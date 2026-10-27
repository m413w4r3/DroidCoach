import { appendFile } from "node:fs/promises";
import { withFileLock } from "../../dist/file-lock.js";

const [lockPath, logPath, id] = process.argv.slice(2);

await withFileLock(lockPath, async () => {
  await appendFile(logPath, `start ${id}\n`);
  await new Promise((resolve) => setTimeout(resolve, 20));
  await appendFile(logPath, `end ${id}\n`);
}, { timeoutMs: 5_000, retryMs: 5 });
