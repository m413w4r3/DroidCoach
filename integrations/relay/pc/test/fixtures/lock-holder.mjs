import { withFileLock } from "../../dist/file-lock.js";

const lockPath = process.argv[2];
const holdMs = Number(process.argv[3] ?? "60000");
let timer;
let release;

process.on("message", (message) => {
  if (message === "release") release?.();
});

await withFileLock(lockPath, async () => {
  process.send?.({ type: "locked", pid: process.pid });
  await new Promise((resolve) => {
    release = () => {
      clearTimeout(timer);
      resolve();
    };
    if (holdMs > 0) timer = setTimeout(release, holdMs);
  });
}, { timeoutMs: 5000 });

process.send?.({ type: "released", pid: process.pid });
process.disconnect?.();
