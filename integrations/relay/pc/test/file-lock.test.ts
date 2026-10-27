import { mkdtemp, readFile, rm, stat, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { withFileLock } from "../src/file-lock.js";
import { killLockHolder, releaseLockHolder, startLockHolder } from "./support/lock-holder.js";

let directory: string | null = null;

afterEach(async () => {
  if (directory) await rm(directory, { recursive: true, force: true });
  directory = null;
});

describe("cross-process file lock", () => {
  it("does not steal a stale lock from a live owner", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock");
    const holder = await startLockHolder(lockPath);
    try {
      let acquired = false;
      const started = Date.now();
      await expect(
        withFileLock(lockPath, async () => { acquired = true; }, {
          timeoutMs: 250,
          staleMs: 50,
          retryMs: 10,
        }),
      ).rejects.toThrow(/busy/);
      expect(acquired).toBe(false);
      expect(Date.now() - started).toBeGreaterThanOrEqual(200);
      expect(holder.exitCode).toBeNull();

      await releaseLockHolder(holder);
      await withFileLock(lockPath, async () => { acquired = true; }, { timeoutMs: 2_000, staleMs: 50, retryMs: 10 });
      expect(acquired).toBe(true);
    } finally {
      await killLockHolder(holder).catch(() => {});
    }
  });

  it("takes over a stale lock after the owner process dies", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock");
    const holder = await startLockHolder(lockPath);
    await killLockHolder(holder);

    let acquired = false;
    await withFileLock(lockPath, async () => { acquired = true; }, {
      timeoutMs: 2_000,
      staleMs: 50,
      retryMs: 10,
    });
    expect(acquired).toBe(true);
    await expect(stat(lockPath)).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("does not remove a lock whose ownership record changed", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock");
    const holder = await startLockHolder(lockPath);
    try {
      const record = JSON.parse(await readFile(lockPath, "utf8")) as Record<string, unknown>;
      await writeFile(lockPath, `${JSON.stringify({ ...record, nonce: "foreign-owner-nonce" })}\n`);
      await releaseLockHolder(holder);

      const remaining = JSON.parse(await readFile(lockPath, "utf8")) as { nonce: string };
      expect(remaining.nonce).toBe("foreign-owner-nonce");
    } finally {
      await killLockHolder(holder).catch(() => {});
      await unlink(lockPath).catch(() => {});
    }
  });
});
