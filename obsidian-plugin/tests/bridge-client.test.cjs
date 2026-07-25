const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const Module = require("node:module");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

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

function clientWith(fake, overrides = {}) {
  const { createBridgeClient } = bridgeExports();
  return createBridgeClient({
    spawn: fake.spawn,
    exporterRoot: "/Users/example/Personal Wiki/tools/astro-export",
    vaultPath: "/Users/example/Personal Wiki/knowledge-base",
    exporterBinary: "/Users/example/Dev/astro-export-java/target/astro-export",
    env: { PATH: "/usr/bin:/bin" },
    homeDir: "/Users/example",
    platform: "darwin",
    ...overrides,
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
    executable: "/Users/example/Dev/astro-export-java/target/astro-export",
    args: [
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
      env: {
        PATH: "/usr/bin:/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:/Users/example/.local/bin",
      },
    },
  });
  assert.equal(fake.calls[0].args.some((argument) => argument.includes("touch pwned")), false);
});

test("PATH is widened with Homebrew and user-local bin directories so GUI-launched hosts can find CLI dependencies like rg", async () => {
  const fake = fakeSpawnResult({ stdout: JSON.stringify(response("prepare")) });
  const client = clientWith(fake, {
    env: { PATH: "/usr/bin:/bin", CUSTOM_VAR: "kept" },
  });

  await client.run("prepare", "concepts/Current.md");

  const { env } = fake.calls[0].options;
  assert.equal(env.CUSTOM_VAR, "kept");
  assert.deepEqual(env.PATH.split(":"), [
    "/usr/bin",
    "/bin",
    "/opt/homebrew/bin",
    "/opt/homebrew/sbin",
    "/usr/local/bin",
    "/usr/local/sbin",
    "/Users/example/.local/bin",
  ]);
});

test("PATH augmentation does not duplicate directories already present", async () => {
  const fake = fakeSpawnResult({ stdout: JSON.stringify(response("prepare")) });
  const client = clientWith(fake, {
    env: { PATH: "/usr/bin:/opt/homebrew/bin" },
  });

  await client.run("prepare", "concepts/Current.md");

  const pathEntries = fake.calls[0].options.env.PATH.split(":");
  assert.equal(pathEntries.filter((entry) => entry === "/opt/homebrew/bin").length, 1);
});

test("PATH is left untouched on Windows hosts", async () => {
  const fake = fakeSpawnResult({ stdout: JSON.stringify(response("prepare")) });
  const client = clientWith(fake, {
    env: { PATH: "C:\\Windows\\System32" },
    platform: "win32",
  });

  await client.run("prepare", "concepts/Current.md");

  assert.equal(fake.calls[0].options.env.PATH, "C:\\Windows\\System32");
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
      assert.match(error.diagnostic.message, /\/Users\/example\/Dev\/astro-export-java\/target\/astro-export/);
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

test("malformed diagnostic entries are rejected at the bridge boundary", async () => {
  const { BridgeClientError } = bridgeExports();
  for (const overrides of [
    { diagnostics: [null] },
    { workspaceHealth: [{ field: "other", message: 42, blocking: true }] },
  ]) {
    const fake = fakeSpawnResult({
      stdout: JSON.stringify(response("prepare", overrides)),
    });
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

function loadPluginHarness({
  savedData = null,
  existsSync = fs.existsSync,
  homeDirectory = os.homedir(),
} = {}) {
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
      this.savedData = savedData;
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
    if (request === "node:fs") return { existsSync };
    if (request === "node:os") return { homedir: () => homeDirectory };
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

function loadShippedMainWithHostRequire() {
  const relativeRequests = [];
  const mainPath = path.resolve(__dirname, "../main.js");
  const hostRequire = (request) => {
    if (request.startsWith(".")) {
      relativeRequests.push(request);
      throw new Error(`Host require cannot resolve ${request}`);
    }
    const externals = {
      "node:path": path,
      "node:child_process": { spawn() {} },
      "node:fs": fs,
      "node:os": os,
      electron: { shell: { async openPath() { return ""; } } },
      obsidian: {
        Modal: class {},
        Notice: class {},
        Plugin: class {},
        PluginSettingTab: class {},
        Setting: class {},
        TFile: class {},
      },
    };
    if (!Object.hasOwn(externals, request)) {
      throw new Error(`Unexpected host dependency: ${request}`);
    }
    return externals[request];
  };
  const pluginModule = { exports: {} };
  vm.runInNewContext(
    fs.readFileSync(mainPath, "utf8"),
    {
      module: pluginModule,
      exports: pluginModule.exports,
      require: hostRequire,
    },
    { filename: mainPath },
  );
  return { pluginModule, relativeRequests };
}

test("shipped main loads when the Obsidian host rejects plugin-relative require", () => {
  const loaded = loadShippedMainWithHostRequire();

  assert.equal(typeof loaded.pluginModule.exports, "function");
  assert.deepEqual(loaded.relativeRequests, []);
});

test("desktop manifest, four Russian command labels and local defaults are registered", async () => {
  const manifest = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "../manifest.json"), "utf8"),
  );
  assert.equal(manifest.id, "astro-publication-workflow");
  assert.equal(manifest.isDesktopOnly, true);

  const harness = loadPluginHarness({ existsSync: () => false });
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
    exporterBinary: "/Users/eugene/Dev/astro-export-java/target/astro-export",
  });
  assert.equal(plugin.settingTabs.length, 1);
});

test("default exporter binary is the local GraalVM build without overriding saved settings", async () => {
  const defaultHarness = loadPluginHarness();
  const defaultPlugin = new defaultHarness.PluginClass(defaultHarness.app);
  await defaultPlugin.onload();
  assert.equal(
    defaultPlugin.settings.exporterBinary,
    "/Users/eugene/Dev/astro-export-java/target/astro-export",
  );

  const explicitHarness = loadPluginHarness({
    savedData: { exporterBinary: "/custom/tools/astro-export" },
  });
  const explicitPlugin = new explicitHarness.PluginClass(explicitHarness.app);
  await explicitPlugin.onload();
  assert.equal(explicitPlugin.settings.exporterBinary, "/custom/tools/astro-export");
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

test("refresh sends no note and reports the six-state summary", async () => {
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
          ready_to_publish: 4,
          translation_failed: 5,
          stale: 6,
        },
        updated: 7,
        unchanged: 8,
        uncertain: 0,
      });
    },
  };

  await command(plugin, "refresh-publication-queue").callback();

  assert.deepEqual(bridgeCalls, [["refresh-publication-queue"]]);
  const finished = harness.notices.map(({ message }) => message).join("\n");
  assert.match(finished, /готово к проверке: 3/);
  assert.match(finished, /готово к публикации: 4/);
  assert.match(finished, /устарело: 6/);
  assert.match(finished, /обновлено: 7/);
});

test("community plugin enablement retains the live list and adds only this plugin", () => {
  const enabled = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "../../../community-plugins.json"), "utf8"),
  );
  assert.equal(enabled.filter((id) => id === "astro-publication-workflow").length, 1);
  assert.ok(enabled.includes("metadata-menu"));
  assert.ok(enabled.includes("obsidian-local-rest-api"));
});
