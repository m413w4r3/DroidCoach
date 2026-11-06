import { chmod, lstat, mkdir, mkdtemp, readFile, rename, rm, stat, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { configFromEnvironment, createConnector, findRepositoryRoot, isOutsideRepositoryPath, readPrivateV4AndroidToken, readPrivateV4PrincipalMap, readPrivateV4RelayAssertion, sameDirectoryIdentity, selectV4CredentialMode, type ConnectorConfig, type ConnectorRuntime } from "../src/connector.js";
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
  it("uses principal maps only in dual/principal modes and keeps legacy fallback isolated", () => {
    expect(selectV4CredentialMode("legacy", false, "valid")).toBe("disabled");
    expect(selectV4CredentialMode("legacy", false, "absent")).toBe("legacy-fallback");
    expect(selectV4CredentialMode("legacy", true, "absent")).toBe("disabled");
    expect(selectV4CredentialMode("dual", false, "valid")).toBe("principal-map");
    expect(selectV4CredentialMode("dual", false, "absent")).toBe("disabled");
    expect(selectV4CredentialMode("principal", false, "valid")).toBe("principal-map");
    expect(selectV4CredentialMode("principal", false, "invalid")).toBe("disabled");
    expect(selectV4CredentialMode("unknown", false, "valid")).toBe("disabled");
  });

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

  it("accepts an assertion secret environment value only in tests and rejects dual sources", async () => {
    vi.stubEnv("NODE_ENV", "test");
    vi.stubEnv("ATLAS_V4_RELAY_ASSERTION_SECRET", Buffer.from("synthetic-assertion-secret-for-config-test").toString("base64url"));
    vi.stubEnv("ATLAS_V4_RELAY_ASSERTION_SECRET_FILE", undefined);
    expect(configFromEnvironment().v4RelayAssertionSecret).toBe(process.env.ATLAS_V4_RELAY_ASSERTION_SECRET);

    vi.stubEnv("NODE_ENV", "production");
    expect(() => configFromEnvironment()).toThrow("accepted only when NODE_ENV=test");

    vi.stubEnv("NODE_ENV", "test");
    vi.stubEnv("ATLAS_V4_RELAY_ASSERTION_SECRET_FILE", "/tmp/synthetic-assertion-secret-file");
    expect(() => configFromEnvironment()).toThrow("exactly one Relay assertion secret source");
    await expect(readPrivateV4RelayAssertion(
      "test-key", "/tmp/synthetic-assertion-secret-file", process.env.ATLAS_V4_RELAY_ASSERTION_SECRET,
    )).resolves.toBeUndefined();
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

  it("loads only owner-controlled principal maps and credentials without following symlinks", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-principal-map-"));
    activeDirectories.push(directory);
    const credentialPath = join(directory, "credential-a");
    const mapPath = join(directory, "principal-map.json");
    await writeFile(credentialPath, "synthetic-core-credential-a\n", { mode: 0o600 });
    await chmod(credentialPath, 0o600);
    await writeFile(mapPath, JSON.stringify({
      "relay-device-synthetic-a": { principal_id: "principal-a", credential_file: credentialPath },
    }), { mode: 0o600 });
    await chmod(mapPath, 0o600);

    const loaded = await readPrivateV4PrincipalMap(mapPath);
    expect(loaded.status).toBe("valid");
    if (loaded.status === "valid") {
      expect(loaded.credentials.get("relay-device-synthetic-a")).toEqual({
        principalId: "principal-a", credential: "synthetic-core-credential-a",
      });
    }

    await chmod(mapPath, 0o640);
    expect((await readPrivateV4PrincipalMap(mapPath)).status).toBe("invalid");
    await chmod(mapPath, 0o600);
    const linkPath = join(directory, "principal-map-link.json");
    await symlink(mapPath, linkPath);
    expect((await readPrivateV4PrincipalMap(linkPath)).status).toBe("invalid");
    expect((await readPrivateV4PrincipalMap(join(directory, "missing-map.json"))).status).toBe("absent");
  });

  it("loads a dedicated assertion key from a private file or a test environment value", async () => {
    vi.stubEnv("NODE_ENV", "test");
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-relay-key-"));
    activeDirectories.push(directory);
    const key = Buffer.from("synthetic-relay-assertion-key-for-connector-tests");
    const encoded = key.toString("base64url");
    const keyPath = join(directory, "assertion-key");
    await writeFile(keyPath, `${encoded}\n`, { mode: 0o600 });
    await chmod(keyPath, 0o600);
    const fromFile = await readPrivateV4RelayAssertion("test-key-1", keyPath, undefined);
    const fromEnvironment = await readPrivateV4RelayAssertion("test-key-1", undefined, encoded);
    expect(fromFile?.keyId).toBe("test-key-1");
    expect(Buffer.from(fromFile?.secret ?? []).equals(key)).toBe(true);
    expect(Buffer.from(fromEnvironment?.secret ?? []).equals(key)).toBe(true);
    expect(await readPrivateV4RelayAssertion("test-key-1", keyPath, encoded)).toBeUndefined();

    const parentAlias = join(directory, "assertion-parent-link");
    await symlink(directory, parentAlias, "dir");
    expect(await readPrivateV4RelayAssertion("test-key-1", join(parentAlias, "assertion-key"), undefined)).toBeDefined();

    await chmod(keyPath, 0o640);
    expect(await readPrivateV4RelayAssertion("test-key-1", keyPath, undefined)).toBeUndefined();
    await chmod(keyPath, 0o600);
    expect(await readPrivateV4RelayAssertion("test-key-1", "relative/assertion-key", undefined)).toBeUndefined();

    const invalidPath = join(directory, "invalid-assertion-key");
    await writeFile(invalidPath, "not-base64url\n", { mode: 0o600 });
    await chmod(invalidPath, 0o600);
    expect(await readPrivateV4RelayAssertion("test-key-1", invalidPath, undefined)).toBeUndefined();

    const linkedPath = join(directory, "linked-assertion-key");
    await symlink(keyPath, linkedPath);
    expect(await readPrivateV4RelayAssertion("test-key-1", linkedPath, undefined)).toBeUndefined();

    const owner = process.getuid();
    const ownerSpy = vi.spyOn(process, "getuid").mockReturnValue(owner + 1);
    try {
      expect(await readPrivateV4RelayAssertion("test-key-1", keyPath, undefined)).toBeUndefined();
    } finally {
      ownerSpy.mockRestore();
    }
  });

  it("finds repository boundaries for standalone and checkout secret paths", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-repository-root-"));
    activeDirectories.push(directory);

    const standaloneModuleDirectory = join(directory, "standalone", "src", "lib");
    await mkdir(standaloneModuleDirectory, { recursive: true });
    await writeFile(join(directory, ".git"), "unrelated dotfiles marker\n", { mode: 0o600 });
    const standaloneSecretPath = join(directory, ".local", "state", "atlas-relay", "assertion-key");
    await mkdir(dirname(standaloneSecretPath), { recursive: true });
    const encoded = Buffer.from("synthetic-standalone-assertion-key").toString("base64url");
    await writeFile(standaloneSecretPath, `${encoded}\n`, { mode: 0o600 });
    await chmod(standaloneSecretPath, 0o600);

    const standaloneRoot = await findRepositoryRoot(standaloneModuleDirectory);
    expect(standaloneRoot).toBeUndefined();
    expect(isOutsideRepositoryPath(standaloneSecretPath, undefined)).toBe(true);
    expect(await readPrivateV4RelayAssertion("standalone-test", standaloneSecretPath, undefined)).toBeDefined();
  });

  it("scopes repository fences to ATLAS checkouts and keeps the real checkout fenced", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-atlas-root-"));
    activeDirectories.push(directory);
    const checkoutRoot = join(directory, "atlas-checkout");
    const checkoutModuleDirectory = join(checkoutRoot, "integrations", "relay", "pc", "src", "lib");
    await mkdir(checkoutModuleDirectory, { recursive: true });
    await writeFile(join(checkoutRoot, ".git"), "synthetic git marker\n", { mode: 0o600 });
    await writeFile(join(checkoutRoot, "AGENTS.md"), "synthetic marker\n", { mode: 0o600 });
    const checkoutSecretPath = join(checkoutRoot, "state", "assertion-key");
    const externalSecretPath = join(directory, "external", "state", "assertion-key");
    await mkdir(dirname(checkoutSecretPath), { recursive: true });
    await mkdir(dirname(externalSecretPath), { recursive: true });
    const externalSecret = Buffer.from("synthetic-atlas-external-assertion").toString("base64url");
    await writeFile(externalSecretPath, `${externalSecret}\n`, { mode: 0o600 });
    await chmod(externalSecretPath, 0o600);
    const checkoutRootFound = await findRepositoryRoot(checkoutModuleDirectory);
    expect(checkoutRootFound).toBe(checkoutRoot);
    expect(isOutsideRepositoryPath(checkoutSecretPath, checkoutRootFound)).toBe(false);
    expect(isOutsideRepositoryPath(externalSecretPath, checkoutRootFound)).toBe(true);
    expect(isOutsideRepositoryPath("relative/assertion-key", checkoutRootFound)).toBe(false);
    expect(await readPrivateV4RelayAssertion("external-test", externalSecretPath, undefined)).toBeDefined();

    const actualCheckoutRoot = await findRepositoryRoot(process.cwd());
    expect(actualCheckoutRoot).toBeDefined();
    const actualSecretDirectory = await mkdtemp(join(process.cwd(), ".test-v4-assertion-in-checkout-"));
    activeDirectories.push(actualSecretDirectory);
    const actualSecretPath = join(actualSecretDirectory, "assertion-key");
    const actualSecret = Buffer.from("synthetic-real-checkout-assertion-key").toString("base64url");
    await writeFile(actualSecretPath, `${actualSecret}\n`, { mode: 0o600 });
    await chmod(actualSecretPath, 0o600);
    expect(await readPrivateV4RelayAssertion("checkout-test", actualSecretPath, undefined)).toBeUndefined();
  });

  it("uses the outermost ATLAS boundary when checkouts are nested", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-nested-root-"));
    activeDirectories.push(directory);
    const outerRoot = join(directory, "outer-checkout");
    const nestedRoot = join(outerRoot, "projects", "nested-checkout");
    const moduleDirectory = join(nestedRoot, "integrations", "relay", "pc", "src", "lib");
    await mkdir(join(outerRoot, "integrations", "relay", "pc"), { recursive: true });
    await mkdir(moduleDirectory, { recursive: true });
    await writeFile(join(outerRoot, ".git"), "synthetic outer git marker\n", { mode: 0o600 });
    await writeFile(join(nestedRoot, "AGENTS.md"), "synthetic nested marker\n", { mode: 0o600 });

    const outerSecretPath = join(outerRoot, "state", "assertion-key");
    const externalSecretPath = join(directory, "outside", "state", "assertion-key");
    await mkdir(dirname(outerSecretPath), { recursive: true });
    await mkdir(dirname(externalSecretPath), { recursive: true });
    const secret = Buffer.from("synthetic-nested-checkout-assertion-key").toString("base64url");
    await writeFile(outerSecretPath, `${secret}\n`, { mode: 0o600 });
    await chmod(outerSecretPath, 0o600);
    await writeFile(externalSecretPath, `${secret}\n`, { mode: 0o600 });
    await chmod(externalSecretPath, 0o600);

    const discoveredRoot = await findRepositoryRoot(moduleDirectory);
    expect(discoveredRoot).toBe(outerRoot);
    expect(isOutsideRepositoryPath(outerSecretPath, discoveredRoot)).toBe(false);
    expect(isOutsideRepositoryPath(externalSecretPath, discoveredRoot)).toBe(true);
    expect(await readPrivateV4RelayAssertion("outside-test", externalSecretPath, undefined)).toBeDefined();
  });

  it("fails closed when repository inspection encounters an inaccessible ancestor", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-inaccessible-root-"));
    activeDirectories.push(directory);
    const outerRoot = join(directory, "outer-checkout");
    const lockedIntegrations = join(outerRoot, "integrations");
    const nestedRoot = join(outerRoot, "nested-checkout");
    const moduleDirectory = join(nestedRoot, "integrations", "relay", "pc", "src");
    await mkdir(moduleDirectory, { recursive: true });
    await mkdir(lockedIntegrations, { recursive: true });
    await writeFile(join(outerRoot, ".git"), "synthetic outer marker\n", { mode: 0o600 });
    await writeFile(join(nestedRoot, "AGENTS.md"), "synthetic nested marker\n", { mode: 0o600 });
    await chmod(lockedIntegrations, 0o000);
    try {
      await expect(findRepositoryRoot(moduleDirectory)).rejects.toMatchObject({ code: "EACCES" });
      expect(await readPrivateV4RelayAssertion("inaccessible-test", join(lockedIntegrations, "secret"), undefined)).toBeUndefined();
    } finally {
      await chmod(lockedIntegrations, 0o700);
    }
  });

  it("accepts a stable symlink parent and detects canonical parent replacement", async () => {
    const directory = await mkdtemp(join(tmpdir(), "atlas-v4-parent-identity-"));
    activeDirectories.push(directory);
    const secretDirectory = join(directory, "private-state");
    await mkdir(secretDirectory);
    const secretPath = join(secretDirectory, "assertion-key");
    const secret = Buffer.from("synthetic-parent-identity-assertion-key").toString("base64url");
    await writeFile(secretPath, `${secret}\n`, { mode: 0o600 });
    await chmod(secretPath, 0o600);
    const parentAlias = join(directory, "private-state-link");
    await symlink(secretDirectory, parentAlias, "dir");
    expect(await readPrivateV4RelayAssertion("parent-link-test", join(parentAlias, "assertion-key"), undefined)).toBeDefined();

    const canonicalParent = join(directory, "canonical-parent");
    const oldParent = join(directory, "canonical-parent-old");
    await mkdir(canonicalParent);
    const beforeSymlinkReplacement = await lstat(canonicalParent);
    await rename(canonicalParent, oldParent);
    await symlink(oldParent, canonicalParent, "dir");
    const afterSymlinkReplacement = await lstat(canonicalParent);
    expect(sameDirectoryIdentity(beforeSymlinkReplacement, afterSymlinkReplacement)).toBe(false);

    await rm(canonicalParent);
    await mkdir(canonicalParent);
    const afterDirectoryReplacement = await lstat(canonicalParent);
    expect(sameDirectoryIdentity(beforeSymlinkReplacement, afterDirectoryReplacement)).toBe(false);
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
