const path = require("node:path");
const os = require("node:os");
const { spawn: defaultSpawn } = require("node:child_process");

const COMMANDS = Object.freeze({
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
  const extraDirs = [...EXTRA_UNIX_BIN_DIRS, path.join(homeDir, ".local", "bin")];
  const segments = (sourceEnv.PATH || "").split(path.delimiter).filter(Boolean);
  const seen = new Set(segments);
  for (const dir of extraDirs) {
    if (!seen.has(dir)) {
      segments.push(dir);
      seen.add(dir);
    }
  }
  return { ...sourceEnv, PATH: segments.join(path.delimiter) };
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
    !path.posix.isAbsolute(notePath) &&
    path.posix.extname(notePath).toLowerCase() === ".md" &&
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
  homeDir = os.homedir(),
  platform = process.platform,
}) {
  if (!exporterRoot || !vaultPath || !exporterBinary) {
    throw new TypeError("exporterRoot, vaultPath and exporterBinary are required");
  }
  const childEnv = buildChildEnv(env, homeDir, platform);

  return {
    run(command, notePath = null) {
      const contract = COMMANDS[command];
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
      args.push("--review", path.join(exporterRoot, "review"));
      if (contract.jobs) {
        args.push("--jobs", path.join(exporterRoot, ".publication-jobs"));
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

module.exports = {
  BridgeClientError,
  createBridgeClient,
};
