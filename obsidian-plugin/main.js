const path = require("node:path");
const fs = require("node:fs");
const { spawn: spawnProcess } = require("node:child_process");

// Obsidian evaluates a plugin's main.js through a host-level require, not a
// module loader rooted at this plugin directory. Keep local dependencies in
// this entrypoint instead of adding a relative runtime require here.
const { createBridgeClient } = (() => {
  const nodePath = require("node:path");
  const nodeOs = require("node:os");
  const { spawn: defaultSpawn } = require("node:child_process");

  const BRIDGE_COMMANDS = Object.freeze({
    prepare: { note: true, jobs: true },
    "inspect-publication": { note: true, jobs: false },
    "mark-reviewed": { note: true, jobs: true },
    "refresh-publication-queue": { note: false, jobs: true },
  });

  // GUI-launched hosts (Obsidian via Finder/Dock, not a terminal) inherit macOS's
  // minimal login PATH, which omits Homebrew and other user-installed bin
  // directories. The exporter shells out to CLI dependencies (e.g. ripgrep)
  // resolved through PATH, so those directories must be added explicitly here.
  const EXTRA_UNIX_BIN_DIRS = Object.freeze([
    "/opt/homebrew/bin",
    "/opt/homebrew/sbin",
    "/usr/local/bin",
    "/usr/local/sbin",
  ]);

  function buildChildEnv(sourceEnv, homeDir, platform) {
    if (platform === "win32") {
      return { ...sourceEnv };
    }
    const extraDirs = [...EXTRA_UNIX_BIN_DIRS, nodePath.join(homeDir, ".local", "bin")];
    const segments = (sourceEnv.PATH || "").split(nodePath.delimiter).filter(Boolean);
    const seen = new Set(segments);
    for (const dir of extraDirs) {
      if (!seen.has(dir)) {
        segments.push(dir);
        seen.add(dir);
      }
    }
    return { ...sourceEnv, PATH: segments.join(nodePath.delimiter) };
  }

  class BridgeClientError extends Error {
    constructor(code, message) {
      super(message);
      this.name = "BridgeClientError";
      this.code = code;
      this.diagnostic = {
        field: "bridge",
        message,
        blocking: true,
      };
    }
  }

  function spawnFailureMessage(exporterBinary) {
    return "Не удалось запустить exporter через " +
      `${exporterBinary}. Проверьте путь к бинарнику exporter-а.`;
  }

  function validateNotePath(notePath) {
    const valid =
      typeof notePath === "string" &&
      notePath.length > 0 &&
      !notePath.includes("\\") &&
      !nodePath.posix.isAbsolute(notePath) &&
      nodePath.posix.extname(notePath).toLowerCase() === ".md" &&
      notePath.split("/").every((part) => part !== "" && part !== "." && part !== "..");
    if (!valid) {
      throw new BridgeClientError(
        "invalid_note",
        "Current note must be a vault-relative Markdown path.",
      );
    }
  }

  function parseResponse(stdout, command, exitCode) {
    let payload;
    try {
      payload = JSON.parse(stdout.trim());
    } catch (_error) {
      const processFailed = exitCode !== 0;
      throw new BridgeClientError(
        processFailed ? "process_failed" : "invalid_json",
        processFailed
          ? `Exporter завершился с кодом ${exitCode} и не вернул корректный JSON.`
          : "Exporter вернул некорректный JSON-ответ.",
      );
    }

    if (
      payload !== null &&
      typeof payload === "object" &&
      !Array.isArray(payload) &&
      Number.isInteger(payload.schemaVersion) &&
      payload.schemaVersion !== 2
    ) {
      throw new BridgeClientError(
        "schema_mismatch",
        `Exporter вернул версию схемы ${payload.schemaVersion}; ожидается версия 2. ` +
          "Пересоберите exporter и перезагрузите Obsidian plugin.",
      );
    }

    const isDiagnostic = (diagnostic) =>
      diagnostic !== null &&
      typeof diagnostic === "object" &&
      !Array.isArray(diagnostic) &&
      typeof diagnostic.field === "string" &&
      typeof diagnostic.message === "string" &&
      typeof diagnostic.blocking === "boolean";
    const validObject =
      payload !== null &&
      typeof payload === "object" &&
      !Array.isArray(payload) &&
      payload.schemaVersion === 2 &&
      payload.command === command &&
      typeof payload.ok === "boolean" &&
      Array.isArray(payload.diagnostics) &&
      payload.diagnostics.every(isDiagnostic) &&
      Array.isArray(payload.workspaceHealth) &&
      payload.workspaceHealth.every(isDiagnostic);
    if (!validObject) {
      throw new BridgeClientError(
        "invalid_json",
        "Exporter вернул некорректный JSON-ответ.",
      );
    }
    if (exitCode !== 0 && payload.ok !== false) {
      throw new BridgeClientError(
        "process_failed",
        `Exporter завершился с кодом ${exitCode} и вернул противоречивый ответ.`,
      );
    }
    return payload;
  }

  function createBridgeClient({
    spawn = defaultSpawn,
    exporterRoot,
    vaultPath,
    exporterBinary,
    env = process.env,
    homeDir = nodeOs.homedir(),
    platform = process.platform,
  }) {
    if (!exporterRoot || !vaultPath || !exporterBinary) {
      throw new TypeError("exporterRoot, vaultPath and exporterBinary are required");
    }
    const childEnv = buildChildEnv(env, homeDir, platform);

    return {
      run(command, notePath = null) {
        const contract = BRIDGE_COMMANDS[command];
        if (!contract) {
          return Promise.reject(
            new BridgeClientError("invalid_command", "Unsupported exporter command."),
          );
        }
        if (contract.note) {
          try {
            validateNotePath(notePath);
          } catch (error) {
            return Promise.reject(error);
          }
        }

        const args = [
          command,
          "--vault",
          vaultPath,
        ];
        if (contract.note) args.push("--note", notePath);
        args.push("--review", nodePath.join(exporterRoot, "review"));
        if (contract.jobs) {
          args.push("--jobs", nodePath.join(exporterRoot, ".publication-jobs"));
        }
        args.push("--json");

        return new Promise((resolve, reject) => {
          let child;
          try {
            child = spawn(exporterBinary, args, {
              cwd: exporterRoot,
              shell: false,
              windowsHide: true,
              env: childEnv,
            });
          } catch (_error) {
            reject(
              new BridgeClientError(
                "spawn_failed",
                spawnFailureMessage(exporterBinary),
              ),
            );
            return;
          }

          let stdout = "";
          let stderr = "";
          let settled = false;
          child.stdout.on("data", (chunk) => {
            stdout += chunk.toString("utf8");
          });
          child.stderr.on("data", (chunk) => {
            stderr += chunk.toString("utf8");
          });
          child.on("error", (_error) => {
            if (settled) return;
            settled = true;
            reject(
              new BridgeClientError(
                "spawn_failed",
                spawnFailureMessage(exporterBinary),
              ),
            );
          });
          child.on("close", (exitCode, signal) => {
            if (settled) return;
            settled = true;
            if (exitCode === null) {
              reject(
                new BridgeClientError(
                  "process_failed",
                  `Exporter был остановлен${signal ? ` сигналом ${signal}` : ""}.`,
                ),
              );
              return;
            }
            try {
              resolve(parseResponse(stdout, command, exitCode, stderr));
            } catch (error) {
              reject(error);
            }
          });
        });
      },
    };
  }

  return { createBridgeClient };
})();

const {
  Modal,
  Notice,
  Plugin,
  PluginSettingTab,
  Setting,
  TFile,
} = require("obsidian");

const COMMANDS = [
  {
    id: "prepare-current-note-for-public-site",
    name: "Подготовить текущую заметку для публичного сайта",
    callback: "prepareCurrentNote",
  },
  {
    id: "open-current-translation-review",
    name: "Открыть проверку перевода текущей заметки",
    callback: "openCurrentReview",
  },
  {
    id: "mark-current-translation-reviewed",
    name: "Отметить перевод текущей заметки как проверенный",
    callback: "markCurrentReviewed",
  },
  {
    id: "refresh-publication-queue",
    name: "Обновить очередь публикации",
    callback: "refreshPublicationQueue",
  },
];

const DEFAULT_EXPORTER_BINARY = "/Users/eugene/Dev/astro-export-java/target/astro-export";
const DEFAULT_ZED_CLI = "/Applications/Zed.app/Contents/MacOS/cli";

function localDiagnostic(message, field = "bridge") {
  return { field, message, blocking: true };
}

function validateReviewPlan(plan) {
  if (!plan || !["absent", "complete"].includes(plan.baselineState)) {
    throw new Error("Exporter вернул неизвестное состояние published baseline.");
  }
  if (!Array.isArray(plan.targets) || plan.targets.length !== 2) {
    throw new Error("Exporter должен вернуть ровно две цели проверки.");
  }
  const expectedLanguages = ["ru", "en"];
  return plan.targets.map((target, index) => {
    if (!target || target.language !== expectedLanguages[index]) {
      throw new Error("Цели проверки должны быть упорядочены как ru, затем en.");
    }
    if (
      typeof target.proposedPath !== "string" ||
      !path.isAbsolute(target.proposedPath)
    ) {
      throw new Error(`Exporter вернул некорректный proposed path для ${target.language}.`);
    }
    if (plan.baselineState === "absent" && target.publishedPath !== null) {
      throw new Error(`Absent baseline не должен содержать published path для ${target.language}.`);
    }
    if (
      plan.baselineState === "complete" &&
      (typeof target.publishedPath !== "string" ||
        !path.isAbsolute(target.publishedPath))
    ) {
      throw new Error(`Complete baseline требует published path для ${target.language}.`);
    }
    return target;
  });
}

function zedCliDiagnostic(zedCli) {
  if (typeof zedCli !== "string" || !path.isAbsolute(zedCli)) {
    return localDiagnostic("Укажите абсолютный путь к Zed CLI.", "zed");
  }
  try {
    const stats = fs.lstatSync(zedCli);
    if (!stats.isFile()) {
      return localDiagnostic("Zed CLI должен быть обычным исполняемым файлом.", "zed");
    }
    fs.accessSync(zedCli, fs.constants.X_OK);
    return null;
  } catch (_error) {
    return localDiagnostic(
      `Zed CLI недоступен или не исполняется: ${zedCli}.`,
      "zed",
    );
  }
}

function zedArgs(baselineState, target) {
  return baselineState === "complete"
    ? ["-n", "--diff", target.publishedPath, target.proposedPath]
    : ["-n", target.proposedPath];
}

function runZedTarget(zedCli, baselineState, target) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawnProcess(zedCli, zedArgs(baselineState, target), {
        shell: false,
        windowsHide: true,
        stdio: ["ignore", "ignore", "pipe"],
      });
    } catch (_error) {
      resolve(localDiagnostic(
        `Не удалось запустить окно Zed для ${target.language.toUpperCase()}.`,
        `zed-${target.language}`,
      ));
      return;
    }
    let stderr = "";
    let settled = false;
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", () => {
      if (settled) return;
      settled = true;
      resolve(localDiagnostic(
        `Не удалось запустить окно Zed для ${target.language.toUpperCase()}.`,
        `zed-${target.language}`,
      ));
    });
    child.on("close", (exitCode, signal) => {
      if (settled) return;
      settled = true;
      if (exitCode === 0) {
        resolve(null);
        return;
      }
      const detail = stderr.trim();
      resolve(localDiagnostic(
        `Zed не принял ${target.language.toUpperCase()} review` +
          `${signal ? `; signal ${signal}` : `; exit ${exitCode}`}` +
          `${detail ? `: ${detail}` : "."}`,
        `zed-${target.language}`,
      ));
    });
  });
}

class DiagnosticsModal extends Modal {
  constructor(app, title, diagnostics) {
    super(app);
    this.title = title;
    this.diagnostics = diagnostics.length > 0
      ? diagnostics
      : [localDiagnostic("Exporter не вернул подробностей ошибки.")];
  }

  onOpen() {
    this.contentEl.empty();
    this.contentEl.addClass("astro-publication-workflow-diagnostics");
    this.contentEl.createEl("h2", { text: this.title });
    const list = this.contentEl.createEl("ul");
    for (const diagnostic of this.diagnostics) {
      const item = list.createEl("li");
      item.createEl("strong", { text: `${diagnostic.field || "publication"}: ` });
      item.appendText(diagnostic.message || "Неизвестная ошибка.");
    }
  }

  onClose() {
    this.contentEl.empty();
  }
}

class ReviewReadyModal extends Modal {
  constructor(app, onOpenReview) {
    super(app);
    this.onOpenReview = onOpenReview;
  }

  onOpen() {
    this.contentEl.empty();
    this.contentEl.addClass("astro-publication-workflow-ready");
    this.contentEl.createEl("h2", { text: "Английский черновик готов" });
    this.contentEl.createEl("p", {
      text: "Откройте русскую и английскую версии для проверки в двух окнах Zed.",
    });
    const button = this.contentEl.createEl("button", {
      text: "Открыть проверку",
      cls: "mod-cta",
    });
    button.addEventListener("click", async () => {
      await this.onOpenReview();
      this.close();
    });
  }

  onClose() {
    this.contentEl.empty();
  }
}

class PublicationWorkflowSettingTab extends PluginSettingTab {
  display() {
    const { containerEl } = this;
    containerEl.empty();
    containerEl.createEl("h2", { text: "Подготовка публикаций для Astro" });

    new Setting(containerEl)
      .setName("Каталог exporter-а")
      .setDesc("Локальный каталог с рабочей областью exporter-а (review, .publication-jobs).")
      .addText((text) => text
        .setPlaceholder("/path/to/tools/astro-export")
        .setValue(this.plugin.settings.exporterRoot)
        .onChange(async (value) => {
          this.plugin.settings.exporterRoot = value.trim();
          await this.plugin.saveSettings();
        }));

    new Setting(containerEl)
      .setName("Бинарник exporter-а")
      .setDesc("Абсолютный путь к нативному бинарнику astro-export (GraalVM).")
      .addText((text) => text
        .setPlaceholder(DEFAULT_EXPORTER_BINARY)
        .setValue(this.plugin.settings.exporterBinary)
        .onChange(async (value) => {
          this.plugin.settings.exporterBinary = value.trim();
          await this.plugin.saveSettings();
        }));

    new Setting(containerEl)
      .setName("Zed CLI")
      .setDesc("Абсолютный путь к CLI внутри Zed.app; каждая языковая версия открывается в новом окне.")
      .addText((text) => text
        .setPlaceholder(DEFAULT_ZED_CLI)
        .setValue(this.plugin.settings.zedCli)
        .onChange(async (value) => {
          this.plugin.settings.zedCli = value.trim();
          await this.plugin.saveSettings();
        }));
  }
}

module.exports = class AstroPublicationWorkflowPlugin extends Plugin {
  async onload() {
    const vaultPath = this.app.vault.adapter.getBasePath();
    const saved = (await this.loadData()) || {};
    this.settings = {
      exporterRoot: saved.exporterRoot || path.resolve(vaultPath, "../tools/astro-export"),
      exporterBinary: saved.exporterBinary || DEFAULT_EXPORTER_BINARY,
      zedCli: saved.zedCli || DEFAULT_ZED_CLI,
    };
    this.vaultPath = vaultPath;
    this.resetBridgeClient();
    this.addSettingTab(new PublicationWorkflowSettingTab(this.app, this));

    for (const definition of COMMANDS) {
      this.addCommand({
        id: definition.id,
        name: definition.name,
        callback: () => this[definition.callback](),
      });
    }
  }

  resetBridgeClient() {
    this.bridgeClient = createBridgeClient({
      exporterRoot: this.settings.exporterRoot,
      vaultPath: this.vaultPath,
      exporterBinary: this.settings.exporterBinary,
    });
  }

  async saveSettings() {
    await this.saveData({
      exporterRoot: this.settings.exporterRoot,
      exporterBinary: this.settings.exporterBinary,
      zedCli: this.settings.zedCli,
    });
    this.resetBridgeClient();
  }

  activeMarkdownNote() {
    const file = this.app.workspace.getActiveFile();
    if (!(file instanceof TFile) || file.extension.toLowerCase() !== "md") {
      new Notice("Откройте Markdown-заметку и повторите команду.");
      return null;
    }
    return file;
  }

  async launchReviewPlan(plan) {
    let targets;
    try {
      targets = validateReviewPlan(plan);
    } catch (error) {
      return {
        ok: false,
        diagnostics: [localDiagnostic(error.message, "review-plan")],
      };
    }
    const cliFailure = zedCliDiagnostic(this.settings.zedCli);
    if (cliFailure) {
      return { ok: false, diagnostics: [cliFailure] };
    }
    const diagnostics = [];
    for (const target of targets) {
      const diagnostic = await runZedTarget(
        this.settings.zedCli,
        plan.baselineState,
        target,
      );
      if (diagnostic) diagnostics.push(diagnostic);
    }
    return { ok: diagnostics.length === 0, diagnostics };
  }

  showBlocked(result, title = "Подготовка публикации заблокирована") {
    const diagnostics = Array.isArray(result && result.diagnostics)
      ? result.diagnostics
      : [localDiagnostic("Не удалось выполнить команду exporter-а.")];
    new DiagnosticsModal(this.app, title, diagnostics).open();
  }

  showBridgeError(error) {
    const diagnostic = error && error.diagnostic
      ? error.diagnostic
      : localDiagnostic("Не удалось выполнить команду exporter-а.");
    new DiagnosticsModal(this.app, "Ошибка локального exporter-а", [diagnostic]).open();
  }

  async prepareCurrentNote() {
    const file = this.activeMarkdownNote();
    if (!file) return;
    const running = new Notice("Подготовка английского черновика…", 0);
    try {
      const result = await this.bridgeClient.run("prepare", file.path);
      if (!result.ok) {
        this.showBlocked(result);
        return;
      }
      new Notice("Английский черновик готов к проверке.");
      new ReviewReadyModal(
        this.app,
        () => this.inspectAndOpenReview(file.path),
      ).open();
    } catch (error) {
      this.showBridgeError(error);
    } finally {
      running.hide();
    }
  }

  async inspectAndOpenReview(notePath) {
    const running = new Notice("Проверка внешнего перевода…", 0);
    try {
      const result = await this.bridgeClient.run(
        "inspect-publication",
        notePath,
      );
      if (!result.ok) {
        this.showBlocked(result, "Перевод пока нельзя открыть");
        return false;
      }
      const launched = await this.launchReviewPlan(result.reviewPlan);
      if (!launched.ok) {
        this.showBlocked(
          { diagnostics: launched.diagnostics },
          "Проверка в Zed открыта не полностью",
        );
        return false;
      }
      new Notice("Проверка перевода открыта в двух окнах Zed.");
      return true;
    } catch (error) {
      this.showBridgeError(error);
      return false;
    } finally {
      running.hide();
    }
  }

  async openCurrentReview() {
    const file = this.activeMarkdownNote();
    if (!file) return;
    await this.inspectAndOpenReview(file.path);
  }

  async markCurrentReviewed() {
    const file = this.activeMarkdownNote();
    if (!file) return;
    const running = new Notice("Проверка English-файла…", 0);
    try {
      const result = await this.bridgeClient.run("mark-reviewed", file.path);
      if (!result.ok) {
        this.showBlocked(result, "Перевод не отмечен как проверенный");
        return;
      }
      new Notice("Перевод проверен; одобренная версия сохранена.");
    } catch (error) {
      this.showBridgeError(error);
    } finally {
      running.hide();
    }
  }

  async refreshPublicationQueue() {
    const running = new Notice("Обновление очереди публикации…", 0);
    try {
      const result = await this.bridgeClient.run("refresh-publication-queue");
      if (!result.ok) {
        this.showBlocked(result, "Очередь обновлена не полностью");
        return;
      }
      const summary = result.summary || {};
      new Notice(
        "Очередь обновлена: " +
        `метаданные заблокированы: ${summary.metadata_blocked || 0}; ` +
        `переводится: ${summary.translating || 0}; ` +
        `готово к проверке: ${summary.ready_for_review || 0}; ` +
        `готово к публикации: ${summary.ready_to_publish || 0}; ` +
        `ошибка перевода: ${summary.translation_failed || 0}; ` +
        `устарело: ${summary.stale || 0}; ` +
        `обновлено: ${result.updated || 0}; без изменений: ${result.unchanged || 0}.`,
      );
    } catch (error) {
      this.showBridgeError(error);
    } finally {
      running.hide();
    }
  }
};
