import { homedir } from "node:os";
import { join } from "node:path";
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
  serverId?: string;
  backendUrl: string;
  serviceToken?: string;
  metricsPort: number;
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
    ...(process.env.ATLAS_RELAY_SERVER_ID ? { serverId: process.env.ATLAS_RELAY_SERVER_ID } : {}),
    backendUrl: process.env.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100",
    ...(process.env.ATLAS_SERVICE_TOKEN ? { serviceToken: process.env.ATLAS_SERVICE_TOKEN } : {}),
    metricsPort,
  };
}

export async function createConnector(config: ConnectorConfig): Promise<ConnectorRuntime> {
  const identityPath = join(config.stateDir, "identity.json");
  const registry = new DeviceRegistry(join(config.stateDir, "devices.json"));
  const { identity, keyPair } = await loadOrCreateIdentity(identityPath);
  const metrics = new ConnectorMetrics();
  const backend = new BackendClient({
    baseUrl: config.backendUrl,
    ...(config.serviceToken ? { serviceToken: config.serviceToken } : {}),
  });
  let relay: RelayLink;
  const operationExecutor = new OperationExecutor(backend, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && config.serviceToken),
  });
  const sessions = new SessionManager(registry, operationExecutor, metrics, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && config.serviceToken),
  });
  relay = new RelayLink({
    relayUrl: config.relayUrl,
    serverId: config.serverId ?? `atlas-pc-${identity.fingerprint.slice(0, 32)}`,
    daemonKeyPair: keyPair,
    sessions,
    metrics,
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

function integerEnv(name: string, fallback: number, minimum: number, maximum: number): number {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  if (!/^\d+$/.test(raw)) throw new Error(`invalid ${name}`);
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) throw new Error(`invalid ${name}`);
  return parsed;
}
