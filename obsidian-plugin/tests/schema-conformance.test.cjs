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

function validateAgainstSchema(schema, instance, definitions) {
  definitions = definitions || schema.definitions || {};
  const errors = [];

  if (schema.$ref) {
    return validateAgainstSchema(resolveRef(schema.$ref, definitions), instance, definitions);
  }
  if (schema.const !== undefined && instance !== schema.const) {
    errors.push(`expected const ${schema.const}, got ${instance}`);
  }
  if (schema.enum && !schema.enum.includes(instance)) {
    errors.push(`expected one of [${schema.enum}], got ${instance}`);
  }
  if (schema.type === "object") {
    for (const key of schema.required || []) {
      if (!(key in instance)) errors.push(`missing required property "${key}"`);
    }
    for (const [key, propSchema] of Object.entries(schema.properties || {})) {
      if (key in instance) {
        errors.push(...validateAgainstSchema(propSchema, instance[key], definitions));
      }
    }
  }
  if (schema.type === "array") {
    for (const item of instance) {
      errors.push(...validateAgainstSchema(schema.items, item, definitions));
    }
  }
  if (schema.type === "boolean" && typeof instance !== "boolean") {
    errors.push(`expected boolean, got ${typeof instance}`);
  }
  if (schema.type === "string" && typeof instance !== "string") {
    errors.push(`expected string, got ${typeof instance}`);
  }
  if (schema.type === "integer" && !Number.isInteger(instance)) {
    errors.push(`expected integer, got ${instance}`);
  }
  return errors;
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

function fakeSpawnResult({ stdout, exitCode }) {
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
    spawn: fakeSpawnResult({ stdout: JSON.stringify(fixture), exitCode: 1 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/does-not-exist.md");
  assert.deepEqual(result, fixture);
});
