import { fork, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HOLDER = join(dirname(fileURLToPath(import.meta.url)), "..", "fixtures", "lock-holder.mjs");

export async function startLockHolder(lockPath: string, holdMs = 60_000): Promise<ChildProcess> {
  const child = fork(HOLDER, [lockPath, String(holdMs)], {
    stdio: ["ignore", "ignore", "inherit", "ipc"],
    execArgv: [],
  });
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("lock holder did not start")), 5_000);
    const onMessage = (message: unknown) => {
      if ((message as { type?: string } | null)?.type === "locked") {
        clearTimeout(timer);
        child.off("message", onMessage);
        resolve();
      }
    };
    child.on("message", onMessage);
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`lock holder exited early: ${code ?? "signal"}`));
    });
  });
  return child;
}

export async function releaseLockHolder(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null) return;
  child.send?.("release");
  await Promise.race([
    once(child, "exit"),
    new Promise((_, reject) => setTimeout(() => reject(new Error("lock holder did not exit")), 5_000)),
  ]);
}

export async function killLockHolder(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null) return;
  child.kill("SIGKILL");
  await once(child, "exit");
}
