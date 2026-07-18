const path = require("node:path");

// Obsidian evaluates a plugin's main.js through a host-level require, not a
// module loader rooted at this plugin directory. Keep local dependencies in
// this entrypoint instead of adding a relative runtime require here.
const { createBridgeClient } = (() => {
  const nodePath = require("node:path");
  const { spawn: defaultSpawn } = require("node:child_process");

  const BRIDGE_COMMANDS = Object.freeze({
    prepare: { note: true, jobs: true },
    "inspect-publication": { note: true, jobs: false },
    "mark-reviewed": { note: true, jobs: true },
    "refresh-publication-queue": { note: false, jobs: true },
  });

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
      payload.schemaVersion === 1 &&
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
    uvExecutable = "uv",
  }) {
    if (!exporterRoot || !vaultPath || !uvExecutable) {
      throw new TypeError("exporterRoot, vaultPath and uvExecutable are required");
    }

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
          "run",
          "astro-export",
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
            child = spawn(uvExecutable, args, {
              cwd: exporterRoot,
              shell: false,
              windowsHide: true,
            });
          } catch (_error) {
            reject(
              new BridgeClientError(
                "spawn_failed",
                "Не удалось запустить exporter. Проверьте путь к uv и каталог exporter-а.",
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
                "Не удалось запустить exporter. Проверьте путь к uv и каталог exporter-а.",
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

const { shell } = require("electron");
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

function localDiagnostic(message) {
  return { field: "bridge", message, blocking: true };
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
      text: "Файлы ru.md и en.md находятся во внешнем каталоге exporter-а.",
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
      .setDesc("Локальный каталог tools/astro-export.")
      .addText((text) => text
        .setPlaceholder("/path/to/tools/astro-export")
        .setValue(this.plugin.settings.exporterRoot)
        .onChange(async (value) => {
          this.plugin.settings.exporterRoot = value.trim();
          await this.plugin.saveSettings();
        }));

    new Setting(containerEl)
      .setName("Команда uv")
      .setDesc("Имя команды или абсолютный путь к uv.")
      .addText((text) => text
        .setPlaceholder("uv")
        .setValue(this.plugin.settings.uvExecutable)
        .onChange(async (value) => {
          this.plugin.settings.uvExecutable = value.trim();
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
      uvExecutable: saved.uvExecutable || "uv",
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
      uvExecutable: this.settings.uvExecutable,
    });
  }

  async saveSettings() {
    await this.saveData({
      exporterRoot: this.settings.exporterRoot,
      uvExecutable: this.settings.uvExecutable,
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

  hasReviewTarget(result) {
    return Boolean(
      result &&
      typeof result.collection === "string" && result.collection &&
      typeof result.publicId === "string" && result.publicId &&
      typeof result.reviewDirectory === "string" && result.reviewDirectory,
    );
  }

  async openConfirmedReview(result) {
    if (!this.hasReviewTarget(result)) {
      this.showBlocked(
        { diagnostics: [localDiagnostic("Exporter не подтвердил каталог проверки перевода.")] },
        "Каталог проверки недоступен",
      );
      return false;
    }
    const openError = await shell.openPath(result.reviewDirectory);
    if (openError) {
      this.showBlocked(
        { diagnostics: [localDiagnostic("Операционная система не смогла открыть каталог проверки.")] },
        "Каталог проверки не открыт",
      );
      return false;
    }
    return true;
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
      if (!this.hasReviewTarget(result)) {
        this.showBlocked({ diagnostics: [localDiagnostic("Exporter не вернул каталог проверки.")] });
        return;
      }
      new Notice("Английский черновик готов к проверке.");
      new ReviewReadyModal(this.app, () => this.openConfirmedReview(result)).open();
    } catch (error) {
      this.showBridgeError(error);
    } finally {
      running.hide();
    }
  }

  async openCurrentReview() {
    const file = this.activeMarkdownNote();
    if (!file) return;
    const running = new Notice("Проверка внешнего перевода…", 0);
    try {
      const result = await this.bridgeClient.run("inspect-publication", file.path);
      if (!result.ok) {
        this.showBlocked(result, "Перевод пока нельзя открыть");
        return;
      }
      if (await this.openConfirmedReview(result)) {
        new Notice("Каталог проверки перевода открыт.");
      }
    } catch (error) {
      this.showBridgeError(error);
    } finally {
      running.hide();
    }
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
      new Notice("Перевод отмечен как проверенный.");
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
