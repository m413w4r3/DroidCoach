import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { DeviceRegistry } from "../src/device-registry.js";
import { killLockHolder, releaseLockHolder, startLockHolder } from "./support/lock-holder.js";

let directory: string | null = null;

afterEach(async () => {
  if (directory) await rm(directory, { recursive: true, force: true });
  directory = null;
});

describe("device registry locking", () => {
  it("does not lose a revocation during a concurrent pairing", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-device-registry-"));
    const path = join(directory, "devices.json");
    const first = new DeviceRegistry(path);
    const second = new DeviceRegistry(path);
    const revoked = await first.pair("revoked device", ["sync.status"]);
    const kept = await first.pair("kept device", ["sync.status"]);

    const [, created] = await Promise.all([
      first.revoke(revoked.device.device_id),
      second.pair("new device", ["sync.status"]),
    ]);

    const devices = await second.list();
    expect(devices.find((item) => item.device_id === revoked.device.device_id)?.revoked_at).toBeTruthy();
    expect(devices.find((item) => item.device_id === kept.device.device_id)?.revoked_at).toBeNull();
    expect(devices.find((item) => item.device_id === created.device.device_id)?.label).toBe("new device");
  });

  it("serializes a revocation and a pairing while another process holds the lock", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-device-registry-"));
    const path = join(directory, "devices.json");
    const registry = new DeviceRegistry(path, { timeoutMs: 3_000, retryMs: 10 });
    const revoked = await registry.pair("revoked device", ["sync.status"]);
    const holder = await startLockHolder(`${path}.lock.d`);
    try {
      const revocation = registry.revoke(revoked.device.device_id);
      const pairing = registry.pair("new device", ["sync.status"]);
      await new Promise((resolve) => setTimeout(resolve, 150));
      expect(holder.exitCode).toBeNull();

      await releaseLockHolder(holder);
      const [, created] = await Promise.all([revocation, pairing]);
      const devices = await registry.list();
      expect(devices.find((item) => item.device_id === revoked.device.device_id)?.revoked_at).toBeTruthy();
      expect(devices.find((item) => item.device_id === created.device.device_id)?.label).toBe("new device");
    } finally {
      await killLockHolder(holder).catch(() => {});
    }
  });

  it("revokes after taking over the lock of a killed process", async () => {
    directory = await mkdtemp(join(tmpdir(), "atlas-device-registry-"));
    const path = join(directory, "devices.json");
    const registry = new DeviceRegistry(path, { timeoutMs: 3_000, retryMs: 10 });
    const device = await registry.pair("stale-lock device", ["sync.status"]);
    const holder = await startLockHolder(`${path}.lock.d`);
    await killLockHolder(holder);

    const revoked = await registry.revoke(device.device.device_id);

    expect(revoked.revoked_at).toBeTruthy();
    expect((await registry.get(device.device.device_id))?.revoked_at).toBeTruthy();
  });
});
