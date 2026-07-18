const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const Module = require("node:module");
const path = require("node:path");
const test = require("node:test");

let bridgeModule = null;
try {
  bridgeModule = require("../bridge-client.js");
} catch (_error) {
  // The first RED run intentionally precedes the bridge implementation.
}

function bridgeExports() {
  assert.ok(bridgeModule, "bridge-client.js must exist");
  assert.equal(typeof bridgeModule.createBridgeClient, "function");
  assert.equal(typeof bridgeModule.BridgeClientError, "function");
  return bridgeModule;
}

function response(command, overrides = {}) {
  return {
    schemaVersion: 1,
    command,
    ok: true,
    status: "ready_for_review",
    note: "concepts/Boundary; note.md",
    collection: "concepts",
    publicId: "boundary-note",
    reviewDirectory: "/tmp/review/concepts/boundary-note",
    pairFreshness: "fresh",
    translationStatus: "generated",
    diagnostics: [],
    workspaceHealth: [],
    jobId: "job-1",
    ...overrides,
  };
}

function fakeSpawnResult({ stdout = "", stderr = "", exitCode = 0, error = null } = {}) {
  const calls = [];
  const spawn = (executable, args, options) => {
    calls.push({ executable, args, options });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    process.nextTick(() => {
      if (stdout) child.stdout.emit("data", Buffer.from(stdout));
      if (stderr) child.stderr.emit("data", Buffer.from(stderr));
      if (error) child.emit("error", error);
      child.emit("close", exitCode, null);
    });
    return child;
  };
  return { spawn, calls };
}

function clientWith(fake) {
  const { createBridgeClient } = bridgeExports();
  return createBridgeClient({
    spawn: fake.spawn,
    exporterRoot: "/Users/example/Personal Wiki/tools/astro-export",
    vaultPath: "/Users/example/Personal Wiki/knowledge-base",
    uvExecutable: "/opt/homebrew/bin/uv",
  });
}

test("prepare preserves every argument boundary and disables the shell", async () => {
  const payload = response("prepare");
  const fake = fakeSpawnResult({ stdout: `${JSON.stringify(payload)}\n` });
  const client = clientWith(fake);

  const result = await client.run("prepare", "concepts/Boundary; note.md");

  assert.deepEqual(result, payload);
  assert.equal(fake.calls.length, 1);
  assert.deepEqual(fake.calls[0], {
    executable: "/opt/homebrew/bin/uv",
    args: [
      "run",
      "astro-export",
      "prepare",
      "--vault",
      "/Users/example/Personal Wiki/knowledge-base",
      "--note",
      "concepts/Boundary; note.md",
      "--review",
      "/Users/example/Personal Wiki/tools/astro-export/review",
      "--jobs",
      "/Users/example/Personal Wiki/tools/astro-export/.publication-jobs",
      "--json",
    ],
    options: {
      cwd: "/Users/example/Personal Wiki/tools/astro-export",
      shell: false,
      windowsHide: true,
    },
  });
  assert.equal(fake.calls[0].args.some((argument) => argument.includes("touch pwned")), false);
});

test("refresh-publication-queue never sends a current-note path", async () => {
  const payload = response("refresh-publication-queue", {
    note: null,
    collection: null,
    publicId: null,
    reviewDirectory: null,
    pairFreshness: null,
    translationStatus: null,
    jobId: null,
    status: "refreshed",
    summary: {
      metadata_blocked: 0,
      translating: 0,
      ready_for_review: 1,
      translation_failed: 0,
      stale: 0,
    },
    updated: 1,
    unchanged: 0,
    uncertain: 0,
  });
  const fake = fakeSpawnResult({ stdout: JSON.stringify(payload) });
  const client = clientWith(fake);

  await client.run("refresh-publication-queue");

  assert.equal(fake.calls[0].args.includes("--note"), false);
  assert.deepEqual(fake.calls[0].args, [
    "run",
    "astro-export",
    "refresh-publication-queue",
    "--vault",
    "/Users/example/Personal Wiki/knowledge-base",
    "--review",
    "/Users/example/Personal Wiki/tools/astro-export/review",
    "--jobs",
    "/Users/example/Personal Wiki/tools/astro-export/.publication-jobs",
    "--json",
  ]);
});

test("a structured blocked response survives the exporter exit code", async () => {
  const payload = response("inspect-publication", {
    ok: false,
    status: "metadata_blocked",
    diagnostics: [
      { field: "publicId", message: "missing publicId", blocking: true },
    ],
  });
  const fake = fakeSpawnResult({
    stdout: JSON.stringify(payload),
    stderr: "diagnostic detail\n",
    exitCode: 1,
  });
  const client = clientWith(fake);

  assert.deepEqual(
    await client.run("inspect-publication", "concepts/Boundary; note.md"),
    payload,
  );
});

test("a non-zero process without bridge JSON becomes a local diagnostic", async () => {
  const { BridgeClientError } = bridgeExports();
  const fake = fakeSpawnResult({ stderr: "usage failed", exitCode: 2 });
  const client = clientWith(fake);

  await assert.rejects(
    client.run("mark-reviewed", "concepts/Boundary; note.md"),
    (error) => {
      assert.ok(error instanceof BridgeClientError);
      assert.equal(error.code, "process_failed");
      assert.deepEqual(error.diagnostic, {
        field: "bridge",
        message: "Exporter завершился с кодом 2 и не вернул корректный JSON.",
        blocking: true,
      });
      return true;
    },
  );
});

test("a spawn failure becomes a local diagnostic", async () => {
  const { BridgeClientError } = bridgeExports();
  const fake = fakeSpawnResult({
    error: Object.assign(new Error("not found"), { code: "ENOENT" }),
    exitCode: null,
  });
  const client = clientWith(fake);

  await assert.rejects(
    client.run("prepare", "concepts/Boundary; note.md"),
    (error) => {
      assert.ok(error instanceof BridgeClientError);
      assert.equal(error.code, "spawn_failed");
      assert.equal(error.diagnostic.field, "bridge");
      assert.match(error.diagnostic.message, /не удалось запустить exporter/i);
      return true;
    },
  );
});

test("malformed or multiple JSON values become a local diagnostic", async () => {
  const { BridgeClientError } = bridgeExports();
  for (const stdout of [
    "not json",
    `${JSON.stringify(response("prepare"))}\n${JSON.stringify({ extra: true })}`,
    "[]",
  ]) {
    const fake = fakeSpawnResult({ stdout });
    const client = clientWith(fake);
    await assert.rejects(
      client.run("prepare", "concepts/Boundary; note.md"),
      (error) => {
        assert.ok(error instanceof BridgeClientError);
        assert.equal(error.code, "invalid_json");
        assert.deepEqual(error.diagnostic, {
          field: "bridge",
          message: "Exporter вернул некорректный JSON-ответ.",
          blocking: true,
        });
        return true;
      },
    );
  }
});

test("note commands reject absolute, traversal and non-Markdown paths before spawn", async () => {
  const fake = fakeSpawnResult({ stdout: JSON.stringify(response("prepare")) });
  const client = clientWith(fake);

  for (const notePath of [
    "/tmp/note.md",
    "../outside.md",
    "concepts/not-markdown.txt",
    "concepts\\windows.md",
  ]) {
    await assert.rejects(client.run("prepare", notePath), /vault-relative Markdown path/);
  }
  assert.equal(fake.calls.length, 0);
});

function loadPluginHarness() {
  const notices = [];
  const modals = [];
  const openedPaths = [];

  class FakeElement {
    constructor(text = "") {
      this.ownText = text;
      this.children = [];
      this.listeners = {};
    }

    empty() {
      this.ownText = "";
      this.children = [];
    }

    addClass() {}

    createEl(_tag, options = {}) {
      const child = new FakeElement(options.text || "");
      this.children.push(child);
      return child;
    }

    appendText(text) {
      this.ownText += text;
    }

    addEventListener(event, callback) {
      this.listeners[event] = callback;
    }

    text() {
      return [this.ownText, ...this.children.map((child) => child.text())].join(" ");
    }
  }

  class FakeTFile {
    constructor(filePath, extension = "md") {
      this.path = filePath;
      this.extension = extension;
    }
  }

  class FakeNotice {
    constructor(message) {
      this.message = message;
      this.hidden = false;
      notices.push(this);
    }

    hide() {
      this.hidden = true;
    }
  }

  class FakeModal {
    constructor(app) {
      this.app = app;
      this.contentEl = new FakeElement();
      modals.push(this);
    }

    open() {
      if (this.onOpen) this.onOpen();
    }

    close() {
      if (this.onClose) this.onClose();
    }
  }

  class FakeSetting {
    constructor(container) {
      this.container = container;
    }

    setName(name) {
      this.container.createEl("span", { text: name });
      return this;
    }

    setDesc(description) {
      this.container.createEl("span", { text: description });
      return this;
    }

    addText(callback) {
      const text = {
        setPlaceholder() { return this; },
        setValue() { return this; },
        onChange(handler) { this.handler = handler; return this; },
      };
      callback(text);
      return this;
    }
  }

  class FakePluginSettingTab {
    constructor(app, plugin) {
      this.app = app;
      this.plugin = plugin;
      this.containerEl = new FakeElement();
    }
  }

  class FakePlugin {
    constructor(app) {
      this.app = app;
      this.commands = [];
      this.settingTabs = [];
      this.savedData = null;
    }

    async loadData() {
      return this.savedData;
    }

    async saveData(data) {
      this.savedData = data;
    }

    addCommand(command) {
      this.commands.push(command);
    }

    addSettingTab(tab) {
      this.settingTabs.push(tab);
    }
  }

  const app = {
    vault: {
      adapter: {
        getBasePath: () => "/Users/example/Personal Wiki/knowledge-base",
      },
    },
    workspace: {
      activeFile: null,
      getActiveFile() {
        return this.activeFile;
      },
    },
  };
  const fakeObsidian = {
    Modal: FakeModal,
    Notice: FakeNotice,
    Plugin: FakePlugin,
    PluginSettingTab: FakePluginSettingTab,
    Setting: FakeSetting,
    TFile: FakeTFile,
  };
  const fakeElectron = {
    shell: {
      async openPath(reviewPath) {
        openedPaths.push(reviewPath);
        return "";
      },
    },
  };

  const mainPath = path.resolve(__dirname, "../main.js");
  delete require.cache[mainPath];
  const originalLoad = Module._load;
  Module._load = function(request, parent, isMain) {
    if (request === "obsidian") return fakeObsidian;
    if (request === "electron") return fakeElectron;
    return originalLoad.call(this, request, parent, isMain);
  };
  let PluginClass;
  try {
    PluginClass = require(mainPath);
  } finally {
    Module._load = originalLoad;
  }

  return {
    app,
    FakeTFile,
    modals,
    notices,
    openedPaths,
    PluginClass,
  };
}

function command(plugin, id) {
  const registered = plugin.commands.find((item) => item.id === id);
  assert.ok(registered, `command ${id} must be registered`);
  return registered;
}

test("desktop manifest, four Russian command labels and local defaults are registered", async () => {
  const manifest = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "../manifest.json"), "utf8"),
  );
  assert.equal(manifest.id, "astro-publication-workflow");
  assert.equal(manifest.isDesktopOnly, true);

  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();

  assert.deepEqual(
    plugin.commands.map(({ id, name }) => ({ id, name })),
    [
      {
        id: "prepare-current-note-for-public-site",
        name: "Подготовить текущую заметку для публичного сайта",
      },
      {
        id: "open-current-translation-review",
        name: "Открыть проверку перевода текущей заметки",
      },
      {
        id: "mark-current-translation-reviewed",
        name: "Отметить перевод текущей заметки как проверенный",
      },
      {
        id: "refresh-publication-queue",
        name: "Обновить очередь публикации",
      },
    ],
  );
  assert.deepEqual(plugin.settings, {
    exporterRoot: "/Users/example/Personal Wiki/tools/astro-export",
    uvExecutable: "uv",
  });
  assert.equal(plugin.settingTabs.length, 1);
});

test("note commands accept only the active Markdown TFile", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(...args) {
      bridgeCalls.push(args);
      return response("prepare");
    },
  };

  harness.app.workspace.activeFile = { path: "concepts/plain-object.md", extension: "md" };
  await command(plugin, "prepare-current-note-for-public-site").callback();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/not-markdown.txt", "txt");
  await command(plugin, "prepare-current-note-for-public-site").callback();
  assert.equal(bridgeCalls.length, 0);

  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  await command(plugin, "prepare-current-note-for-public-site").callback();
  assert.deepEqual(bridgeCalls, [["prepare", "concepts/Current.md"]]);
  assert.match(harness.modals.at(-1).contentEl.text(), /Открыть проверку/);
  assert.ok(harness.notices.some(({ message }) => /Подготовка/.test(message)));
  assert.ok(harness.notices.some(({ message }) => /готов/.test(message)));
});

test("open review uses inspect-publication and only the bridge-confirmed directory", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(...args) {
      bridgeCalls.push(args);
      return response("inspect-publication", {
        reviewDirectory: "/external/review/concepts/current",
      });
    },
  };

  await command(plugin, "open-current-translation-review").callback();

  assert.deepEqual(bridgeCalls, [["inspect-publication", "concepts/Current.md"]]);
  assert.deepEqual(harness.openedPaths, ["/external/review/concepts/current"]);
});

test("blocked review validation opens a field checklist without optimistic success", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  plugin.bridgeClient = {
    async run() {
      return response("mark-reviewed", {
        ok: false,
        status: "stale",
        diagnostics: [
          {
            field: "translation",
            message: "sourceHash does not match the source note",
            blocking: true,
          },
        ],
      });
    },
  };

  await command(plugin, "mark-current-translation-reviewed").callback();

  assert.match(harness.modals.at(-1).contentEl.text(), /translation/);
  assert.match(harness.modals.at(-1).contentEl.text(), /sourceHash/);
  assert.equal(
    harness.notices.some(({ message }) => /отмечен как проверенный/.test(message)),
    false,
  );
});

test("refresh sends no note and reports the five-state summary", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(...args) {
      bridgeCalls.push(args);
      return response("refresh-publication-queue", {
        note: null,
        status: "refreshed",
        summary: {
          metadata_blocked: 2,
          translating: 1,
          ready_for_review: 3,
          translation_failed: 4,
          stale: 5,
        },
        updated: 6,
        unchanged: 7,
        uncertain: 0,
      });
    },
  };

  await command(plugin, "refresh-publication-queue").callback();

  assert.deepEqual(bridgeCalls, [["refresh-publication-queue"]]);
  const finished = harness.notices.map(({ message }) => message).join("\n");
  assert.match(finished, /готово к проверке: 3/);
  assert.match(finished, /устарело: 5/);
  assert.match(finished, /обновлено: 6/);
});

test("community plugin enablement retains the live list and adds only this plugin", () => {
  const enabled = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "../../../community-plugins.json"), "utf8"),
  );
  assert.equal(enabled.filter((id) => id === "astro-publication-workflow").length, 1);
  assert.ok(enabled.includes("metadata-menu"));
  assert.ok(enabled.includes("obsidian-local-rest-api"));
});
