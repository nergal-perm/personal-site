const path = require("node:path");
const { spawn: defaultSpawn } = require("node:child_process");

const COMMANDS = Object.freeze({
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
        "run",
        "astro-export",
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

module.exports = {
  BridgeClientError,
  createBridgeClient,
};
