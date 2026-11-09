import { chmod, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { assertBackendHttpUrl } from "../src/backend.js";
import { configFromEnvironment, createConnector, principalMapCoversActiveDevices, readPrivateServiceToken, validateConnectorEndpoints, type ConnectorRuntime } from "../src/connector.js";
import { DeviceRegistry, type DeviceRecord } from "../src/device-registry.js";
import { PROTOCOL_V2 } from "../src/protocol.js";
import { parseRelayUrl } from "../src/relay-link.js";
import { FakeSecureChannel, hello, request } from "./helpers.js";

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const temporaryDirectories: string[] = [];
const activeRuntimes: ConnectorRuntime[] = [];

beforeEach(() => {
  vi.stubEnv("ATLAS_DOCKER_NETWORK", "");
  vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "");
  vi.stubEnv("ATLAS_RELAY_URL", "ws://127.0.0.1:4400");
  vi.stubEnv("ATLAS_BACKEND_URL", "http://127.0.0.1:8100");
  vi.stubEnv("ATLAS_SERVICE_TOKEN", undefined);
  vi.stubEnv("ATLAS_SERVICE_TOKEN_FILE", undefined);
});

afterEach(async () => {
  for (const runtime of activeRuntimes.splice(0).reverse()) await runtime.stop();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  await Promise.all(temporaryDirectories.splice(0).map((directory) =>
    rm(directory, { recursive: true, force: true })));
});

describe("private Docker connector endpoints", () => {
  it("accepts only the exact Relay and Core service URLs when Docker mode is enabled", () => {
    vi.stubEnv("ATLAS_DOCKER_NETWORK", "1");
    vi.stubEnv("ATLAS_RELAY_URL", "WS://ATLAS-RELAY:4000/ws");
    vi.stubEnv("ATLAS_BACKEND_URL", "HTTP://ATLAS-CORE:8100");

    const config = configFromEnvironment();
    expect(config.dockerNetwork).toBe(true);
    expect(config.allowLoopbackWs).toBe(true);
    validateConnectorEndpoints(config);
    expect(parseRelayUrl(config.relayUrl, { dockerNetwork: true }).href).toBe("ws://atlas-relay:4000/ws");
    expect(assertBackendHttpUrl(config.backendUrl, { dockerNetwork: true }).href).toBe("http://atlas-core:8100/");
  });

  it("keeps the loopback WebSocket opt-in and backend policy when Docker mode is absent", () => {
    const denied = configFromEnvironment();
    expect(denied.allowLoopbackWs).toBe(false);
    expect(() => validateConnectorEndpoints(denied)).toThrow(/explicitly enabled loopback relay/);
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "1");
    const config = configFromEnvironment();
    expect(config.dockerNetwork).toBe(false);
    expect(config.allowLoopbackWs).toBe(true);
    expect(config.relayUrl).toBe("ws://127.0.0.1:4400");
    expect(config.backendUrl).toBe("http://127.0.0.1:8100");
    validateConnectorEndpoints(config);
    expect(() => assertBackendHttpUrl("http://evil.example:8100")).toThrow(/loopback HTTP URL/);
  });

  it("rejects Docker Relay URLs without the mode or with a different destination", () => {
    expect(() => parseRelayUrl("ws://atlas-relay:4000", { allowLoopbackWs: true })).toThrow();
    for (const value of [
      "wss://atlas-relay:4000",
      "ws://evil.example:4000",
      "ws://atlas-relay:4001",
      "ws://atlas-relay:4000/other",
      "ws://atlas-relay:4000?",
      "ws://atlas-relay:4000#fragment",
      "ws://user@atlas-relay:4000",
      "ws://atlas-relay:4000/path/../ws",
      "ws://[::1]:4000",
      "ws://atlas-relay:4000/evil?route=elsewhere",
    ]) {
      expect(() => parseRelayUrl(value, { dockerNetwork: true }), value).toThrow();
    }
    expect(() => validateConnectorEndpoints({
      relayUrl: "ws://evil.example:4000", backendUrl: "http://atlas-core:8100", allowLoopbackWs: false, dockerNetwork: true,
    })).toThrow();
  });

  it("rejects arbitrary backend URLs in Docker mode", () => {
    for (const value of [
      "http://evil.example:8100",
      "http://atlas-core:8101",
      "https://atlas-core:8100",
      "http://user@atlas-core:8100",
      "http://atlas-core:8100/",
      "http://atlas-core:8100/v4",
      "http://atlas-core:8100?token=hidden",
      "http://atlas-core:8100#fragment",
      "http://[::1]:8100",
      "http://atlas-core:8100:443",
    ]) {
      expect(() => assertBackendHttpUrl(value, { dockerNetwork: true }), value).toThrow();
    }
    expect(() => validateConnectorEndpoints({
      relayUrl: "ws://atlas-relay:4000", backendUrl: "http://evil.example:8100", allowLoopbackWs: false, dockerNetwork: true,
    })).toThrow();
    expect(() => validateConnectorEndpoints({
      relayUrl: "ws://127.0.0.1:4400", backendUrl: "http://atlas-core:8100", allowLoopbackWs: true, dockerNetwork: false,
    })).toThrow();
  });

  it("refuses simultaneous service token sources before opening state", async () => {
    vi.stubEnv("ATLAS_SERVICE_TOKEN", "synthetic-env-token");
    vi.stubEnv("ATLAS_SERVICE_TOKEN_FILE", "/tmp/synthetic-token-file");
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "1");
    const config = configFromEnvironment();
    await expect(createConnector(config)).rejects.toThrow(/exactly one service token source/);
  });
});

describe("ATLAS_SERVICE_TOKEN_FILE", () => {
  it("loads a private owner-controlled token without a final newline", async () => {
    const directory = await makeTemporaryDirectory();
    const path = join(directory, "service-token");
    await writePrivateFile(path, "synthetic-service-token");
    await expect(readPrivateServiceToken(path)).resolves.toBe("synthetic-service-token");
    await expect(readPrivateServiceToken("relative/service-token")).rejects.toThrow(/unavailable or invalid/);

    const owner = process.getuid();
    const ownerSpy = vi.spyOn(process, "getuid").mockReturnValue(owner + 1);
    try {
      await expect(readPrivateServiceToken(path)).rejects.toThrow(/unavailable or invalid/);
    } finally {
      ownerSpy.mockRestore();
    }
  });

  it("rejects a symlink, mode 0644, a trailing newline, and oversized content", async () => {
    const directory = await makeTemporaryDirectory();
    const target = join(directory, "target-token");
    const linked = join(directory, "linked-token");
    await writePrivateFile(target, "synthetic-file-token");
    await symlink(target, linked);

    await expect(readPrivateServiceToken(linked)).rejects.toThrow(/unavailable or invalid/);
    await chmod(target, 0o644);
    await expect(readPrivateServiceToken(target)).rejects.toThrow(/unavailable or invalid/);
    await chmod(target, 0o600);
    await writeFile(target, "synthetic-file-token\n", { mode: 0o600 });
    await chmod(target, 0o600);
    await expect(readPrivateServiceToken(target)).rejects.toThrow(/unavailable or invalid/);
    await writeFile(target, "x".repeat(4_097), { mode: 0o600 });
    await chmod(target, 0o600);
    await expect(readPrivateServiceToken(target)).rejects.toThrow(/unavailable or invalid/);
  });

  it("requires the token file to be outside the checkout", async () => {
    const directory = await mkdtemp(join(PACKAGE_ROOT, ".service-token-test-"));
    temporaryDirectories.push(directory);
    const path = join(directory, "service-token");
    await writePrivateFile(path, "synthetic-checkout-token");
    const error = await readPrivateServiceToken(path).then(() => null, (reason: Error) => reason);
    expect(error).toBeInstanceOf(Error);
    expect(error?.message).toBe("ATLAS_SERVICE_TOKEN_FILE is unavailable or invalid");
    expect(error?.message).not.toContain("synthetic-checkout-token");
  });
});

describe("principal map active-device coverage", () => {
  it("requires exactly the active device set and ignores revoked devices only when absent from the map", () => {
    const active = makeDeviceRecord("relay-active");
    const revoked = makeDeviceRecord("relay-revoked", "2026-11-01T00:00:00.000Z");
    const credential = { principalId: "synthetic-principal", credential: "synthetic-credential" };

    expect(principalMapCoversActiveDevices(new Map([[active.device_id, credential]]), [active])).toBe(true);
    expect(principalMapCoversActiveDevices(new Map(), [active])).toBe(false);
    expect(principalMapCoversActiveDevices(new Map([
      [active.device_id, credential], ["relay-extra", credential],
    ]), [active])).toBe(false);
    expect(principalMapCoversActiveDevices(new Map([
      [revoked.device_id, credential],
    ]), [revoked])).toBe(false);
    expect(principalMapCoversActiveDevices(new Map(), [revoked])).toBe(true);
    expect(principalMapCoversActiveDevices(new Map(), [])).toBe(true);
  });

  it("keeps V4 active when the valid map covers the active registry", async () => {
    const { stateDir, paired, mapPath, credentialPath } = await preparePrincipalMap();
    await writePrivateFile(credentialPath, "synthetic-principal-credential\n");
    await writePrincipalMap(mapPath, {
      [paired.device.device_id]: { principal_id: "synthetic-principal", credential_file: credentialPath },
    });
    const calls = await exerciseV4Chat(stateDir, paired.device.device_id, paired.token, mapPath);
    expect(calls).toMatchObject([{
      url: "http://127.0.0.1:8100/v4/agent/chat",
      authorization: "Bearer synthetic-principal-credential",
      contract: "4",
      capability: "coach.chat",
    }]);
  });

  it("disables V4 before loading map credentials or assertion when an active device is missing", async () => {
    const { stateDir, paired, mapPath, credentialPath, legacyTokenPath } = await preparePrincipalMap();
    await writePrivateFile(credentialPath, "synthetic-principal-credential\n");
    await writePrivateFile(legacyTokenPath, "synthetic-legacy-bearer\n");
    await writePrincipalMap(mapPath, {
      "relay-device-extra": { principal_id: "synthetic-extra-principal", credential_file: credentialPath },
    });
    const calls = await exerciseV4Chat(stateDir, paired.device.device_id, paired.token, mapPath, {
      v4AndroidTokenFile: legacyTokenPath,
      verifyV2: true,
    });
    expect(calls).toMatchObject([{
      url: "http://127.0.0.1:8100/v2/agent/status",
      authorization: "Bearer synthetic-service-bearer",
      contract: null,
    }]);
  });

  it("keeps V4 disabled for a covered map whose credential file is invalid", async () => {
    const { stateDir, paired, mapPath, credentialPath } = await preparePrincipalMap();
    await writeFile(credentialPath, "synthetic-invalid-mode-credential\n", { mode: 0o600 });
    await chmod(credentialPath, 0o644);
    await writePrincipalMap(mapPath, {
      [paired.device.device_id]: { principal_id: "synthetic-principal", credential_file: credentialPath },
    });
    const calls = await exerciseV4Chat(stateDir, paired.device.device_id, paired.token, mapPath);
    expect(calls).toHaveLength(0);
  });
});

async function makeTemporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "atlas-service-token-test-"));
  temporaryDirectories.push(directory);
  return directory;
}

async function writePrivateFile(path: string, content: string): Promise<void> {
  await writeFile(path, content, { mode: 0o600 });
  await chmod(path, 0o600);
}

function makeDeviceRecord(device_id: string, revoked_at: string | null = null): DeviceRecord {
  return {
    device_id,
    label: "synthetic device",
    token_sha256: "0".repeat(64),
    capabilities: [],
    created_at: "2026-11-01T00:00:00.000Z",
    revoked_at,
  };
}

async function preparePrincipalMap(): Promise<{
  stateDir: string;
  paired: Awaited<ReturnType<DeviceRegistry["pair"]>>;
  mapPath: string;
  credentialPath: string;
  legacyTokenPath: string;
}> {
  const stateDir = await makeTemporaryDirectory();
  const registry = new DeviceRegistry(join(stateDir, "devices.json"));
  const paired = await registry.pair("synthetic principal-map device", ["v4.coach.chat", "coach.status"]);
  return {
    stateDir,
    paired,
    mapPath: join(stateDir, "principal-map.json"),
    credentialPath: join(stateDir, "principal-credential"),
    legacyTokenPath: join(stateDir, "legacy-token"),
  };
}

async function writePrincipalMap(path: string, value: Record<string, { principal_id: string; credential_file: string }>): Promise<void> {
  await writePrivateFile(path, JSON.stringify(value));
}

async function exerciseV4Chat(
  stateDir: string,
  deviceId: string,
  deviceToken: string,
  mapPath: string,
  overrides: { v4AndroidTokenFile?: string; verifyV2?: boolean } = {},
): Promise<Array<{ url: string; authorization: string | null; contract: string | null; capability: string | null }>> {
  vi.stubEnv("NODE_ENV", "test");
  const calls: Array<{ url: string; authorization: string | null; contract: string | null; capability: string | null }> = [];
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    calls.push({
      url: String(input),
      authorization: headers.get("authorization"),
      contract: headers.get("x-atlas-contract-version"),
      capability: headers.get("x-atlas-capability"),
    });
    return new Response(JSON.stringify({ reply: "synthetic answer" }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });
  const { verifyV2 = false, ...connectorOverrides } = overrides;
  const runtime = await createConnector({
    stateDir,
    relayUrl: "wss://relay.example.org",
    allowLoopbackWs: false,
    backendUrl: "http://127.0.0.1:8100",
    serviceToken: "synthetic-service-bearer",
    metricsPort: 0,
    v4Enabled: true,
    v4AuthMode: "principal",
    requirePrincipalBinding: true,
    v4PrincipalMapFile: mapPath,
    v4RelayAssertionKeyId: "synthetic-test-key",
    v4RelayAssertionSecret: Buffer.from("synthetic-test-relay-assertion-secret").toString("base64url"),
    ...connectorOverrides,
  });
  activeRuntimes.push(runtime);

  const channel = new FakeSecureChannel();
  const peer = runtime.sessions.create(channel);
  await peer.receive(JSON.stringify(hello(deviceId, deviceToken, {
    protocol: PROTOCOL_V2,
    capabilities: ["v4.coach.chat", "coach.status"],
  })));
  const sessionId = String(channel.lastMessage().session_id);
  const requestId = "00000000-0000-4000-8000-000000000112";
  const operationRequest = request(sessionId, "v4.coach.chat.request", {
    contract_version: 4,
    schema_version: 4,
    request_id: requestId,
    capability: "coach.chat",
    sent_at: "2026-11-01T00:00:00Z",
    conversation_id: "synthetic-principal-map-session",
    message: "Synthetic chat request",
    consent_version: 1,
    shared_categories: [],
  }, {
    protocol: PROTOCOL_V2,
    request_id: requestId,
    idempotency_key: requestId,
  });
  try {
    await peer.receive(JSON.stringify(operationRequest));
    if (verifyV2) {
      const v2RequestId = "00000000-0000-4000-8000-000000000113";
      await peer.receive(JSON.stringify(request(sessionId, "coach.status", {}, {
        protocol: PROTOCOL_V2,
        request_id: v2RequestId,
        idempotency_key: v2RequestId,
      })));
    }
    return calls;
  } finally {
    peer.close();
  }
}
