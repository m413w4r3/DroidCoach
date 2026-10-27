import { fork } from "node:child_process";
import { once } from "node:events";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

const WORKER = join(dirname(fileURLToPath(import.meta.url)), "fixtures", "lock-worker.mjs");

let directory: string | null = null;

afterEach(async () => {
  if (directory) await rm(directory, { recursive: true, force: true });
  directory = null;
});

describe("ticket lock recovery stress", () => {
  it("serializes concurrent recoverers after a dead owner", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-lock-stress-"));
    for (let round = 0; round < 10; round += 1) {
      const lockPath = join(directory, `lock-${round}.d`);
      const logPath = join(directory, `log-${round}.txt`);
      await mkdir(lockPath, { recursive: true, mode: 0o700 });
      await writeFile(logPath, "");
      await writeFile(
        join(lockPath, `dead-${round}.json`),
        `${JSON.stringify({
          pid: 999_999,
          pid_start: "dead",
          nonce: `dead-owner-${round}`,
          ticket: 1,
          choosing: false,
          created_at: new Date(0).toISOString(),
        })}\n`,
      );

      const workers = Array.from({ length: 6 }, (_, index) => {
        const child = fork(WORKER, [lockPath, logPath, String(index)], {
          stdio: ["ignore", "ignore", "inherit", "ipc"],
          execArgv: [],
        });
        return once(child, "exit");
      });
      const exits = await Promise.all(workers);
      expect(exits.every(([code]) => code === 0)).toBe(true);

      const lines = (await readFile(logPath, "utf8")).trim().split("\n").filter(Boolean);
      expect(lines).toHaveLength(12);
      let active = 0;
      for (const line of lines) {
        active += line.startsWith("start") ? 1 : -1;
        expect(active).toBeLessThanOrEqual(1);
      }
      expect(active).toBe(0);
    }
  }, 30_000);
});
