import { constants } from "node:fs";
import { lstat, open, realpath } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { BackendClient } from "./backend.js";
import { DeviceRegistry } from "./device-registry.js";
import { ConnectorDiagnostics } from "./diagnostics.js";
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
  v4AuthMode?: string;
  requirePrincipalBinding?: boolean;
  v4PrincipalMapFile?: string;
  v4RelayAssertionKeyId?: string;
  v4RelayAssertionSecretFile?: string;
  v4RelayAssertionSecret?: string;
  /** Development-only compatibility path when legacy mode has no principal map. */
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
  const v4AuthMode = process.env.ATLAS_V4_AUTH_MODE ?? "legacy";
  if (!["legacy", "dual", "principal"].includes(v4AuthMode)) throw new Error("invalid ATLAS_V4_AUTH_MODE");
  const requirePrincipalBinding = process.env.ATLAS_REQUIRE_PRINCIPAL_BINDING;
  if (requirePrincipalBinding !== undefined && !["0", "1"].includes(requirePrincipalBinding)) {
    throw new Error("ATLAS_REQUIRE_PRINCIPAL_BINDING must be 0 or 1");
  }
  const assertionSecretFile = process.env.ATLAS_V4_RELAY_ASSERTION_SECRET_FILE;
  const assertionSecret = process.env.ATLAS_V4_RELAY_ASSERTION_SECRET;
  if (assertionSecret !== undefined && process.env.NODE_ENV !== "test") {
    throw new Error("ATLAS_V4_RELAY_ASSERTION_SECRET is accepted only when NODE_ENV=test");
  }
  if (assertionSecretFile !== undefined && assertionSecret !== undefined) {
    throw new Error("configure exactly one Relay assertion secret source");
  }
  return {
    stateDir,
    relayUrl: process.env.ATLAS_RELAY_URL ?? "ws://127.0.0.1:4400",
    allowLoopbackWs: process.env.ATLAS_ALLOW_LOOPBACK_WS === "1",
    ...(process.env.ATLAS_RELAY_SERVER_ID ? { serverId: process.env.ATLAS_RELAY_SERVER_ID } : {}),
    backendUrl: process.env.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100",
    ...(process.env.ATLAS_SERVICE_TOKEN ? { serviceToken: process.env.ATLAS_SERVICE_TOKEN } : {}),
    v4Enabled: process.env.ATLAS_V4_ENABLED === "1",
    v4AuthMode,
    requirePrincipalBinding: requirePrincipalBinding === "1",
    ...(process.env.ATLAS_CORE_PRINCIPAL_MAP_FILE ? { v4PrincipalMapFile: process.env.ATLAS_CORE_PRINCIPAL_MAP_FILE } : {}),
    ...(process.env.ATLAS_V4_RELAY_ASSERTION_KEY_ID ? { v4RelayAssertionKeyId: process.env.ATLAS_V4_RELAY_ASSERTION_KEY_ID } : {}),
    ...(process.env.ATLAS_V4_RELAY_ASSERTION_SECRET_FILE ? { v4RelayAssertionSecretFile: process.env.ATLAS_V4_RELAY_ASSERTION_SECRET_FILE } : {}),
    ...(process.env.ATLAS_V4_RELAY_ASSERTION_SECRET ? { v4RelayAssertionSecret: process.env.ATLAS_V4_RELAY_ASSERTION_SECRET } : {}),
    ...(process.env.ATLAS_V4_ANDROID_TOKEN_FILE ? { v4AndroidTokenFile: process.env.ATLAS_V4_ANDROID_TOKEN_FILE } : {}),
    metricsPort,
    handshakeTimeoutMs: integerEnv("ATLAS_HANDSHAKE_TIMEOUT_MS", 8000, 100, 60_000),
  };
}

export async function createConnector(config: ConnectorConfig): Promise<ConnectorRuntime> {
  let v4Enabled = config.v4Enabled === true;
  if (!["legacy", "dual", "principal"].includes(config.v4AuthMode ?? "legacy")) v4Enabled = false;
  let principalCredentials: Map<string, { principalId: string; credential: string }> | undefined;
  let legacyAndroidToken: string | undefined;
  let relayAssertion: { keyId: string; secret: Uint8Array } | undefined;
  if (v4Enabled) {
    const map = await readPrivateV4PrincipalMap(config.v4PrincipalMapFile);
    const credentialMode = selectV4CredentialMode(
      config.v4AuthMode ?? "legacy", config.requirePrincipalBinding === true, map.status,
    );
    if (credentialMode === "principal-map" && map.status === "valid") {
      relayAssertion = await readPrivateV4RelayAssertion(config.v4RelayAssertionKeyId,
        config.v4RelayAssertionSecretFile, config.v4RelayAssertionSecret);
      if (relayAssertion) principalCredentials = map.credentials;
      else v4Enabled = false;
    } else if (credentialMode === "legacy-fallback") {
      legacyAndroidToken = await readPrivateV4AndroidToken(config.v4AndroidTokenFile);
      v4Enabled = Boolean(legacyAndroidToken);
    } else {
      v4Enabled = false;
    }
  }
  const registry = new DeviceRegistry(join(config.stateDir, "devices.json"));
  // Validate existing state before creating any connector identity or opening a relay.
  await registry.list();
  const identityPath = join(config.stateDir, "identity.json");
  const { identity, keyPair } = await loadOrCreateIdentity(identityPath);
  const metrics = new ConnectorMetrics();
  const diagnostics = new ConnectorDiagnostics();
  const backend = new BackendClient({
    baseUrl: config.backendUrl,
    ...(config.serviceToken ? { serviceToken: config.serviceToken } : {}),
    v4Enabled,
    ...(diagnostics.enabled ? {
      fetcher: async (input, init) => {
        const response = await fetch(input, init);
        diagnostics.observeHttpStatus(response.status);
        return response;
      },
    } : {}),
    ...(principalCredentials && relayAssertion ? {
      pairedDeviceAuth: (relayDeviceId: string) => {
        const selected = principalCredentials?.get(relayDeviceId);
        return selected ? { principalId: selected.principalId, credential: selected.credential } : undefined;
      },
      relayAssertion,
    } : {}),
    ...(legacyAndroidToken ? {
      // Development compatibility only: enabled solely in legacy mode with no principal map.
      pairedDeviceToken: (pairedDeviceId: string) => pairedDeviceId.length > 0 ? legacyAndroidToken : undefined,
    } : {}),
  });
  let relay: RelayLink;
  const operationExecutor = new OperationExecutor(backend, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && config.serviceToken),
    uncertainOutcome: () => metrics.uncertainOutcome(),
  }, Date.now, join(config.stateDir, "operations.json"), diagnostics);
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

export function selectV4CredentialMode(
  authMode: string,
  principalBindingRequired: boolean,
  mapStatus: PrincipalMapRead["status"],
): "principal-map" | "legacy-fallback" | "disabled" {
  if (authMode !== "legacy" && authMode !== "dual" && authMode !== "principal") return "disabled";
  if (mapStatus === "invalid") return "disabled";
  if (mapStatus === "valid") return authMode === "dual" || authMode === "principal" ? "principal-map" : "disabled";
  return authMode === "legacy" && !principalBindingRequired ? "legacy-fallback" : "disabled";
}

/** Reads a mode-0600 owner-controlled file without following links or accepting inode swaps. */
async function readPrivateFile(path: string | undefined, maxBytes: number): Promise<string | undefined> {
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
        file.size < 1 || file.size > maxBytes) {
      return undefined;
    }

    const raw = await handle.readFile("utf8");
    const afterRead = await lstat(path);
    if (afterRead.isSymbolicLink() || afterRead.dev !== file.dev || afterRead.ino !== file.ino) return undefined;
    return raw;
  } catch {
    // Missing, inaccessible, replaced, or unsafe credential files disable V4 without
    // affecting V1/V2/V3 or exposing filesystem details in connector responses.
    return undefined;
  } finally {
    await handle?.close().catch(() => {});
  }
}

/** Reads the dedicated legacy Core Android bearer used only by the explicit dev fallback. */
export async function readPrivateV4AndroidToken(path: string | undefined): Promise<string | undefined> {
  const raw = await readPrivateFile(path, 4_098);
  if (raw === undefined) return undefined;
  const token = raw.endsWith("\r\n") ? raw.slice(0, -2) : raw.endsWith("\n") ? raw.slice(0, -1) : raw;
  if (token.length < 1 || token.length > 4_096 || /[^\x21-\x7e]/.test(token)) return undefined;
  return token;
}

export type PrincipalMapRead =
  | { status: "absent" }
  | { status: "invalid" }
  | { status: "valid"; credentials: Map<string, { principalId: string; credential: string }> };

/** Loads Relay-ID → principal credential mappings from a private JSON file. */
export async function readPrivateV4PrincipalMap(path: string | undefined): Promise<PrincipalMapRead> {
  if (!path) return { status: "absent" };
  try {
    await lstat(path);
  } catch (error) {
    if (isNotFound(error)) return { status: "absent" };
    return { status: "invalid" };
  }
  const raw = await readPrivateFile(path, 262_144);
  if (raw === undefined) return { status: "invalid" };
  try {
    const value: unknown = JSON.parse(raw);
    if (!isRecord(value) || Object.keys(value).length < 1 || Object.keys(value).length > 4096) {
      return { status: "invalid" };
    }
    const credentials = new Map<string, { principalId: string; credential: string }>();
    for (const [relayDeviceId, entry] of Object.entries(value)) {
      if (!relayDeviceId || relayDeviceId.length > 256 || !isRecord(entry)
        || Object.keys(entry).sort().join(",") !== "credential_file,principal_id"
        || typeof entry.principal_id !== "string" || !entry.principal_id
        || typeof entry.credential_file !== "string" || !isAbsolute(entry.credential_file)) {
        return { status: "invalid" };
      }
      const credential = await readPrivateV4AndroidToken(entry.credential_file);
      if (!credential) return { status: "invalid" };
      credentials.set(relayDeviceId, { principalId: entry.principal_id, credential });
    }
    return { status: "valid", credentials };
  } catch {
    return { status: "invalid" };
  }
}

/** Loads a base64url-encoded dedicated connector-to-Core assertion secret. */
export async function readPrivateV4RelayAssertion(
  keyId: string | undefined,
  secretFile: string | undefined,
  secretEnvironment: string | undefined,
): Promise<{ keyId: string; secret: Uint8Array } | undefined> {
  const hasSecretFile = secretFile !== undefined;
  const hasSecretEnvironment = secretEnvironment !== undefined;
  if (!keyId || !/^[A-Za-z0-9._-]{1,64}$/.test(keyId) || hasSecretFile === hasSecretEnvironment) return undefined;
  if (hasSecretEnvironment && process.env.NODE_ENV !== "test") return undefined;
  if (hasSecretFile && !(await isOutsideRepository(secretFile))) return undefined;
  const rawValue = secretEnvironment ?? await readPrivateFile(secretFile, 512);
  if (!rawValue) return undefined;
  const encoded = rawValue.endsWith("\r\n") ? rawValue.slice(0, -2) : rawValue.endsWith("\n") ? rawValue.slice(0, -1) : rawValue;
  if (!/^[A-Za-z0-9_-]{43,}$/.test(encoded)) return undefined;
  try {
    const secret = Buffer.from(encoded, "base64url");
    if (secret.length < 32) return undefined;
    return { keyId, secret };
  } catch {
    return undefined;
  }
}

/** Finds a Git checkout root by walking upward from a module directory. */
export async function findRepositoryRoot(startDirectory: string): Promise<string | undefined> {
  let current = resolve(startDirectory);
  while (true) {
    try {
      await lstat(join(current, ".git"));
      return current;
    } catch (error) {
      if (!isNotFound(error)) return resolve(current, sep);
    }
    const parent = dirname(current);
    if (parent === current) return undefined;
    current = parent;
  }
}

/** Pure path check; no Git context means standalone deployment with no repository boundary. */
export function isOutsideRepositoryPath(path: string, repositoryRoot: string | undefined): boolean {
  if (!isAbsolute(path)) return false;
  if (repositoryRoot === undefined) return true;
  const fromRepository = relative(resolve(repositoryRoot), resolve(path));
  return fromRepository === ".." || fromRepository.startsWith(`..${sep}`) || isAbsolute(fromRepository);
}

async function isOutsideRepository(path: string): Promise<boolean> {
  if (!isAbsolute(path)) return false;
  try {
    const actualPath = resolve(await realpath(dirname(path)), basename(path));
    const moduleDirectory = await realpath(dirname(fileURLToPath(import.meta.url)));
    const repositoryRoot = await findRepositoryRoot(moduleDirectory);
    return isOutsideRepositoryPath(actualPath, repositoryRoot);
  } catch {
    return false;
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

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
