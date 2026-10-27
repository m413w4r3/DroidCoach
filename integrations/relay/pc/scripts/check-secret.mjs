import { lstat } from "node:fs/promises";
import { loadEnvironmentFile, runtimeEnvironment, tokenPresent, modeString } from "./lib/config.mjs";

try {
  let environment = process.env;
  let mode = "not-applicable";
  const args = process.argv.slice(2);
  if (args.length) {
    if (args.length !== 2 || args[0] !== "--env-file") throw new Error("usage: check-secret.mjs [--env-file PATH]");
    const filePath = args[1];
    if (!filePath) throw new Error("environment file path is required");
    const loaded = await loadEnvironmentFile(filePath);
    environment = runtimeEnvironment(loaded.values);
    mode = modeString(loaded.fileMode);
    const info = await lstat(filePath);
    if ((info.mode & 0o077) !== 0) throw new Error("environment file permissions are too broad");
  }
  process.stdout.write(`ATLAS_SERVICE_TOKEN: ${tokenPresent(environment) ? "present" : "absent"}; source=${args.length ? "environment-file" : "process-environment"}; mode=${mode}\n`);
} catch {
  process.stderr.write("ATLAS_SERVICE_TOKEN: status unavailable; environment file is invalid or not private\n");
  process.exitCode = 1;
}
