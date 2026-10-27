import { createHash, randomUUID } from "node:crypto";
import { link, mkdir, open, readFile, readdir, rename, unlink } from "node:fs/promises";
import { join } from "node:path";

export interface LockRecord {
  pid: number;
  pid_start: string | null;
  nonce: string;
  ticket: number;
  choosing: boolean;
  created_at: string;
}

export interface FileLockOptions {
  timeoutMs?: number;
  retryMs?: number;
  busyMessage?: string;
  now?: () => number;
  isOwnerAlive?: (record: LockRecord) => boolean | Promise<boolean>;
}

const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_RETRY_MS = 20;

/**
 * Cross-process bakery-style ticket lock.
 *
 * A contender first publishes a `choosing` record, reads the current maximum
 * ticket, publishes its immutable final record, then removes the choosing
 * record. It enters only when no live choosing record exists and no live final
 * record precedes its own (ticket, nonce) pair. Dead records are ignored and
 * can be removed safely because every filename contains a unique nonce, so no
 * process ever deletes a live contender's record or reuses another's name.
 */
export async function withFileLock<T>(
  lockPath: string,
  action: () => Promise<T>,
  options: FileLockOptions = {},
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const retryMs = options.retryMs ?? DEFAULT_RETRY_MS;
  const busyMessage = options.busyMessage ?? "file lock is busy";
  const now = options.now ?? Date.now;
  if (!(timeoutMs > 0) || !(retryMs > 0)) throw new Error("invalid file lock timing");

  await mkdir(lockPath, { recursive: true, mode: 0o700 });
  const nonce = randomUUID();
  const identity = {
    pid: process.pid,
    pid_start: await processStartToken(process.pid),
    nonce,
    created_at: new Date(now()).toISOString(),
  };
  const recordPath = join(lockPath, `${nonce}.json`);
  try {
    await publishNew(lockPath, recordPath, { ...identity, ticket: 0, choosing: true });
    const entries = await readEntries(lockPath);
    const ticket = entries.reduce(
      (maximum, entry) => Math.max(maximum, entry.record.choosing ? 0 : entry.record.ticket),
      0,
    ) + 1;
    const owner: LockRecord = { ...identity, ticket, choosing: false };
    await replace(lockPath, recordPath, owner);

    const deadline = now() + timeoutMs;
    while (true) {
      const contenders = await readEntries(lockPath);
      let blocked = false;
      for (const entry of contenders) {
        if (entry.record.nonce === nonce) continue;
        if (await isOwnerAlive(entry.record, options.isOwnerAlive)) {
          if (entry.record.choosing || precedes(entry.record, owner)) blocked = true;
        } else {
          await unlink(entry.path).catch(() => {});
        }
      }
      if (!blocked) return await action();
      if (now() >= deadline) throw new Error(busyMessage);
      await new Promise((resolve) => setTimeout(resolve, retryMs));
    }
  } finally {
    await unlink(recordPath).catch(() => {});
  }
}

interface LockEntry {
  path: string;
  record: LockRecord;
}

async function publishNew(lockPath: string, finalPath: string, record: LockRecord): Promise<void> {
  const candidatePath = await writeCandidate(lockPath, record);
  try {
    await link(candidatePath, finalPath);
  } finally {
    await unlink(candidatePath).catch(() => {});
  }
}

async function replace(lockPath: string, finalPath: string, record: LockRecord): Promise<void> {
  const candidatePath = await writeCandidate(lockPath, record);
  try {
    // Rename over the contender's own stable filename: readers observe either
    // the choosing record or the final record, never a missing file.
    await rename(candidatePath, finalPath);
  } catch (error) {
    await unlink(candidatePath).catch(() => {});
    throw error;
  }
}

async function writeCandidate(lockPath: string, record: LockRecord): Promise<string> {
  const candidatePath = join(lockPath, `.candidate-${record.nonce}-${randomUUID()}.tmp`);
  const candidate = await open(candidatePath, "wx", 0o600);
  try {
    await candidate.writeFile(`${JSON.stringify(record)}\n`);
  } finally {
    await candidate.close().catch(() => {});
  }
  return candidatePath;
}

async function readEntries(lockPath: string): Promise<LockEntry[]> {
  const names = await readdir(lockPath).catch(() => [] as string[]);
  const entries: LockEntry[] = [];
  for (const name of names) {
    if (!name.endsWith(".json") || name.startsWith(".")) continue;
    const path = join(lockPath, name);
    const record = parseLockRecord(await readFile(path, "utf8").catch(() => null));
    if (record !== null) entries.push({ path, record });
  }
  return entries;
}

function precedes(left: LockRecord, right: LockRecord): boolean {
  if (left.ticket !== right.ticket) return left.ticket < right.ticket;
  return left.nonce < right.nonce;
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
    if (typeof value.choosing !== "boolean") return null;
    if (!Number.isInteger(value.ticket) || (value.ticket ?? -1) < 0) return null;
    if (!value.choosing && (value.ticket ?? 0) <= 0) return null;
    if (typeof value.created_at !== "string") return null;
    if (value.pid_start !== null && typeof value.pid_start !== "string") return null;
    return {
      pid: value.pid as number,
      pid_start: value.pid_start ?? null,
      nonce: value.nonce,
      ticket: value.ticket as number,
      choosing: value.choosing,
      created_at: value.created_at,
    };
  } catch {
    return null;
  }
}
