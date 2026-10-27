import { randomUUID } from "node:crypto";
import { chmod, lstat, mkdir, open, readFile, rename, rm, unlink } from "node:fs/promises";
import { dirname } from "node:path";

export async function ensurePrivateDirectory(directory: string): Promise<void> {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
}

export async function readPrivateJson<T>(path: string): Promise<T | null> {
  try {
    const handle = await open(path, "r");
    try {
      await handle.chmod(0o600);
      const raw = await handle.readFile("utf8");
      return JSON.parse(raw) as T;
    } finally {
      await handle.close();
    }
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

export async function writePrivateJson(path: string, value: unknown): Promise<void> {
  await ensurePrivateDirectory(dirname(path));
  const temporaryPath = `${path}.${randomUUID()}.tmp`;
  const handle = await open(temporaryPath, "wx", 0o600);
  let closed = false;
  try {
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
    await handle.chmod(0o600);
    await handle.close();
    closed = true;
  } catch (error) {
    if (!closed) await handle.close().catch(() => {});
    await rm(temporaryPath, { force: true }).catch(() => {});
    throw error;
  }
  try {
    await rename(temporaryPath, path);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    throw error;
  }
}

/**
 * Creates an exclusive one-time-token file and returns an ownership-checked
 * cleanup function for compensating a later registry commit failure.
 */
export async function writeOneTimeTokenFile(path: string, contents: string): Promise<() => Promise<void>> {
  const handle = await open(path, "wx", 0o600);
  let created: { dev: number; ino: number } | null = null;
  try {
    const info = await handle.stat();
    created = { dev: info.dev, ino: info.ino };
    await handle.writeFile(contents, "utf8");
    await handle.sync();
    await handle.chmod(0o600);
    await handle.close();
  } catch (error) {
    if (!created) {
      const info = await handle.stat().catch(() => null);
      if (info) created = { dev: info.dev, ino: info.ino };
    }
    await handle.close().catch(() => {});
    if (created) await unlinkIfSameFile(path, created).catch(() => {});
    throw error;
  }

  if (!created) throw new Error("could not identify one-time-token file");
  return () => unlinkIfSameFile(path, created);
}

async function unlinkIfSameFile(path: string, owned: { dev: number; ino: number }): Promise<void> {
  let current;
  try {
    current = await lstat(path);
  } catch (error) {
    if (isNotFound(error)) return;
    throw error;
  }
  if (current.dev === owned.dev && current.ino === owned.ino) await unlink(path);
}

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT";
}
