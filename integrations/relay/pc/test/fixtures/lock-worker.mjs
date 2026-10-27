import { access, appendFile } from "node:fs/promises";
import { withFileLock } from "../../dist/file-lock.js";

const [lockPath, logPath, id, readyPath, attemptPath, barrierPath, holdMsArg] = process.argv.slice(2);
const holdMs = Number(holdMsArg ?? "20");

if (readyPath) await appendFile(readyPath, `ready ${id}\n`);

await withFileLock(lockPath, async () => {
  await appendFile(logPath, `start ${id}\n`);
  await new Promise((resolve) => setTimeout(resolve, holdMs));
  await appendFile(logPath, `end ${id}\n`);
}, {
  timeoutMs: 5_000,
  retryMs: 5,
  onAttempt: async () => {
    if (attemptPath) await appendFile(attemptPath, `attempt ${id}\n`);
    if (!barrierPath) return;
    for (;;) {
      try {
        await access(barrierPath);
        return;
      } catch (error) {
        if (error?.code !== "ENOENT") throw error;
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
    }
  },
});
