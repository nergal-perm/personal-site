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
  return errors;
}

function validateArray(schema, instance, definitions) {
  if (schema.type !== "array") return [];
  if (!Array.isArray(instance)) {
    return [`expected array, got ${instance === null ? "null" : typeof instance}`];
  }

  const errors = [];
  for (const item of instance) {
    errors.push(...validateAgainstSchema(schema.items, item, definitions));
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

function validateAgainstSchema(schema, instance, definitions) {
  definitions = definitions || schema.definitions || {};

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
