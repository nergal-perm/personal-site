import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import crypto from "node:crypto";
import {
  appendFile,
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(import.meta.dirname, "..");
const gateScript = path.join(projectRoot, "scripts/check-content.mjs");
const payloadRoots = [
  "public/assets/vault",
  "src/content",
  "src/data/pages",
];
const pageIds = [
  "about",
  "concepts",
  "essays",
  "home",
  "library",
  "music",
  "notes",
  "search",
  "claims",
];

test("accepts the last provenance-valid materialized release", async () => {
  const fixture = await writeProvenanceFixture();
  try {
    const result = await runGate(fixture.env);
    assert.match(result.stdout, /Content validation passed successfully/);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test("rejects a modified managed file", async () => {
  const fixture = await writeProvenanceFixture();
  try {
    await appendFile(fixture.ruMarkdown, "\nmanual change\n", "utf8");
    await assertGateRejects(fixture.env, /release-provenance-mismatch/i);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test("uses Java-compatible natural ordering for non-ASCII provenance paths", () => {
  assert.deepEqual(["ä", "z"].sort(comparePaths), ["z", "ä"]);
  assert.deepEqual(["\uE000", "\u{10000}"].sort(comparePaths), ["\u{10000}", "\uE000"]);
});

async function runGate(extraEnv = {}) {
  return execFileAsync(process.execPath, [gateScript], {
    cwd: projectRoot,
    env: { ...process.env, CI: "1", NO_COLOR: "1", ...extraEnv },
    maxBuffer: 10 * 1024 * 1024,
  });
}

async function assertGateRejects(env, pattern) {
  await assert.rejects(
    runGate(env),
    (error) => {
      const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
      assert.match(output, pattern);
      return true;
    },
  );
}

async function writeProvenanceFixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), "astro-provenance-"));
  for (const relative of payloadRoots) {
    await mkdir(path.join(root, relative), { recursive: true });
  }
  const ruMarkdown = path.join(root, "src/content/blog/ru/provenance-fixture.md");
  const enMarkdown = path.join(root, "src/content/blog/en/provenance-fixture.md");
  await mkdir(path.dirname(ruMarkdown), { recursive: true });
  await mkdir(path.dirname(enMarkdown), { recursive: true });
  await writeFile(ruMarkdown, markdown("ru"), "utf8");
  await writeFile(enMarkdown, markdown("en"), "utf8");
  for (const language of ["ru", "en"]) {
    await mkdir(path.join(root, "src/data/pages", language), { recursive: true });
    for (const id of pageIds) {
      await writeFile(
        path.join(root, "src/data/pages", language, `${id}.json`),
        `${JSON.stringify(page(id, language), null, 2)}\n`,
        "utf8",
      );
    }
  }
  const manifest = await provenance(root);
  const manifestPath = path.join(root, ".astro-export/release-provenance.json");
  await mkdir(path.dirname(manifestPath), { recursive: true });
  await writeFile(manifestPath, JSON.stringify(manifest), "utf8");
  return {
    root,
    ruMarkdown,
    env: {
      ASTRO_CONTENT_DIR: path.join(root, "src/content"),
      ASTRO_PAGES_DIR: path.join(root, "src/data/pages"),
      ASTRO_RELEASE_MANIFEST: manifestPath,
    },
  };
}

function markdown(language) {
  const translation = language === "en" ? "translationOf: provenance-fixture\n" : "";
  return `---
id: provenance-fixture
title: Provenance fixture ${language}
publish: true
contentType: note
description: Valid synthetic provenance fixture.
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: provenance-fixture-source
translationStatus: ${language === "ru" ? "source" : "generated"}
---

Body.
`;
}

function page(id, language) {
  const data = {
    id,
    type: pageType(id),
    searchable: false,
    topics: [],
    links: id === "home" ? ["provenance-fixture"] : [],
    title: `Gate page ${language} ${id}`,
    summary: "Valid synthetic page.",
  };
  if (id !== "search") {
    data.language = language;
    data.sourceLanguage = "ru";
    data.translationStatus = language === "ru" ? "source" : "generated";
    if (language === "en") data.translationOf = id;
  }
  return data;
}

function pageType(id) {
  return {
    about: "page",
    concepts: "concept",
    essays: "essay",
    home: "page",
    library: "book",
    music: "album",
    notes: "note",
    search: "search",
    claims: "claim",
  }[id];
}

async function provenance(root) {
  const withoutDigest = {
    schemaVersion: 1,
    selectedPages: [
      {
        pageRef: "vault-ref-provenance-fixture",
        publicId: "provenance-fixture",
        sourcePath: "blog/Provenance fixture.md",
        ruSha256: sha256(Buffer.from("approved ru", "utf8")),
        enSha256: sha256(Buffer.from("approved en", "utf8")),
        referencesSha256: sha256(Buffer.from("{}", "utf8")),
        ruProjectionSha256: sha256(Buffer.from("Body.", "utf8")),
        enProjectionSha256: sha256(Buffer.from("Body.", "utf8")),
      },
    ],
    managedTrees: await Promise.all(payloadRoots.map(async (relative) => ({
      relative,
      sha256: await hashTree(path.join(root, relative)),
    }))),
    managedFiles: await hashPayloadFiles(root),
    activationCount: 0,
    deactivationCount: 0,
    payloadDigest: "",
  };
  return {
    ...withoutDigest,
    payloadDigest: sha256(Buffer.from(JSON.stringify(withoutDigest), "utf8")),
  };
}

async function hashPayloadFiles(root) {
  const records = [];
  for (const relativeRoot of payloadRoots) {
    const treeRoot = path.join(root, relativeRoot);
    for (const filePath of await listTree(treeRoot)) {
      const relative = slash(path.relative(root, filePath));
      const stat = fs.lstatSync(filePath);
      if (stat.isDirectory()) continue;
      records.push({ path: relative, sha256: sha256(await readFile(filePath)) });
    }
  }
  return records.sort((left, right) => left.path.localeCompare(right.path));
}

async function hashTree(root) {
  const digest = crypto.createHash("sha256");
  for (const filePath of await listTree(root)) {
    const relative = slash(path.relative(root, filePath));
    const relativeBytes = Buffer.from(relative, "utf8");
    const stat = fs.lstatSync(filePath);
    const payload = stat.isDirectory() ? Buffer.alloc(0) : await readFile(filePath);
    digest.update(Buffer.from(stat.isDirectory() ? "D" : "F"));
    digest.update(lengthBuffer(relativeBytes.length));
    digest.update(relativeBytes);
    digest.update(lengthBuffer(payload.length));
    digest.update(payload);
  }
  return digest.digest("hex");
}

async function listTree(root) {
  const results = [];
  async function visit(dir) {
    for (const name of (await readdir(dir)).sort()) {
      const child = path.join(dir, name);
      results.push(child);
      if (fs.lstatSync(child).isDirectory()) await visit(child);
    }
  }
  await visit(root);
  return results.sort((left, right) =>
    slash(path.relative(root, left)).localeCompare(slash(path.relative(root, right))),
  );
}

function comparePaths(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function lengthBuffer(length) {
  const buffer = Buffer.alloc(8);
  buffer.writeBigInt64BE(BigInt(length));
  return buffer;
}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function slash(value) {
  return value.split(path.sep).join("/");
}
