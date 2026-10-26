import { randomUUID } from "node:crypto";
import { chmod, mkdir, open, readFile, rename, rm } from "node:fs/promises";
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
  try {
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
    await handle.chmod(0o600);
  } finally {
    await handle.close();
  }
  try {
    await rename(temporaryPath, path);
    await chmod(path, 0o600);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    throw error;
  }
}

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT";
}
