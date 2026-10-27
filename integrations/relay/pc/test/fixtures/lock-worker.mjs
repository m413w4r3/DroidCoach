import { access, appendFile } from "node:fs/promises";
import { withFileLock } from "../../dist/file-lock.js";

const [lockPath, logPath, id, readyPath, barrierPath, holdMsArg] = process.argv.slice(2);
const holdMs = Number(holdMsArg ?? "20");

if (readyPath) await appendFile(readyPath, `ready ${id}\n`);
if (barrierPath) {
  for (;;) {
    try {
      await access(barrierPath);
      break;
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }
}

await withFileLock(lockPath, async () => {
  await appendFile(logPath, `start ${id}\n`);
  await new Promise((resolve) => setTimeout(resolve, holdMs));
  await appendFile(logPath, `end ${id}\n`);
}, { timeoutMs: 5_000, retryMs: 5 });
