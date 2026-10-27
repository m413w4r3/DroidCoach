import { mkdtemp, readdir, rm } from "node:fs/promises";
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

describe("cross-process ticket lock", () => {
  it("does not steal a live owner's ticket", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock.d");
    const holder = await startLockHolder(lockPath);
    try {
      let acquired = false;
      const started = Date.now();
      await expect(
        withFileLock(lockPath, async () => { acquired = true; }, {
          timeoutMs: 250,
          retryMs: 10,
        }),
      ).rejects.toThrow(/busy/);
      expect(acquired).toBe(false);
      expect(Date.now() - started).toBeGreaterThanOrEqual(200);
      expect(holder.exitCode).toBeNull();

      await releaseLockHolder(holder);
      await withFileLock(lockPath, async () => { acquired = true; }, { timeoutMs: 2_000, retryMs: 10 });
      expect(acquired).toBe(true);
    } finally {
      await killLockHolder(holder).catch(() => {});
    }
  });

  it("takes over after the owner process dies", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock.d");
    const holder = await startLockHolder(lockPath);
    await killLockHolder(holder);

    let acquired = false;
    await withFileLock(lockPath, async () => { acquired = true; }, {
      timeoutMs: 2_000,
      retryMs: 10,
    });
    expect(acquired).toBe(true);
    expect((await readdir(lockPath)).filter((name) => name.endsWith(".json"))).toEqual([]);
  });

  it("removes only its own record on release", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-file-lock-"));
    const lockPath = join(directory, "registry.lock.d");
    const holder = await startLockHolder(lockPath);
    try {
      expect((await readdir(lockPath)).filter((name) => name.endsWith(".json"))).toHaveLength(1);
      await expect(
        withFileLock(lockPath, async () => {}, { timeoutMs: 150, retryMs: 10 }),
      ).rejects.toThrow(/busy/);

      await releaseLockHolder(holder);
      expect((await readdir(lockPath)).filter((name) => name.endsWith(".json"))).toEqual([]);
    } finally {
      await killLockHolder(holder).catch(() => {});
    }
  });
});
