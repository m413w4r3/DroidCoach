import { createHash, randomUUID } from "node:crypto";
import { chmod, copyFile, lstat, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { isAbsolute, join, relative, resolve, sep } from "node:path";
import { loadEnvironmentFile, runtimeEnvironment } from "./lib/config.mjs";

const FILES = ["identity.json", "devices.json"];

export async function backupState(stateDir, backupDir) {
  if (!isAbsolute(stateDir)) throw new Error("state directory must be an absolute path");
  await requirePrivateDirectory(stateDir, "state directory");
  const target = resolve(backupDir);
  if (isWithin(resolve(stateDir), target)) throw new Error("backup destination must be outside the state directory");
  const existing = await lstat(target).catch(() => null);
  if (existing) throw new Error("backup destination already exists");
  await mkdir(target, { recursive: true, mode: 0o700 });
  await chmod(target, 0o700);
  const entries = {};
  try {
    for (const name of FILES) {
      const source = join(stateDir, name);
      const info = await lstat(source).catch((error) => error?.code === "ENOENT" ? null : Promise.reject(error));
      if (!info) {
        entries[name] = null;
        continue;
      }
      assertPrivateFile(info, name);
      const destination = join(target, name);
      await copyFile(source, destination);
      await chmod(destination, 0o600);
      entries[name] = createHash("sha256").update(await readFile(destination)).digest("hex");
    }
    if (!entries["identity.json"]) throw new Error("state identity is missing");
    const manifest = { schema_version: 1, files: entries };
    const manifestPath = join(target, "manifest.json");
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    await chmod(manifestPath, 0o600);
    return target;
  } catch (error) {
    await rm(target, { recursive: true, force: true });
    throw error;
  }
}

export async function restoreState({ stateDir, backupDir, expectedFingerprint, preRestoreBackup, serviceStopped }) {
  if (!serviceStopped) throw new Error("stop the connector and attest with --service-stopped before restore");
  if (!/^[0-9a-f]{64}$/.test(expectedFingerprint ?? "")) throw new Error("a 64-character confirmation fingerprint is required");
  if (!preRestoreBackup) throw new Error("a pre-restore backup destination is required");
  if (!isAbsolute(stateDir)) throw new Error("state directory must be an absolute path");
  const targetState = resolve(stateDir);
  if (isWithin(targetState, resolve(backupDir))) throw new Error("restore backup must be outside the state directory");
  await requirePrivateDirectory(targetState, "state directory");
  const source = await validateBackup(backupDir);
  const identity = JSON.parse(await readFile(join(source, "identity.json"), "utf8"));
  const fingerprint = identityFingerprint(identity);
  if (fingerprint !== expectedFingerprint) throw new Error("confirmation fingerprint does not match the selected backup identity");
  await backupState(targetState, preRestoreBackup);

  const manifest = JSON.parse(await readFile(join(source, "manifest.json"), "utf8"));
  const staged = new Map();
  try {
    for (const name of FILES) {
      if (manifest.files[name] === null) continue;
      const temporary = join(targetState, `.${name}.${randomUUID()}.restore`);
      await copyFile(join(source, name), temporary);
      await chmod(temporary, 0o600);
      staged.set(name, temporary);
    }
    for (const name of FILES) {
      const destination = join(targetState, name);
      if (staged.has(name)) await rename(staged.get(name), destination);
      else await rm(destination, { force: true });
      if (staged.has(name)) await chmod(destination, 0o600);
    }
  } catch (error) {
    for (const temporary of staged.values()) await rm(temporary, { force: true });
    throw error;
  }
}

async function validateBackup(backupDir) {
  const directory = resolve(backupDir);
  await requirePrivateDirectory(directory, "backup directory");
  const manifestPath = join(directory, "manifest.json");
  const manifestInfo = await lstat(manifestPath);
  assertPrivateFile(manifestInfo, "backup manifest");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  if (manifest.schema_version !== 1 || !manifest.files || !Object.hasOwn(manifest.files, "identity.json") || !Object.hasOwn(manifest.files, "devices.json")) {
    throw new Error("backup manifest is invalid");
  }
  for (const name of FILES) {
    const expected = manifest.files[name];
    if (expected === null) continue;
    if (typeof expected !== "string" || !/^[0-9a-f]{64}$/.test(expected)) throw new Error("backup manifest checksum is invalid");
    const info = await lstat(join(directory, name));
    assertPrivateFile(info, `backup ${name}`);
    const digest = createHash("sha256").update(await readFile(join(directory, name))).digest("hex");
    if (digest !== expected) throw new Error(`backup ${name} checksum mismatch`);
  }
  if (!manifest.files["identity.json"]) throw new Error("backup identity is missing");
  return directory;
}

async function requirePrivateDirectory(path, description) {
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink() || (info.mode & 0o777) !== 0o700) throw new Error(`${description} must be a non-symlink directory with mode 0700`);
  return info;
}

function assertPrivateFile(info, description) {
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o777) !== 0o600) throw new Error(`${description} must be a non-symlink regular file with mode 0600`);
}

function isWithin(parent, candidate) {
  const path = relative(parent, candidate);
  return path === "" || (path !== ".." && !path.startsWith(`..${sep}`));
}

function identityFingerprint(identity) {
  if (identity?.schema_version !== 1 || typeof identity.public_key !== "string" || typeof identity.fingerprint !== "string") throw new Error("backup identity is invalid");
  const publicKey = Buffer.from(identity.public_key, "base64");
  const fingerprint = createHash("sha256").update(publicKey).digest("hex");
  if (publicKey.byteLength !== 32 || fingerprint !== identity.fingerprint) throw new Error("backup identity fingerprint is invalid");
  return fingerprint;
}
