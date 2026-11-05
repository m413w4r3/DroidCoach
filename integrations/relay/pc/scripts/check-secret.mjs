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
    const authMode = environment.ATLAS_V4_AUTH_MODE ?? "legacy";
    const principalBound = authMode !== "legacy" || environment.ATLAS_REQUIRE_PRINCIPAL_BINDING === "1" || Boolean(environment.ATLAS_CORE_PRINCIPAL_MAP_FILE);
    if (principalBound) {
      const mapValid = await isPrivatePrincipalMap(environment.ATLAS_CORE_PRINCIPAL_MAP_FILE);
      const assertionValid = await isPrivateAssertionSecret(environment);
      process.stdout.write(`ATLAS_CORE_PRINCIPAL_MAP_FILE: ${mapValid ? "private mapping and credentials present" : "unavailable or permissions invalid"}\n`);
      process.stdout.write(`ATLAS_V4_RELAY_ASSERTION_SECRET: ${assertionValid ? "private key configured" : "unavailable or permissions invalid"}\n`);
      if (!mapValid || !assertionValid) process.exitCode = 1;
    } else {
      const privateTokenFile = await isPrivateAndroidTokenFile(environment.ATLAS_V4_ANDROID_TOKEN_FILE);
      process.stdout.write(`ATLAS_V4_ANDROID_TOKEN_FILE: ${privateTokenFile ? "private file present" : "unavailable or permissions invalid"}\n`);
      if (!privateTokenFile) process.exitCode = 1;
    }
  }
} catch {
  process.stderr.write("ATLAS_SERVICE_TOKEN: status unavailable; environment file is invalid or not private\n");
  process.exitCode = 1;
}

async function isPrivateAndroidTokenFile(path) {
  const raw = await readPrivateFile(path, 4098);
  if (raw === undefined) return false;
  const token = raw.endsWith("\r\n") ? raw.slice(0, -2) : raw.endsWith("\n") ? raw.slice(0, -1) : raw;
  return token.length >= 1 && token.length <= 4096 && /^[\x21-\x7e]+$/.test(token);
}

async function isPrivatePrincipalMap(path) {
  const raw = await readPrivateFile(path, 262144);
  if (raw === undefined) return false;
  let map;
  try { map = JSON.parse(raw); } catch { return false; }
  if (!map || typeof map !== "object" || Array.isArray(map) || Object.keys(map).length < 1 || Object.keys(map).length > 4096) return false;
  for (const [relayDeviceId, item] of Object.entries(map)) {
    if (!relayDeviceId || relayDeviceId.length > 256 || !item || typeof item !== "object" || Array.isArray(item) ||
        Object.keys(item).sort().join(",") !== "credential_file,principal_id" ||
        typeof item.principal_id !== "string" || !item.principal_id ||
        typeof item.credential_file !== "string" || !isAbsolute(item.credential_file) ||
        !(await isPrivateAndroidTokenFile(item.credential_file))) return false;
  }
  return true;
}

async function isPrivateAssertionSecret(environment) {
  const keyId = environment.ATLAS_V4_RELAY_ASSERTION_KEY_ID;
  const secretFile = environment.ATLAS_V4_RELAY_ASSERTION_SECRET_FILE;
  const secretEnv = environment.ATLAS_V4_RELAY_ASSERTION_SECRET;
  if (typeof keyId !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(keyId) || Boolean(secretFile) === Boolean(secretEnv)) return false;
  const raw = secretFile ? await readPrivateFile(secretFile, 512) : secretEnv;
  const encoded = typeof raw === "string" && raw.endsWith("\n") ? raw.slice(0, -1) : raw;
  if (typeof encoded !== "string" || !/^[A-Za-z0-9_-]{43,}$/.test(encoded)) return false;
  return Buffer.from(encoded, "base64url").length >= 32;
}

async function readPrivateFile(path, maximumSize) {
  if (!path || !isAbsolute(path) || typeof process.getuid !== "function" || constants.O_NOFOLLOW === undefined) return undefined;
  let handle;
  try {
    const beforeOpen = await lstat(path);
    if (!beforeOpen.isFile() || beforeOpen.isSymbolicLink()) return undefined;
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const file = await handle.stat();
    const owner = process.getuid();
    if (!file.isFile() || file.uid !== owner || beforeOpen.uid !== owner || (file.mode & 0o777) !== 0o600 ||
        beforeOpen.dev !== file.dev || beforeOpen.ino !== file.ino || file.size < 1 || file.size > maximumSize) return undefined;
    const value = await handle.readFile("utf8");
    const afterRead = await lstat(path);
    if (afterRead.isSymbolicLink() || afterRead.dev !== file.dev || afterRead.ino !== file.ino) return undefined;
    return value;
  } catch {
    return undefined;
  } finally {
    await handle?.close().catch(() => {});
  }
}
