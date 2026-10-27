import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { DeviceRegistry } from "../src/device-registry.js";

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
});
