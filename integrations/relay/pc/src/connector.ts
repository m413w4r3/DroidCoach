import { constants, type Stats } from "node:fs";
import { lstat, open, realpath } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { assertBackendHttpUrl, BackendClient } from "./backend.js";
import { DeviceRegistry, type DeviceRecord } from "./device-registry.js";
import { ConnectorDiagnostics } from "./diagnostics.js";
import { loadOrCreateIdentity } from "./identity.js";
import { ConnectorMetrics } from "./metrics.js";
import { OperationExecutor } from "./operations.js";
import { parseRelayUrl, RelayLink } from "./relay-link.js";
import { SessionManager } from "./session.js";

export interface ConnectorConfig {
  stateDir: string;
  relayUrl: string;
  allowLoopbackWs: boolean;
  dockerNetwork?: boolean;
  serverId?: string;
  backendUrl: string;
  serviceToken?: string;
  serviceTokenFile?: string;
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
  const dockerNetwork = process.env.ATLAS_DOCKER_NETWORK === "1";
  const relayUrl = process.env.ATLAS_RELAY_URL ?? "ws://127.0.0.1:4400";
  const backendUrl = process.env.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100";
  const serviceToken = process.env.ATLAS_SERVICE_TOKEN;
  const serviceTokenFile = process.env.ATLAS_SERVICE_TOKEN_FILE;
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
    relayUrl,
    allowLoopbackWs: dockerNetwork || process.env.ATLAS_ALLOW_LOOPBACK_WS === "1",
    dockerNetwork,
    ...(process.env.ATLAS_RELAY_SERVER_ID ? { serverId: process.env.ATLAS_RELAY_SERVER_ID } : {}),
    backendUrl,
    ...(serviceToken ? { serviceToken } : {}),
    ...(serviceTokenFile !== undefined ? { serviceTokenFile } : {}),
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

/** Validates both fixed outbound destinations before the connector touches its state. */
export function validateConnectorEndpoints(config: Pick<ConnectorConfig,
  "relayUrl" | "backendUrl" | "allowLoopbackWs" | "dockerNetwork">): void {
  const dockerNetwork = config.dockerNetwork === true;
  parseRelayUrl(config.relayUrl, {
    dockerNetwork,
    allowLoopbackWs: dockerNetwork || config.allowLoopbackWs,
  });
  assertBackendHttpUrl(config.backendUrl, { dockerNetwork });
}

export async function createConnector(config: ConnectorConfig): Promise<ConnectorRuntime> {
  const dockerNetwork = config.dockerNetwork === true;
  validateConnectorEndpoints(config);
  if (config.serviceToken !== undefined && config.serviceTokenFile !== undefined) {
    throw new Error("configure exactly one service token source");
  }
  let serviceToken = config.serviceToken;
  if (config.serviceTokenFile !== undefined) serviceToken = await readPrivateServiceToken(config.serviceTokenFile);

  const registry = new DeviceRegistry(join(config.stateDir, "devices.json"));
  // Validate existing state before creating any connector identity or opening a relay.
  const devices = await registry.list();

  let v4Enabled = config.v4Enabled === true;
  if (!["legacy", "dual", "principal"].includes(config.v4AuthMode ?? "legacy")) v4Enabled = false;
  let principalCredentials: Map<string, { principalId: string; credential: string }> | undefined;
  let legacyAndroidToken: string | undefined;
  let relayAssertion: { keyId: string; secret: Uint8Array } | undefined;
  if (v4Enabled) {
    const map = await readPrivateV4PrincipalMapIndex(config.v4PrincipalMapFile);
    const credentialMode = selectV4CredentialMode(
      config.v4AuthMode ?? "legacy", config.requirePrincipalBinding === true, map.status,
    );
    if (credentialMode === "principal-map" && map.status === "valid") {
      if (!principalMapCoversActiveDevices(map.entries, devices)) {
        v4Enabled = false;
        process.stderr.write("ATLAS V4 disabled: principal map does not cover active devices\n");
      } else {
        principalCredentials = await loadPrincipalMapCredentials(map.entries);
        if (principalCredentials) {
          relayAssertion = await readPrivateV4RelayAssertion(config.v4RelayAssertionKeyId,
            config.v4RelayAssertionSecretFile, config.v4RelayAssertionSecret);
        }
        if (!principalCredentials || !relayAssertion) v4Enabled = false;
      }
    } else if (credentialMode === "legacy-fallback") {
      legacyAndroidToken = await readPrivateV4AndroidToken(config.v4AndroidTokenFile);
      v4Enabled = Boolean(legacyAndroidToken);
    } else {
      v4Enabled = false;
    }
  }
  const identityPath = join(config.stateDir, "identity.json");
  const { identity, keyPair } = await loadOrCreateIdentity(identityPath);
  const metrics = new ConnectorMetrics();
  const diagnostics = new ConnectorDiagnostics();
  const backend = new BackendClient({
    baseUrl: config.backendUrl,
    ...(serviceToken ? { serviceToken } : {}),
    dockerNetwork,
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
    backendConfigured: () => Boolean(config.backendUrl && serviceToken),
    uncertainOutcome: () => metrics.uncertainOutcome(),
  }, Date.now, join(config.stateDir, "operations.json"), diagnostics);
  await operationExecutor.initialize();
  const sessions = new SessionManager(registry, operationExecutor, metrics, {
    relayConnected: () => relay.connected,
    backendConfigured: () => Boolean(config.backendUrl && serviceToken),
  });
  relay = new RelayLink({
    relayUrl: config.relayUrl,
    allowLoopbackWs: dockerNetwork || config.allowLoopbackWs,
    dockerNetwork,
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

/** Returns true only when the principal map covers exactly the non-revoked device set. */
export function principalMapCoversActiveDevices<T>(credentials: Map<string, T>, devices: DeviceRecord[]): boolean {
  const activeDeviceIds = new Set(
    devices.filter((device) => device.revoked_at === null).map((device) => device.device_id),
  );
  if (credentials.size !== activeDeviceIds.size) return false;
  return [...credentials.keys()].every((deviceId) => activeDeviceIds.has(deviceId));
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
async function readPrivateFile(
  path: string | undefined,
  maxBytes: number,
  requireOutsideRepository = false,
): Promise<string | undefined> {
  if (!path || !isAbsolute(path) || typeof process.getuid !== "function" || constants.O_NOFOLLOW === undefined) return undefined;
  let handle: Awaited<ReturnType<typeof open>> | undefined;
  try {
    let actualPath = path;
    let parentBefore: Stats | undefined;
    if (requireOutsideRepository) {
      actualPath = resolve(await realpath(dirname(path)), basename(path));
      if (!(await isOutsideRepository(actualPath))) return undefined;
      parentBefore = await lstat(dirname(actualPath));
      if (!parentBefore.isDirectory() || parentBefore.isSymbolicLink()) return undefined;
    }

    const beforeOpen = await lstat(actualPath);
    if (!beforeOpen.isFile() || beforeOpen.isSymbolicLink()) return undefined;

    handle = await open(actualPath, constants.O_RDONLY | constants.O_NOFOLLOW);
    if (parentBefore && !sameDirectoryIdentity(parentBefore, await lstat(dirname(actualPath)))) return undefined;
    const file = await handle.stat();
    const owner = process.getuid();
    if (!file.isFile() || file.uid !== owner || beforeOpen.uid !== owner ||
        (file.mode & 0o7777) !== 0o600 || beforeOpen.dev !== file.dev || beforeOpen.ino !== file.ino ||
        file.size < 1 || file.size > maxBytes) {
      return undefined;
    }

    const raw = await handle.readFile("utf8");
    const afterRead = await lstat(actualPath);
    if ((parentBefore && !sameDirectoryIdentity(parentBefore, await lstat(dirname(actualPath)))) || afterRead.isSymbolicLink() ||
        afterRead.dev !== file.dev || afterRead.ino !== file.ino) return undefined;
    return raw;
  } catch {
    // Missing, inaccessible, replaced, or unsafe credential files disable V4 without
    // affecting V1/V2/V3 or exposing filesystem details in connector responses.
    return undefined;
  } finally {
    await handle?.close().catch(() => {});
  }
}

/** Loads the non-V4 service bearer from an owner-controlled secret file outside the checkout. */
export async function readPrivateServiceToken(path: string): Promise<string> {
  const token = await readPrivateFile(path, 4_096, true);
  if (token === undefined || token.length < 1 || Buffer.byteLength(token, "utf8") > 4_096
      || token.endsWith("\n") || token.endsWith("\r") || /[^\x21-\x7e]/.test(token)) {
    throw new Error("ATLAS_SERVICE_TOKEN_FILE is unavailable or invalid");
  }
  return token;
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

type PrincipalMapEntry = { principalId: string; credentialFile: string };
type PrincipalMapIndexRead =
  | { status: "absent" }
  | { status: "invalid" }
  | { status: "valid"; entries: Map<string, PrincipalMapEntry> };

/** Loads Relay-ID → principal credential mappings from a private JSON file. */
export async function readPrivateV4PrincipalMap(path: string | undefined): Promise<PrincipalMapRead> {
  const map = await readPrivateV4PrincipalMapIndex(path);
  if (map.status !== "valid") return map;
  const credentials = await loadPrincipalMapCredentials(map.entries);
  return credentials ? { status: "valid", credentials } : { status: "invalid" };
}

async function readPrivateV4PrincipalMapIndex(path: string | undefined): Promise<PrincipalMapIndexRead> {
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
    const entries = new Map<string, PrincipalMapEntry>();
    for (const [relayDeviceId, entry] of Object.entries(value)) {
      if (!relayDeviceId || relayDeviceId.length > 256 || !isRecord(entry)
        || Object.keys(entry).sort().join(",") !== "credential_file,principal_id"
        || typeof entry.principal_id !== "string" || !entry.principal_id
        || typeof entry.credential_file !== "string" || !isAbsolute(entry.credential_file)) {
        return { status: "invalid" };
      }
      entries.set(relayDeviceId, { principalId: entry.principal_id, credentialFile: entry.credential_file });
    }
    return { status: "valid", entries };
  } catch {
    return { status: "invalid" };
  }
}

async function loadPrincipalMapCredentials(
  entries: Map<string, PrincipalMapEntry>,
): Promise<Map<string, { principalId: string; credential: string }> | undefined> {
  const credentials = new Map<string, { principalId: string; credential: string }>();
  for (const [relayDeviceId, entry] of entries) {
    const credential = await readPrivateV4AndroidToken(entry.credentialFile);
    if (!credential) return undefined;
    credentials.set(relayDeviceId, { principalId: entry.principalId, credential });
  }
  return credentials;
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
  const rawValue = secretEnvironment ?? await readPrivateFile(secretFile, 512, hasSecretFile);
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

/** Finds the outermost ATLAS checkout root containing this package and a checkout marker. */
export async function findRepositoryRoot(startDirectory: string): Promise<string | undefined> {
  let current = resolve(startDirectory);
  let repositoryRoot: string | undefined;
  while (true) {
    const packagePath = join(current, "integrations", "relay", "pc");
    const packageEntry = await lstatIfPresent(packagePath);
    const gitMarker = await lstatIfPresent(join(current, ".git"));
    const agentMarker = await lstatIfPresent(join(current, "AGENTS.md"));
    const hasPackage = packageEntry?.isDirectory() === true && !packageEntry.isSymbolicLink();
    const hasGitMarker = gitMarker !== undefined && !gitMarker.isSymbolicLink();
    const hasAgentMarker = agentMarker !== undefined && !agentMarker.isSymbolicLink();
    if (hasPackage && (hasGitMarker || hasAgentMarker)) repositoryRoot = current;
    const parent = dirname(current);
    if (parent === current) return repositoryRoot;
    current = parent;
  }
}

/** Compares a canonical parent directory before and after a private-file read. */
export function sameDirectoryIdentity(before: Stats, after: Stats): boolean {
  return before.isDirectory() && !before.isSymbolicLink() && after.isDirectory() &&
    !after.isSymbolicLink() && before.dev === after.dev && before.ino === after.ino;
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
    const moduleDirectory = await realpath(dirname(fileURLToPath(import.meta.url)));
    const repositoryRoot = await findRepositoryRoot(moduleDirectory);
    return isOutsideRepositoryPath(path, repositoryRoot);
  } catch {
    return false;
  }
}

async function lstatIfPresent(path: string): Promise<Stats | undefined> {
  try {
    return await lstat(path);
  } catch (error) {
    if (isNotFound(error)) return undefined;
    throw error;
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
