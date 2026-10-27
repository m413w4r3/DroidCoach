import { lstat, readFile } from "node:fs/promises";
import { lstatSync as lstatFileSync } from "node:fs";
import { isIP } from "node:net";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";

const ALLOWED_KEYS = new Set([
  "ATLAS_RELAY_URL",
  "ATLAS_RELAY_SERVER_ID",
  "ATLAS_RELAY_STATE_DIR",
  "ATLAS_BACKEND_URL",
  "ATLAS_SERVICE_TOKEN",
  "ATLAS_ALLOW_LOOPBACK_WS",
  "ATLAS_METRICS_PORT",
  "ATLAS_TEST_RELAY_URL",
]);
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

export async function loadEnvironmentFile(path) {
  const info = await lstat(path).catch(() => null);
  if (!info || info.isSymbolicLink() || !info.isFile()) throw new Error("environment file must be a regular, non-symlink file");
  const mode = info.mode & 0o777;
  if ((mode & 0o077) !== 0) throw new Error("environment file must not be accessible by group or other users");
  const content = await readFile(path, "utf8");
  const result = {};
  for (const [index, rawLine] of content.split(/\r?\n/).entries()) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
    if (!match) throw new Error(`invalid environment file syntax on line ${index + 1}`);
    const [, key, rawValue] = match;
    if (!ALLOWED_KEYS.has(key)) throw new Error(`unsupported environment key on line ${index + 1}`);
    if (Object.hasOwn(result, key)) throw new Error(`duplicate environment key on line ${index + 1}`);
    let value = rawValue;
    if (rawValue.startsWith("\"") || rawValue.startsWith("'")) {
      const quote = rawValue[0];
      if (rawValue.length < 2 || rawValue.at(-1) !== quote || rawValue.slice(1, -1).includes(quote) || rawValue.includes("\\")) {
        throw new Error(`unsupported quoted environment value on line ${index + 1}`);
      }
      value = rawValue.slice(1, -1);
    } else if (rawValue.includes("\\") || rawValue !== rawValue.trim()) {
      throw new Error(`unsupported environment value syntax on line ${index + 1}`);
    }
    if (value.includes("\0") || value.includes("\n") || value.includes("\r")) throw new Error(`invalid environment value on line ${index + 1}`);
    result[key] = value;
  }
  return { values: result, fileMode: mode };
}

export function runtimeEnvironment(fileValues = {}) {
  return { ...process.env, ...fileValues };
}

export function inspectConfiguration(environment, { checkState = true } = {}) {
  const errors = [];
  const checks = [];
  const relayValue = environment.ATLAS_RELAY_URL ?? "ws://127.0.0.1:4400";
  const backendValue = environment.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100";
  const stateDir = environment.ATLAS_RELAY_STATE_DIR ?? join(homedir(), ".local", "state", "atlas-relay");
  const metricsRaw = environment.ATLAS_METRICS_PORT ?? "4401";
  const allowLoopbackRaw = environment.ATLAS_ALLOW_LOOPBACK_WS;
  const allowLoopbackWs = allowLoopbackRaw === "1";
  if (allowLoopbackRaw !== undefined && allowLoopbackRaw !== "0" && allowLoopbackRaw !== "1") {
    errors.push("ATLAS_ALLOW_LOOPBACK_WS must be 0 or 1");
  }
  let relayUrl;
  try {
    relayUrl = validateRelayUrl(relayValue, { allowLoopbackWs });
    checks.push("relay URL follows the WSS/loopback policy");
  } catch {
    errors.push("relay URL must be wss:// for a remote relay, or ws:// on exact loopback with ATLAS_ALLOW_LOOPBACK_WS=1; no credentials, query, fragment, or path");
  }
  try {
    validateBackendUrl(backendValue);
    checks.push("FastAPI URL is HTTP loopback with no path, credentials, query, or fragment");
  } catch {
    errors.push("FastAPI URL must be http:// on loopback with no path, credentials, query, or fragment");
  }
  if (!isAbsolute(stateDir)) errors.push("state directory must be an absolute path");
  if (!/^\d+$/.test(metricsRaw) || Number(metricsRaw) < 1 || Number(metricsRaw) > 65_535) {
    errors.push("metrics port must be an integer from 1 to 65535");
  } else {
    checks.push(`metrics listener binds to 127.0.0.1:${metricsRaw}`);
  }
  if (environment.ATLAS_RELAY_SERVER_ID && Buffer.byteLength(environment.ATLAS_RELAY_SERVER_ID, "utf8") > 200) {
    errors.push("relay server id must not exceed 200 UTF-8 bytes");
  }
  if (checkState && isAbsolute(stateDir)) {
    const state = inspectStateDirectory(stateDir);
    checks.push(...state.checks);
    errors.push(...state.errors);
  }
  return { errors, checks, relayUrl, backendUrl: backendValue, stateDir, metricsPort: metricsRaw };
}

export function validateRelayUrl(value, { allowLoopbackWs = false } = {}) {
  const url = new URL(value);
  if (!["ws:", "wss:"].includes(url.protocol)) throw new Error("invalid relay URL");
  if (url.username || url.password || url.search || url.hash) throw new Error("invalid relay URL");
  if (!["/", "/ws"].includes(url.pathname)) throw new Error("invalid relay URL");
  if (!isValidRelayHost(url.hostname)) throw new Error("invalid relay URL");
  if (url.port) {
    const port = Number(url.port);
    if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("invalid relay URL");
  }
  if (url.protocol === "ws:") {
    const host = normalizeHost(url.hostname);
    if (!allowLoopbackWs || !LOOPBACK_HOSTS.has(host) && !LOOPBACK_HOSTS.has(url.hostname.toLowerCase())) {
      throw new Error("invalid relay URL");
    }
  }
  return url;
}

function normalizeHost(hostname) {
  const lower = hostname.toLowerCase();
  return lower.startsWith("[") && lower.endsWith("]") ? lower.slice(1, -1) : lower;
}

function isValidRelayHost(hostname) {
  if (!hostname) return false;
  const host = normalizeHost(hostname);
  if (host === "localhost") return true;
  const ipVersion = isIP(host);
  if (ipVersion === 4 || ipVersion === 6) return true;
  if (/^[0-9.]+$/.test(host)) return false;
  if (host.length > 253) return false;
  return host.split(".").every((label) =>
    label.length >= 1 && label.length <= 63 &&
    /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/.test(label));
}

export function validateProbeUrl(value) {
  const url = new URL(value);
  const local = LOOPBACK_HOSTS.has(url.hostname.toLowerCase());
  const shapeAllowed = ["/", "/ws"].includes(url.pathname) && !url.username && !url.password && !url.search && !url.hash;
  if (!shapeAllowed || !["ws:", "wss:"].includes(url.protocol) || (url.protocol === "ws:" && !local)) {
    throw new Error("probe URL must be loopback ws:// or TLS-protected wss:// without credentials or URL parameters");
  }
  return url;
}

function validateBackendUrl(value) {
  const url = new URL(value);
  if (url.protocol !== "http:" || !LOOPBACK_HOSTS.has(url.hostname.toLowerCase()) || url.username || url.password ||
      url.pathname !== "/" || url.search || url.hash) throw new Error("invalid backend URL");
}

function inspectStateDirectory(path) {
  const checks = [];
  const errors = [];
  let info;
  try {
    info = lstatSync(path);
  } catch (error) {
    if (error?.code === "ENOENT") {
      checks.push("state directory is not initialized; connector creates it with mode 0700");
      return { checks, errors };
    }
    errors.push("state directory cannot be inspected");
    return { checks, errors };
  }
  if (!info.isDirectory() || info.isSymbolicLink()) {
    errors.push("state path must be a directory and must not be a symlink");
    return { checks, errors };
  }
  if ((info.mode & 0o777) !== 0o700) errors.push("state directory mode must be 0700");
  else checks.push("state directory mode is 0700");
  for (const name of ["identity.json", "devices.json"]) {
    let file;
    try {
      file = lstatSync(join(path, name));
    } catch (error) {
      if (error?.code === "ENOENT") {
        checks.push(`${name} is absent and will be created privately when needed`);
        continue;
      }
      errors.push(`${name} cannot be inspected`);
      continue;
    }
    if (!file.isFile() || file.isSymbolicLink()) errors.push(`${name} must be a regular, non-symlink file`);
    else if ((file.mode & 0o777) !== 0o600) errors.push(`${name} mode must be 0600`);
    else checks.push(`${name} mode is 0600`);
  }
  return { checks, errors };
}

function lstatSync(path) {
  return lstatFileSync(path);
}

export function modeString(mode) {
  return (mode & 0o777).toString(8).padStart(4, "0");
}

export function tokenPresent(environment) {
  return typeof environment.ATLAS_SERVICE_TOKEN === "string" && environment.ATLAS_SERVICE_TOKEN.length > 0;
}

export function assertTokenNotInArgs(environment, args) {
  const token = environment.ATLAS_SERVICE_TOKEN;
  if (token && args.includes(token)) throw new Error("service token must not be passed as a command-line argument");
  if (args.some((argument) => /(?:^|--)(?:ATLAS_SERVICE_TOKEN|token)=/i.test(argument))) {
    throw new Error("service token must be supplied through the protected environment file");
  }
}
