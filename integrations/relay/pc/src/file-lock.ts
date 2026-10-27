import { createHash, randomUUID } from "node:crypto";
import { link, open, readFile, stat, unlink } from "node:fs/promises";

export interface LockRecord {
  pid: number;
  pid_start: string | null;
  nonce: string;
  created_at: string;
}

export interface FileLockOptions {
  timeoutMs?: number;
  staleMs?: number;
  retryMs?: number;
  busyMessage?: string;
  now?: () => number;
  isOwnerAlive?: (record: LockRecord) => boolean | Promise<boolean>;
}

const DEFAULT_TIMEOUT_MS = 5_000;
const DEFAULT_STALE_MS = 10_000;
const DEFAULT_RETRY_MS = 20;

/**
 * Cross-process exclusive lock.
 *
 * A lock file is published atomically with `link(2)` after its record is fully
 * written, so a waiter never observes a half-written owner record. A stale lock
 * is taken over only when its recorded process is demonstrably gone; a suspended
 * but live owner keeps ownership and waiters time out instead of stealing it.
 * Release removes the file only when its nonce is still the one this caller
 * published.
 */
export async function withFileLock<T>(
  lockPath: string,
  action: () => Promise<T>,
  options: FileLockOptions = {},
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const staleMs = options.staleMs ?? DEFAULT_STALE_MS;
  const retryMs = options.retryMs ?? DEFAULT_RETRY_MS;
  const busyMessage = options.busyMessage ?? "file lock is busy";
  const now = options.now ?? Date.now;
  if (!(timeoutMs > 0) || !(staleMs > 0) || !(retryMs > 0)) {
    throw new Error("invalid file lock timing");
  }

  const nonce = randomUUID();
  const owner: LockRecord = {
    pid: process.pid,
    pid_start: await processStartToken(process.pid),
    nonce,
    created_at: new Date(now()).toISOString(),
  };
  const candidatePath = `${lockPath}.${process.pid}.${nonce}.tmp`;
  const candidate = await open(candidatePath, "wx", 0o600);
  try {
    await candidate.writeFile(`${JSON.stringify(owner)}\n`);
  } finally {
    await candidate.close().catch(() => {});
  }

  const deadline = now() + timeoutMs;
  let acquired = false;
  try {
    while (!acquired) {
      try {
        await link(candidatePath, lockPath);
        acquired = true;
        await unlink(candidatePath).catch(() => {});
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
        const info = await stat(lockPath).catch(() => null);
        const record = parseLockRecord(await readFile(lockPath, "utf8").catch(() => null));
        const stale = info !== null && now() - info.mtimeMs > staleMs;
        if (stale && (record === null || !(await isOwnerAlive(record, options.isOwnerAlive)))) {
          // A corrupt/empty stale file can only be left by a process that died
          // while publishing, because publication is otherwise atomic.
          await unlink(lockPath).catch(() => {});
          continue;
        }
        if (now() >= deadline) throw new Error(busyMessage);
        await new Promise((resolve) => setTimeout(resolve, retryMs));
      }
    }
    return await action();
  } finally {
    await unlink(candidatePath).catch(() => {});
    if (acquired) {
      const current = parseLockRecord(await readFile(lockPath, "utf8").catch(() => null));
      if (current?.nonce === nonce) await unlink(lockPath).catch(() => {});
    }
  }
}

async function isOwnerAlive(
  record: LockRecord,
  override: FileLockOptions["isOwnerAlive"],
): Promise<boolean> {
  if (override) return override(record);
  if (record.pid_start !== null) {
    const current = await processStartToken(record.pid);
    if (current !== null && current !== record.pid_start) return false;
  }
  try {
    process.kill(record.pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM";
  }
}

async function processStartToken(pid: number): Promise<string | null> {
  // Linux exposes a stable start-time field; other platforms fall back to the
  // PID liveness check. The field is used to reject a recycled PID.
  try {
    const statText = await readFile(`/proc/${pid}/stat`, "utf8");
    const fields = statText.slice(statText.lastIndexOf(")") + 2).split(" ");
    const startTime = fields[19];
    return startTime === undefined ? null : createHash("sha256").update(startTime).digest("hex").slice(0, 16);
  } catch {
    return null;
  }
}

function parseLockRecord(text: string | null): LockRecord | null {
  if (text === null || text.trim() === "") return null;
  try {
    const value = JSON.parse(text) as Partial<LockRecord>;
    if (!Number.isInteger(value.pid) || (value.pid ?? 0) <= 0) return null;
    if (typeof value.nonce !== "string" || value.nonce.length < 8) return null;
    if (typeof value.created_at !== "string") return null;
    if (value.pid_start !== null && typeof value.pid_start !== "string") return null;
    return {
      pid: value.pid as number,
      pid_start: value.pid_start ?? null,
      nonce: value.nonce,
      created_at: value.created_at,
    };
  } catch {
    return null;
  }
}
