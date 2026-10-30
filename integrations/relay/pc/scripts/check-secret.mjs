import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import { isAbsolute } from "node:path";
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
  if (environment.ATLAS_V4_ENABLED === "1") {
    const privateTokenFile = await isPrivateAndroidTokenFile(environment.ATLAS_V4_ANDROID_TOKEN_FILE);
    process.stdout.write(`ATLAS_V4_ANDROID_TOKEN_FILE: ${privateTokenFile ? "private file present" : "unavailable or permissions invalid"}\n`);
    if (!privateTokenFile) process.exitCode = 1;
  }
} catch {
  process.stderr.write("ATLAS_SERVICE_TOKEN: status unavailable; environment file is invalid or not private\n");
  process.exitCode = 1;
}

async function isPrivateAndroidTokenFile(path) {
  if (!path || !isAbsolute(path) || typeof process.getuid !== "function" || constants.O_NOFOLLOW === undefined) return false;
  let handle;
  try {
    const beforeOpen = await lstat(path);
    if (!beforeOpen.isFile() || beforeOpen.isSymbolicLink()) return false;
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const file = await handle.stat();
    const owner = process.getuid();
    return file.isFile() && file.uid === owner && beforeOpen.uid === owner &&
      (file.mode & 0o777) === 0o600 && beforeOpen.dev === file.dev && beforeOpen.ino === file.ino &&
      file.size >= 1 && file.size <= 4098;
  } catch {
    return false;
  } finally {
    await handle?.close().catch(() => {});
  }
}
