const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { EventEmitter } = require("node:events");

const { createBridgeClient } = require("../bridge-client.js");

const SCHEMA_PATH = path.join(__dirname, "..", "..", "bridge-contract", "schema-v2.json");

function loadSchema() {
  return JSON.parse(fs.readFileSync(SCHEMA_PATH, "utf8"));
}

function resolveRef(ref, definitions) {
  const name = ref.replace("#/definitions/", "");
  return definitions[name];
}

function isPlainObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validateConst(schema, instance) {
  if (schema.const === undefined) return [];
  if (instance === schema.const) return [];
  return [`expected const ${schema.const}, got ${instance}`];
}

function validateEnum(schema, instance) {
  if (!schema.enum) return [];
  if (schema.enum.includes(instance)) return [];
  return [`expected one of [${schema.enum}], got ${instance}`];
}

function validateObject(schema, instance, definitions) {
  if (schema.type !== "object") return [];
  if (!isPlainObject(instance)) {
    return [`expected object, got ${instance === null ? "null" : typeof instance}`];
  }

  const errors = [];
  for (const key of schema.required || []) {
    if (!Object.hasOwn(instance, key)) {
      errors.push(`missing required property "${key}"`);
    }
  }
  for (const [key, propSchema] of Object.entries(schema.properties || {})) {
    if (Object.hasOwn(instance, key)) {
      errors.push(...validateAgainstSchema(propSchema, instance[key], definitions));
    }
  }
  errors.push(...validateAdditionalProperties(schema, instance));
  return errors;
}

function validateAdditionalProperties(schema, instance) {
  if (schema.additionalProperties !== false) return [];

  const declared = new Set(Object.keys(schema.properties || {}));
  return Object.keys(instance)
    .filter((key) => !declared.has(key))
    .map((key) => `unexpected additional property "${key}"`);
}

function validateArray(schema, instance, definitions) {
  if (schema.type !== "array") return [];
  if (!Array.isArray(instance)) {
    return [`expected array, got ${instance === null ? "null" : typeof instance}`];
  }

  const errors = validateArrayLength(schema, instance);
  if (schema.items) {
    for (const item of instance) {
      errors.push(...validateAgainstSchema(schema.items, item, definitions));
    }
  }
  return errors;
}

function validateArrayLength(schema, instance) {
  const errors = [];
  if (Number.isInteger(schema.minItems) && instance.length < schema.minItems) {
    errors.push(`expected at least ${schema.minItems} items, got ${instance.length}`);
  }
  if (Number.isInteger(schema.maxItems) && instance.length > schema.maxItems) {
    errors.push(`expected at most ${schema.maxItems} items, got ${instance.length}`);
  }
  return errors;
}

function validatePrimitiveType(schema, instance) {
  if (schema.type === "boolean" && typeof instance !== "boolean") {
    return [`expected boolean, got ${typeof instance}`];
  }
  if (schema.type === "string" && typeof instance !== "string") {
    return [`expected string, got ${typeof instance}`];
  }
  if (schema.type === "integer" && !Number.isInteger(instance)) {
    return [`expected integer, got ${instance}`];
  }
  return [];
}

// Keywords this hand-rolled validator actually interprets. Anything outside the
// list is refused loudly rather than ignored, so a future tightening of
// bridge-contract/schema-v2.json can never pass unchecked while this gate
// reports green.
const SUPPORTED_KEYWORDS = new Set([
  "$schema",
  "$id",
  "$ref",
  "title",
  "description",
  "definitions",
  "type",
  "const",
  "enum",
  "required",
  "properties",
  "additionalProperties",
  "items",
  "minItems",
  "maxItems",
]);

function assertOnlySupportedKeywords(schema) {
  const unsupported = Object.keys(schema).filter((key) => !SUPPORTED_KEYWORDS.has(key));
  if (unsupported.length > 0) {
    throw new Error(`unsupported schema keyword(s): ${unsupported.join(", ")}`);
  }
}

function validateAgainstSchema(schema, instance, definitions) {
  definitions = definitions || schema.definitions || {};
  assertOnlySupportedKeywords(schema);

  if (schema.$ref) {
    return validateAgainstSchema(resolveRef(schema.$ref, definitions), instance, definitions);
  }

  return [
    ...validateConst(schema, instance),
    ...validateEnum(schema, instance),
    ...validateObject(schema, instance, definitions),
    ...validateArray(schema, instance, definitions),
    ...validatePrimitiveType(schema, instance),
  ];
}

function blockedFixture(message) {
  return {
    schemaVersion: 2,
    command: "inspect-publication",
    ok: false,
    status: "metadata_blocked",
    diagnostics: [{ field: "note", message, blocking: true }],
    workspaceHealth: [],
  };
}

function createFakeSpawn({ stdout, exitCode }) {
  return () => {
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    process.nextTick(() => {
      child.stdout.emit("data", Buffer.from(stdout));
      child.emit("close", exitCode, null);
    });
    return child;
  };
}

test("unsafe-path fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = blockedFixture("Note path escapes the vault root.");
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("absent-note fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = blockedFixture("Note was not found in the vault.");
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant blocked response", async () => {
  const fixture = blockedFixture("Note was not found in the vault.");
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 1 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/does-not-exist.md");
  assert.deepEqual(result, fixture);
});

// Negative control: each case below mutates an otherwise-conformant fixture so
// it violates exactly one schema rule. If validateAgainstSchema were replaced
// with a stub that always returns [], every case here would start failing,
// proving the positive-path tests above are actually exercising validation
// logic and not just rubber-stamping any object.
const NONCONFORMANT_CASES = [
  {
    name: "wrong const for schemaVersion",
    mutate: (fixture) => ({ ...fixture, schemaVersion: 3 }),
  },
  {
    name: "wrong enum value for command",
    mutate: (fixture) => ({ ...fixture, command: "not-a-real-command" }),
  },
  {
    name: "wrong primitive type for ok",
    mutate: (fixture) => ({ ...fixture, ok: "false" }),
  },
  {
    name: "missing required property status",
    mutate: (fixture) => {
      const { status, ...rest } = fixture;
      return rest;
    },
  },
  {
    name: "invalid nested diagnostics[0] missing field",
    mutate: (fixture) => ({
      ...fixture,
      diagnostics: [{ message: "oops", blocking: true }],
    }),
  },
];

for (const { name, mutate } of NONCONFORMANT_CASES) {
  test(`validator rejects nonconformant fixture: ${name}`, () => {
    const schema = loadSchema();
    const fixture = mutate(blockedFixture("Note was not found in the vault."));
    const errors = validateAgainstSchema(schema, fixture);
    assert.ok(errors.length > 0, `expected validation errors for case: ${name}`);
  });
}

// bridge-contract/schema-v2.json deliberately sets `additionalProperties: true`
// everywhere for forward compatibility, and declares no array-length bounds, so
// these keywords are exercised against a small inline schema instead. Without
// them the validator would silently accept a future contract that tightens the
// shape — the same class of gap the negative controls above guard against.
const STRICT_SCHEMA = {
  type: "object",
  required: ["name", "tags"],
  additionalProperties: false,
  properties: {
    name: { type: "string" },
    tags: { type: "array", minItems: 1, maxItems: 2, items: { type: "string" } },
  },
};

const STRICT_CASES = [
  {
    name: "undeclared property under additionalProperties: false",
    instance: { name: "note", tags: ["a"], sneaky: true },
  },
  {
    name: "empty array under minItems",
    instance: { name: "note", tags: [] },
  },
  {
    name: "oversized array under maxItems",
    instance: { name: "note", tags: ["a", "b", "c"] },
  },
];

test("validator accepts an instance satisfying additionalProperties/minItems/maxItems", () => {
  assert.deepEqual(validateAgainstSchema(STRICT_SCHEMA, { name: "note", tags: ["a"] }), []);
});

for (const { name, instance } of STRICT_CASES) {
  test(`validator rejects strict-schema violation: ${name}`, () => {
    const errors = validateAgainstSchema(STRICT_SCHEMA, instance);
    assert.ok(errors.length > 0, `expected validation errors for case: ${name}`);
  });
}

test("validator refuses to silently ignore an unsupported schema keyword", () => {
  assert.throws(
    () => validateAgainstSchema({ type: "string", pattern: "^a" }, "b"),
    /unsupported schema keyword/i,
  );
});

function essayInspectedFixture() {
  return {
    schemaVersion: 2,
    command: "inspect-publication",
    ok: true,
    status: "not_prepared",
    identity: { publicCollection: "blog", publicContentType: "essay", publicId: "my-essay" },
    candidateState: "absent",
    approvedSnapshotState: "absent",
    semanticReferenceState: "absent",
    releaseState: "absent",
    diagnostics: [],
    workspaceHealth: [],
  };
}

test("valid-essay fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = essayInspectedFixture();
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant valid-essay response", async () => {
  const fixture = essayInspectedFixture();
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 0 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/my-essay.md");
  assert.deepEqual(result, fixture);
});
