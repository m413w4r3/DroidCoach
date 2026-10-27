import { createHash, randomBytes, randomUUID } from "node:crypto";
import { chmod, stat } from "node:fs/promises";
import { withFileLock, type FileLockOptions } from "./file-lock.js";
import { readPrivateJson, writeOneTimeTokenFile, writePrivateJson } from "./secure-files.js";
import { CAPABILITIES, type Capability } from "./protocol.js";

export interface DeviceRecord {
  device_id: string;
  label: string;
  token_sha256: string;
  capabilities: Capability[];
  created_at: string;
  revoked_at: string | null;
}

interface RegistryDocument {
  schema_version: 1;
  devices: DeviceRecord[];
}

export interface PairedDevice {
  device: DeviceRecord;
  token: string;
}

export interface PairingReceipt {
  device: DeviceRecord;
  tokenFile: string;
}

export class DeviceRegistry {
  constructor(
    readonly path: string,
    private readonly lockOptions: FileLockOptions = {},
  ) {}

  async list(): Promise<DeviceRecord[]> {
    const document = await this.load();
    return document.devices;
  }

  async get(deviceId: string): Promise<DeviceRecord | undefined> {
    return (await this.load()).devices.find((device) => device.device_id === deviceId);
  }

  async pair(label: string, capabilities: Capability[] = [...CAPABILITIES]): Promise<PairedDevice> {
    const paired = this.createPair(label, capabilities);
    await this.commitPair(paired.device);
    return paired;
  }

  /**
   * Delivers the one-time token to an exclusive 0600 file before authorizing
   * the device under the registry lock. A crash before the registry commit
   * may leave an orphan token file, but cannot leave an authorized device
   * whose token was not written. A failed commit compensates by removing the
   * file when it is still the file created by this operation.
   */
  async pairToTokenFile(
    label: string,
    tokenFile: string,
    capabilities: Capability[] = [...CAPABILITIES],
  ): Promise<PairingReceipt> {
    const paired = this.createPair(label, capabilities);
    const payload = `${JSON.stringify({
      device_id: paired.device.device_id,
      label: paired.device.label,
      capabilities: paired.device.capabilities,
      token: paired.token,
    }, null, 2)}\n`;
    const removeTokenFile = await writeOneTimeTokenFile(tokenFile, payload);
    try {
      await this.commitPair(paired.device);
      return { device: paired.device, tokenFile };
    } catch (error) {
      await removeTokenFile().catch(() => {});
      throw error;
    }
  }

  async revoke(deviceId: string): Promise<DeviceRecord> {
    return this.withLock(async () => {
      const document = await this.load();
      const device = document.devices.find((item) => item.device_id === deviceId);
      if (!device) throw new Error("device not found");
      if (!device.revoked_at) device.revoked_at = new Date().toISOString();
      await writePrivateJson(this.path, document);
      await this.assertPrivateMode();
      return device;
    });
  }

  async verifyToken(device: DeviceRecord, token: string): Promise<boolean> {
    const tokenHash = createHash("sha256").update(Buffer.from(token, "base64url")).digest();
    const expected = Buffer.from(device.token_sha256, "hex");
    if (expected.length !== tokenHash.length) return false;
    const equal = (await import("node:crypto")).timingSafeEqual(tokenHash, expected);
    return equal && !device.revoked_at;
  }

  async assertPrivateMode(): Promise<void> {
    try {
      const info = await stat(this.path);
      if ((info.mode & 0o077) !== 0) await chmod(this.path, 0o600);
    } catch {
      // The next read or write reports the useful filesystem error.
    }
  }

  /**
   * Serializes read-modify-write operations across connector and CLI processes.
   * A stale lock is only taken over when its recorded process is gone.
   */
  private async withLock<T>(action: () => Promise<T>): Promise<T> {
    return withFileLock(`${this.path}.lock.d`, action, {
      ...this.lockOptions,
      busyMessage: this.lockOptions.busyMessage ?? "device registry is busy",
    });
  }

  private createPair(label: string, capabilities: Capability[]): PairedDevice {
    if (label.length < 1 || label.length > 80 || /[\u0000-\u001f]/.test(label)) throw new Error("invalid device label");
    if (capabilities.length === 0 || new Set(capabilities).size !== capabilities.length || !capabilities.every((capability) => CAPABILITIES.includes(capability))) {
      throw new Error("invalid device capabilities");
    }
    const rawToken = randomBytes(32);
    return {
      token: rawToken.toString("base64url"),
      device: {
        device_id: randomUUID(),
        label,
        token_sha256: createHash("sha256").update(rawToken).digest("hex"),
        capabilities: [...capabilities],
        created_at: new Date().toISOString(),
        revoked_at: null,
      },
    };
  }

  private async commitPair(device: DeviceRecord): Promise<void> {
    await this.withLock(async () => {
      const document = await this.load();
      document.devices.push(device);
      await writePrivateJson(this.path, document);
      await this.assertPrivateMode();
    });
  }

  private async load(): Promise<RegistryDocument> {
    const document = await readPrivateJson<Partial<RegistryDocument>>(this.path);
    // Reads are side-effect free. The registry is created only by a locked
    // read-modify-write, so a concurrent list/get cannot overwrite the first
    // pairing with an empty document.
    if (document === null) return { schema_version: 1, devices: [] };
    if (document.schema_version !== 1 || !Array.isArray(document.devices)) throw new Error("invalid device registry");
    for (const entry of document.devices) {
      if (!isDeviceRecord(entry)) throw new Error("invalid device registry");
    }
    await this.assertPrivateMode();
    return document as RegistryDocument;
  }
}

function isDeviceRecord(value: unknown): value is DeviceRecord {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return typeof record.device_id === "string" && typeof record.label === "string" &&
    typeof record.token_sha256 === "string" && /^[0-9a-f]{64}$/.test(record.token_sha256) &&
    Array.isArray(record.capabilities) && record.capabilities.every((item) => CAPABILITIES.includes(item as Capability)) &&
    typeof record.created_at === "string" && (record.revoked_at === null || typeof record.revoked_at === "string");
}
