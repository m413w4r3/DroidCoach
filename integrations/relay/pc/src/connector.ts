import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import { BackendClient } from "./backend.js";
import { DeviceRegistry } from "./device-registry.js";
import { loadOrCreateIdentity } from "./identity.js";
import { ConnectorMetrics } from "./metrics.js";
import { OperationExecutor } from "./operations.js";
import { RelayLink } from "./relay-link.js";
import { SessionManager } from "./session.js";

export interface ConnectorConfig {
  stateDir: string;
  relayUrl: string;
  allowLoopbackWs: boolean;
  serverId?: string;
  backendUrl: string;
  serviceToken?: string;
  v4Enabled?: boolean;
  v4AndroidTokenFile?: string;
  metricsPort: number;
  handshakeTimeoutMs?: number;
}

export interface ConnectorRuntime {
  identityPath: string;
  registry: DeviceRegistry;
  metrics: ConnectorMetrics;
  relay: RelayLink;
  sessions: SessionManager;
  start(): Promise<void>;
  stop(): Promise<void>;
}

export function configFromEnvironment(): ConnectorConfig {
  const stateDir = process.env.ATLAS_RELAY_STATE_DIR ?? join(homedir(), ".local", "state", "atlas-relay");
  const metricsPort = integerEnv("ATLAS_METRICS_PORT", 4401, 1, 65_535);
  return {
    stateDir,
    relayUrl: process.env.ATLAS_RELAY_URL ?? "ws://127.0.0.1:4400",
    allowLoopbackWs: process.env.ATLAS_ALLOW_LOOPBACK_WS === "1",
    ...(process.env.ATLAS_RELAY_SERVER_ID ? { serverId: process.env.ATLAS_RELAY_SERVER_ID } : {}),
    backendUrl: process.env.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100",
    ...(process.env.ATLAS_SERVICE_TOKEN ? { serviceToken: process.env.ATLAS_SERVICE_TOKEN } : {}),
    v4Enabled: process.env.ATLAS_V4_ENABLED === "1",
    ...(process.env.ATLAS_V4_ANDROID_TOKEN_FILE ? { v4AndroidTokenFile: process.env.ATLAS_V4_ANDROID_TOKEN_FILE } : {}),
    metricsPort,
    handshakeTimeoutMs: integerEnv("ATLAS_HANDSHAKE_TIMEOUT_MS", 8000, 100, 60_000),
  };
}

export async function createConnector(config: ConnectorConfig): Promise<ConnectorRuntime> {
  const v4AndroidToken = config.v4Enabled ? await readPrivateV4AndroidToken(config.v4AndroidTokenFile) : undefined;
  const registry = new DeviceRegistry(join(config.stateDir, "devices.json"));
  // Validate existing state before creating any connector identity or opening a relay.
  await registry.list();
  const identityPath = join(config.stateDir, "identity.json");
  const { identity, keyPair } = await loadOrCreateIdentity(identityPath);
  const metrics = new ConnectorMetrics();
  const backend = new BackendClient({
    baseUrl: config.backendUrl,
    ...(config.serviceToken ? { serviceToken: config.serviceToken } : {}),
    v4Enabled: config.v4Enabled === true,
    ...(v4AndroidToken ? {
      // The shared Android bearer is only selected after an authenticated relay session
      // supplies its paired device id; payload data never chooses credentials.
      pairedDeviceToken: (pairedDeviceId: string) => pairedDeviceId.length > 0 ? v4AndroidToken : undefined,
    } : {}),
  });
  let relay: RelayLink;
  const operationExecutor = new OperationExecutor(backend, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && config.serviceToken),
    uncertainOutcome: () => metrics.uncertainOutcome(),
  }, Date.now, join(config.stateDir, "operations.json"));
  await operationExecutor.initialize();
  const sessions = new SessionManager(registry, operationExecutor, metrics, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && config.serviceToken),
  });
  relay = new RelayLink({
    relayUrl: config.relayUrl,
    allowLoopbackWs: config.allowLoopbackWs,
    serverId: config.serverId ?? `atlas-pc-${identity.fingerprint.slice(0, 32)}`,
    daemonKeyPair: keyPair,
    sessions,
    metrics,
    ...(config.handshakeTimeoutMs !== undefined ? { handshakeTimeoutMs: config.handshakeTimeoutMs } : {}),
  });
  let revocationPoll: ReturnType<typeof setInterval> | null = null;
  return {
    identityPath,
    registry,
    metrics,
    relay,
    sessions,
    async start() {
      await metrics.listen(config.metricsPort);
      await relay.start();
      revocationPoll = setInterval(() => void sessions.closeRevokedSessions(), 1_000);
      revocationPoll.unref?.();
    },
    async stop() {
      if (revocationPoll) clearInterval(revocationPoll);
      revocationPoll = null;
      await relay.stop();
      await metrics.close();
    },
  };
}

/** Reads the dedicated Core Android bearer from a private, owner-controlled file. */
export async function readPrivateV4AndroidToken(path: string | undefined): Promise<string | undefined> {
  if (!path || !isAbsolute(path) || typeof process.getuid !== "function" || constants.O_NOFOLLOW === undefined) return undefined;

  let handle: Awaited<ReturnType<typeof open>> | undefined;
  try {
    const beforeOpen = await lstat(path);
    if (!beforeOpen.isFile() || beforeOpen.isSymbolicLink()) return undefined;

    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const file = await handle.stat();
    const owner = process.getuid();
    if (!file.isFile() || file.uid !== owner || beforeOpen.uid !== owner ||
        (file.mode & 0o777) !== 0o600 || beforeOpen.dev !== file.dev || beforeOpen.ino !== file.ino ||
        file.size < 1 || file.size > 4_098) {
      return undefined;
    }

    const raw = await handle.readFile("utf8");
    const token = raw.endsWith("\r\n") ? raw.slice(0, -2) : raw.endsWith("\n") ? raw.slice(0, -1) : raw;
    if (token.length < 1 || token.length > 4_096 || /[^\x21-\x7e]/.test(token)) return undefined;
    return token;
  } catch {
    // Missing, inaccessible, replaced, or unsafe credential files disable V4 without
    // affecting V1/V2/V3 or exposing filesystem details in connector responses.
    return undefined;
  } finally {
    await handle?.close().catch(() => {});
  }
}

function integerEnv(name: string, fallback: number, minimum: number, maximum: number): number {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  if (!/^\d+$/.test(raw)) throw new Error(`invalid ${name}`);
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) throw new Error(`invalid ${name}`);
  return parsed;
}
