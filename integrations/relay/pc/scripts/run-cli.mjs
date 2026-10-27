import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { assertTokenNotInArgs, inspectConfiguration, loadEnvironmentFile, runtimeEnvironment } from "./lib/config.mjs";

const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const [envPath, ...cliArgs] = process.argv.slice(2);
const allowedCommands = new Set(["start", "show-identity", "pair-device", "revoke-device", "help", "--help", "-h"]);

try {
  if (!envPath || cliArgs.length === 0 || !allowedCommands.has(cliArgs[0])) throw new Error("usage: run-cli.mjs ENV_FILE EXISTING_CLI_COMMAND [ARGS]");
  const loaded = await loadEnvironmentFile(envPath);
  const environment = runtimeEnvironment(loaded.values);
  assertTokenNotInArgs(environment, cliArgs);
  if (cliArgs[0] === "start") {
    const result = inspectConfiguration(environment);
    if (result.errors.length) throw new Error("configuration check failed; run check-config.mjs for details");
  }
  const child = spawn(process.execPath, [join(PACKAGE_ROOT, "dist", "main.js"), ...cliArgs], {
    cwd: PACKAGE_ROOT,
    env: environment,
    stdio: "inherit",
  });
  for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => child.kill(signal));
  child.once("error", () => {
    process.stderr.write("ATLAS relay connector process could not be started\n");
    process.exitCode = 1;
  });
  child.once("exit", (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exitCode = code ?? 1;
  });
} catch (error) {
  process.stderr.write(`ATLAS relay command failed: ${error instanceof Error ? error.message : "invalid command"}\n`);
  process.exitCode = 1;
}
