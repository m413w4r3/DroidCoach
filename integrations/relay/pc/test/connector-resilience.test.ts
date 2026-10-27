import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { configFromEnvironment, createConnector, type ConnectorConfig, type ConnectorRuntime } from "../src/connector.js";
import { loadOrCreateIdentity } from "../src/identity.js";
import { FakeSecureChannel, hello } from "./helpers.js";

const activeRuntimes: ConnectorRuntime[] = [];
const activeDirectories: string[] = [];

afterEach(async () => {
  for (const runtime of activeRuntimes.splice(0).reverse()) await runtime.stop();
  for (const directory of activeDirectories.splice(0)) await rm(directory, { recursive: true, force: true });
  vi.unstubAllEnvs();
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
