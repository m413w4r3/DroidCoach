import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { chmod, chown, mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { DeviceRegistry } from "../src/device-registry.js";
import { killLockHolder, releaseLockHolder, startLockHolder } from "./support/lock-holder.js";

const PACKAGE_ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const CLI = join(PACKAGE_ROOT, "dist", "main.js");
const PAIRING_WORKER = join(PACKAGE_ROOT, "test", "fixtures", "pairing-worker.mjs");

let directory: string | null = null;

afterEach(async () => {
  if (directory) await rm(directory, { recursive: true, force: true });
  directory = null;
});

describe("pair-device token delivery", () => {
  it("writes one private token file and never prints the token", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-success-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const tokenFile = join(directory, "token.json");

    const result = await runCli([
      "pair-device",
      "--label",
      "synthetic device",
      "--capabilities",
      "sync.status",
      "--token-file",
      tokenFile,
    ], stateDir);

    expect(result.code).toBe(0);
    const output = JSON.parse(result.stdout) as Record<string, unknown>;
    const fileText = await readFile(tokenFile, "utf8");
    const file = JSON.parse(fileText) as Record<string, unknown>;
    const token = typeof file.token === "string" ? file.token : "";
    const mode = (await stat(tokenFile)).mode & 0o777;

    expect(Object.keys(output).sort()).toEqual(["capabilities", "device_id", "label", "token_file"]);
    expect(output.device_id).toBe(file.device_id);
    expect(output.label).toBe("synthetic device");
    expect(output.capabilities).toEqual(["sync.status"]);
    expect(output.token_file).toBe(tokenFile);
    expect(result.stdout.includes(token)).toBe(false);
    expect(result.stderr.includes(token)).toBe(false);
    expect(mode).toBe(0o600);
    expect(typeof file.device_id === "string").toBe(true);
    expect(file.label).toBe("synthetic device");
    expect(file.capabilities).toEqual(["sync.status"]);
    expect(/^[A-Za-z0-9_-]{43}$/.test(token)).toBe(true);
    expect(fileText.split(token).length - 1).toBe(1);
    expect((await new DeviceRegistry(join(stateDir, "devices.json")).list()).map((device) => device.device_id))
      .toEqual([output.device_id]);
  });

  it("does not authorize a device when the token file already exists", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-existing-token-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const tokenFile = join(directory, "token.json");
    const existingContents = "preserve existing file\n";
    await writeFile(tokenFile, existingContents, { mode: 0o600 });

    const result = await runPair(stateDir, tokenFile);

    expect(result.code).not.toBe(0);
    expect(await readFile(tokenFile, "utf8")).toBe(existingContents);
    expect(await new DeviceRegistry(join(stateDir, "devices.json")).list()).toEqual([]);
  });

  it("does not authorize a device when its token directory is inaccessible", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-inaccessible-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const tokenFile = join(directory, "missing-directory", "token.json");

    const result = await runPair(stateDir, tokenFile);

    expect(result.code).not.toBe(0);
    expect(result.stdout).toBe("");
    expect(await new DeviceRegistry(join(stateDir, "devices.json")).list()).toEqual([]);
  });

  it("does not authorize a device when token creation is denied with EACCES", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-eacces-"));
    const stateDir = join(directory, "state");
    const deniedDir = join(directory, "denied");
    await mkdir(stateDir);
    await mkdir(deniedDir);
    const userId = typeof process.getuid === "function" && process.getuid() === 0 ? 65_534 : undefined;
    if (userId !== undefined) {
      await chmod(directory, 0o755);
      await chown(stateDir, userId, userId);
    }
    await chmod(deniedDir, 0o555);
    const result = await runPair(stateDir, join(deniedDir, "token.json"), userId);

    expect(result.code).not.toBe(0);
    expect(result.stdout).toBe("");
    expect(await new DeviceRegistry(join(stateDir, "devices.json")).list()).toEqual([]);
  });

  it("does not authorize a device when the requested token path is refused", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-refused-token-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const tokenFile = join(directory, "token-path-is-a-directory");
    await mkdir(tokenFile);

    const result = await runPair(stateDir, tokenFile);

    expect(result.code).not.toBe(0);
    expect(result.stdout).toBe("");
    expect((await stat(tokenFile)).isDirectory()).toBe(true);
    expect(await new DeviceRegistry(join(stateDir, "devices.json")).list()).toEqual([]);
  });

  it("compensates the token file when the registry cannot be read", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-unreadable-registry-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const registryPath = join(stateDir, "devices.json");
    await mkdir(registryPath);
    const sentinelPath = join(registryPath, "preserve.txt");
    await writeFile(sentinelPath, "preserve registry data\n");
    const tokenFile = join(directory, "token.json");

    const result = await runPair(stateDir, tokenFile);

    expect(result.code).not.toBe(0);
    expect(result.stdout).toBe("");
    expect((await stat(registryPath)).isDirectory()).toBe(true);
    await chmod(registryPath, 0o700);
    expect(await readFile(sentinelPath, "utf8")).toBe("preserve registry data\n");
    await expect(stat(tokenFile)).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("leaves only an orphan token file if the process dies before registry commit", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-restart-"));
    const registryPath = join(directory, "devices.json");
    const tokenFile = join(directory, "token.json");
    const attemptPath = join(directory, "attempt.txt");
    const registry = new DeviceRegistry(registryPath);
    const holder = await startLockHolder(`${registryPath}.lock.d`);
    let worker: ChildProcess | null = null;
    try {
      worker = spawn(process.execPath, [PAIRING_WORKER, registryPath, tokenFile, "crash fixture", attemptPath], {
        stdio: "ignore",
      });
      await waitForFile(tokenFile);
      await waitForFile(attemptPath);
      expect(await registry.list()).toEqual([]);

      const exited = once(worker, "exit");
      worker.kill("SIGKILL");
      await exited;

      expect(await registry.list()).toEqual([]);
      expect((await stat(tokenFile)).mode & 0o777).toBe(0o600);
      expect((await readFile(tokenFile, "utf8")).length > 0).toBe(true);
    } finally {
      if (worker && worker.exitCode === null && worker.signalCode === null) {
        const exited = once(worker, "exit");
        worker.kill("SIGKILL");
        await exited;
      }
      await releaseLockHolder(holder).catch(() => killLockHolder(holder));
    }
  }, 10_000);

  it("serializes a CLI pairing with a concurrent revocation", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-pairing-concurrent-"));
    const stateDir = join(directory, "state");
    await mkdir(stateDir);
    const registryPath = join(stateDir, "devices.json");
    const first = new DeviceRegistry(registryPath);
    const second = new DeviceRegistry(registryPath);
    const existing = await first.pair("device to revoke", ["sync.status"]);
    const tokenFile = join(directory, "new-token.json");

    const [, result] = await Promise.all([
      first.revoke(existing.device.device_id),
      second.pairToTokenFile("concurrent device", tokenFile, ["sync.status"]),
    ]);

    const devices = await first.list();
    expect(Boolean(devices.find((device) => device.device_id === existing.device.device_id)?.revoked_at)).toBe(true);
    expect(devices.find((device) => device.device_id === result.device.device_id)?.label).toBe("concurrent device");
  });
});

function runPair(
  stateDir: string,
  tokenFile: string,
  userId?: number,
): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return runCli(["pair-device", "--label", "synthetic device", "--token-file", tokenFile], stateDir, userId);
}

function runCli(
  args: string[],
  stateDir: string,
  userId?: number,
): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [CLI, ...args], {
      env: { ...process.env, ATLAS_RELAY_STATE_DIR: stateDir },
      stdio: ["ignore", "pipe", "pipe"],
      ...(userId === undefined ? {} : { uid: userId, gid: userId }),
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk: string) => { stdout += chunk; });
    child.stderr.setEncoding("utf8").on("data", (chunk: string) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => resolve({ code, stdout, stderr }));
  });
}

async function waitForFile(path: string, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      await stat(path);
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }
  throw new Error("pairing worker did not write its token file");
}
