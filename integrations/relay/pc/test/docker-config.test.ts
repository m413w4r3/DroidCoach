import { chmod, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { assertBackendHttpUrl } from "../src/backend.js";
import { configFromEnvironment, createConnector, readPrivateServiceToken, validateConnectorEndpoints } from "../src/connector.js";
import { parseRelayUrl } from "../src/relay-link.js";

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const temporaryDirectories: string[] = [];

beforeEach(() => {
  vi.stubEnv("ATLAS_DOCKER_NETWORK", "");
  vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "");
  vi.stubEnv("ATLAS_RELAY_URL", "ws://127.0.0.1:4400");
  vi.stubEnv("ATLAS_BACKEND_URL", "http://127.0.0.1:8100");
  vi.stubEnv("ATLAS_SERVICE_TOKEN", undefined);
  vi.stubEnv("ATLAS_SERVICE_TOKEN_FILE", undefined);
});

afterEach(async () => {
  vi.unstubAllEnvs();
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

async function makeTemporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "atlas-service-token-test-"));
  temporaryDirectories.push(directory);
  return directory;
}

async function writePrivateFile(path: string, content: string): Promise<void> {
  await writeFile(path, content, { mode: 0o600 });
  await chmod(path, 0o600);
}
