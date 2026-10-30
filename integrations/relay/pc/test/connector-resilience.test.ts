import { chmod, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { configFromEnvironment, createConnector, readPrivateV4AndroidToken, type ConnectorConfig, type ConnectorRuntime } from "../src/connector.js";
import { loadOrCreateIdentity } from "../src/identity.js";
import { PROTOCOL_V2 } from "../src/protocol.js";
import { FakeSecureChannel, hello, request } from "./helpers.js";

const activeRuntimes: ConnectorRuntime[] = [];
const activeDirectories: string[] = [];

afterEach(async () => {
  for (const runtime of activeRuntimes.splice(0).reverse()) await runtime.stop();
  for (const directory of activeDirectories.splice(0)) await rm(directory, { recursive: true, force: true });
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("connector restart and configuration resilience", () => {
  it("keeps its identity and registry across restart and rejects a revoked device after restart", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-connector-restart-"));
    activeDirectories.push(stateDir);
    const config = makeConfig(stateDir);

    const firstRuntime = await createConnector(config);
    activeRuntimes.push(firstRuntime);
    const firstIdentity = await loadOrCreateIdentity(firstRuntime.identityPath);
    const paired = await firstRuntime.registry.pair("synthetic restart device", ["sync.status"]);
    const firstRegistry = await firstRuntime.registry.list();
    await firstRuntime.stop();
    activeRuntimes.splice(activeRuntimes.indexOf(firstRuntime), 1);

    const secondRuntime = await createConnector(config);
    activeRuntimes.push(secondRuntime);
    const secondIdentity = await loadOrCreateIdentity(secondRuntime.identityPath);
    expect(secondIdentity.identity.public_key).toBe(firstIdentity.identity.public_key);
    expect(secondIdentity.identity.fingerprint).toBe(firstIdentity.identity.fingerprint);
    expect(await secondRuntime.registry.list()).toEqual(firstRegistry);

    const liveChannel = new FakeSecureChannel();
    const livePeer = secondRuntime.sessions.create(liveChannel);
    await livePeer.receive(JSON.stringify(hello(paired.device.device_id, paired.token, { capabilities: ["sync.status"] })));
    expect(liveChannel.lastMessage()).toMatchObject({ kind: "hello_ok", capabilities: ["sync.status"] });
    livePeer.close();

    await secondRuntime.registry.revoke(paired.device.device_id);
    await secondRuntime.stop();
    activeRuntimes.splice(activeRuntimes.indexOf(secondRuntime), 1);

    const thirdRuntime = await createConnector(config);
    activeRuntimes.push(thirdRuntime);
    const revokedChannel = new FakeSecureChannel();
    await thirdRuntime.sessions.create(revokedChannel).receive(JSON.stringify(hello(paired.device.device_id, paired.token, { capabilities: ["sync.status"] })));
    expect(revokedChannel.lastMessage()).toMatchObject({ kind: "error", error: { code: "DEVICE_REVOKED" } });
    expect(revokedChannel.closeEvents[0]?.code).toBe(4001);
    expect(thirdRuntime.sessions.activeSessionCount()).toBe(0);
  });

  it("fails startup on a partially written registry without replacing it or creating an identity", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-registry-corrupt-"));
    activeDirectories.push(stateDir);
    const registryPath = join(stateDir, "devices.json");
    const partialRegistry = "{\"schema_version\":1,\"devices\":[";
    await writeFile(registryPath, partialRegistry, "utf8");

    await expect(createConnector(makeConfig(stateDir))).rejects.toThrow();
    expect(await readFile(registryPath, "utf8")).toBe(partialRegistry);
    await expect(stat(join(stateDir, "identity.json"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("fails startup when devices.json cannot be read and preserves that path", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-registry-unreadable-"));
    activeDirectories.push(stateDir);
    const registryPath = join(stateDir, "devices.json");
    await mkdir(registryPath);

    await expect(createConnector(makeConfig(stateDir))).rejects.toThrow();
    expect((await stat(registryPath)).isDirectory()).toBe(true);
    await expect(stat(join(stateDir, "identity.json"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("uses the exact environment opt-in and defaults loopback cleartext to denied", () => {
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "");
    expect(configFromEnvironment().allowLoopbackWs).toBe(false);
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "true");
    expect(configFromEnvironment().allowLoopbackWs).toBe(false);
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "1");
    expect(configFromEnvironment().allowLoopbackWs).toBe(true);
  });

  it("enables V4 only for the exact environment opt-in", () => {
    vi.stubEnv("ATLAS_V4_ENABLED", "");
    vi.stubEnv("ATLAS_V4_ANDROID_TOKEN_FILE", "");
    expect(configFromEnvironment()).toMatchObject({ v4Enabled: false });
    vi.stubEnv("ATLAS_V4_ENABLED", "true");
    expect(configFromEnvironment()).toMatchObject({ v4Enabled: false });
    vi.stubEnv("ATLAS_V4_ENABLED", "1");
    vi.stubEnv("ATLAS_V4_ANDROID_TOKEN_FILE", "/tmp/atlas-v4-android-token");
    expect(configFromEnvironment()).toMatchObject({
      v4Enabled: true,
      v4AndroidTokenFile: "/tmp/atlas-v4-android-token",
    });
  });

  it("accepts only an owner-controlled mode-0600 Android token file", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-v4-token-file-"));
    activeDirectories.push(stateDir);
    const tokenFile = join(stateDir, "android-token");
    const token = "synthetic-core-android-token";
    await writeFile(tokenFile, `${token}\n`, { mode: 0o600 });
    await chmod(tokenFile, 0o600);

    expect(await readPrivateV4AndroidToken(tokenFile)).toBe(token);

    await chmod(tokenFile, 0o644);
    expect(await readPrivateV4AndroidToken(tokenFile)).toBeUndefined();

    await chmod(tokenFile, 0o600);
    await rm(tokenFile);
    expect(await readPrivateV4AndroidToken(tokenFile)).toBeUndefined();
    expect(await readPrivateV4AndroidToken(undefined)).toBeUndefined();
  });

  it("sends V4 from an authenticated paired session using the dedicated Android bearer", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-v4-connector-session-"));
    activeDirectories.push(stateDir);
    const tokenFile = join(stateDir, "android-token");
    const androidToken = "synthetic-android-bearer-session-test";
    await writeFile(tokenFile, androidToken, { mode: 0o600 });
    await chmod(tokenFile, 0o600);

    const calls: Array<{ url: string; authorization: string | null; contract: string | null; capability: string | null; body: string }> = [];
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      calls.push({
        url: String(input),
        authorization: headers.get("authorization"),
        contract: headers.get("x-atlas-contract-version"),
        capability: headers.get("x-atlas-capability"),
        body: typeof init?.body === "string" ? init.body : "",
      });
      return new Response(JSON.stringify({ reply: "synthetic answer" }), { status: 200, headers: { "content-type": "application/json" } });
    });

    const runtime = await createConnector({
      ...makeConfig(stateDir),
      serviceToken: "synthetic-service-bearer",
      v4Enabled: true,
      v4AndroidTokenFile: tokenFile,
    });
    activeRuntimes.push(runtime);
    const paired = await runtime.registry.pair("synthetic V4 device", ["v4.coach.chat"]);
    const channel = new FakeSecureChannel();
    const peer = runtime.sessions.create(channel);
    await peer.receive(JSON.stringify(hello(paired.device.device_id, paired.token, {
      protocol: PROTOCOL_V2,
      capabilities: ["v4.coach.chat"],
    })));
    const sessionId = String(channel.lastMessage().session_id);
    const requestId = "00000000-0000-4000-8000-000000000111";
    const operationRequest = request(sessionId, "v4.coach.chat.request", {
      contract_version: 4,
      schema_version: 4,
      request_id: requestId,
      capability: "coach.chat",
      sent_at: "2026-10-29T08:02:04Z",
      conversation_id: "synthetic-connector-session",
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
      expect(channel.lastMessage()).toMatchObject({ kind: "response", operation: "v4.coach.chat.request" });
      expect(calls).toHaveLength(1);
      expect(calls[0]).toMatchObject({
        url: "http://127.0.0.1:8100/v4/agent/chat",
        authorization: `Bearer ${androidToken}`,
        contract: "4",
        capability: "coach.chat",
      });
      expect(calls[0]?.authorization).not.toContain("synthetic-service-bearer");
      expect(calls[0]?.body).not.toContain(paired.device.device_id);
    } finally {
      peer.close();
    }
  });
});

function makeConfig(stateDir: string): ConnectorConfig {
  return {
    stateDir,
    relayUrl: "wss://relay.example.org",
    allowLoopbackWs: false,
    backendUrl: "http://127.0.0.1:8100",
    metricsPort: 0,
  };
}
