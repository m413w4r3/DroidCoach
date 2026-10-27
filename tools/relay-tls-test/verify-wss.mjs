import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { once } from "node:events";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import dns from "node:dns";
import net from "node:net";
import { homedir } from "node:os";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { RelayLink, parseRelayUrl } from "../../integrations/relay/pc/dist/relay-link.js";
import { ConnectorMetrics } from "../../integrations/relay/pc/dist/metrics.js";

const require = createRequire(new URL("../../integrations/relay/pc/package.json", import.meta.url));
const WebSocket = require("ws");
const { generateKeyPair } = require("@getpaseo/relay/e2ee");
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const pkiDir = join(homedir(), ".local", "share", "atlas-v63-r3a-plus", "pki");
const testCa = await readFile(join(repoRoot, "android/app/src/debug/res/raw/atlas_test_ca.pem"));
const lookup = explicitTestLookup;
const proxies = [];
let fastApiProbe;
let relayLink;
let passed = 0;

try {
  const health = await fetch("http://127.0.0.1:4400/health");
  assert.equal(health.status, 200, "the real Paseo Relay must answer on 127.0.0.1:4400");

  fastApiProbe = await startFastApiProbe();
  const relayProxy = await startProxy("relay-cert.pem", "relay-key.pem");
  proxies.push(relayProxy);
  const wrongHostProxy = await startProxy("wrong-host-cert.pem", "wrong-host-key.pem");
  proxies.push(wrongHostProxy);
  const untrustedProxy = await startProxy("untrusted-cert.pem", "untrusted-key.pem");
  proxies.push(untrustedProxy);

  assert.equal(parseRelayUrl("wss://relay.atlas.test:4443").protocol, "wss:");
  assert.throws(() => parseRelayUrl("ws://relay.atlas.test:4400", { allowLoopbackWs: false }));
  assert.equal(parseRelayUrl("ws://127.0.0.1:4400", { allowLoopbackWs: true }).protocol, "ws:");
  pass("remote ws policy rejects cleartext and allows explicit loopback development opt-in");

  const connected = await openWebSocket(relayUrl(relayProxy.port, "client"), { ca: testCa, lookup, followRedirects: false });
  await closeWebSocket(connected);
  pass("WSS accepts the explicitly trusted local CA and reaches Paseo Relay");

  const wrongHostError = await expectTlsFailure(
    relayUrl(wrongHostProxy.port, "client"),
    { ca: testCa, lookup, followRedirects: false },
  );
  assert.match(`${wrongHostError.code ?? ""} ${wrongHostError.message}`, /ALTNAME|hostname|subject alternative/i);
  pass("WSS refuses a trusted certificate with the wrong hostname");

  const untrustedError = await expectTlsFailure(
    relayUrl(untrustedProxy.port, "client"),
    { lookup, followRedirects: false },
  );
  assert.match(`${untrustedError.code ?? ""} ${untrustedError.message}`, /CERT|SELF_SIGNED|VERIFY|issuer/i);
  pass("WSS refuses a certificate outside the system and test trust stores");

  await provePlainHttpCannotReachFastApi(relayProxy.port, fastApiProbe);
  pass("plain HTTP sent to the TLS proxy never reaches the 127.0.0.1:8100 sentinel");

  relayLink = new RelayLink({
    relayUrl: `wss://relay.atlas.test:${relayProxy.port}`,
    serverId: `atlas-wss-harness-${Date.now()}`,
    daemonKeyPair: generateKeyPair(),
    sessions: {},
    metrics: new ConnectorMetrics(),
    testOnlyTlsOptions: { ca: testCa, lookup },
    reconnectMinMs: 100,
    reconnectMaxMs: 500,
  });
  await relayLink.start();
  await relayLink.waitForControl(8_000);
  assert.equal(relayLink.connected, true);
  process.kill(relayProxy.child.pid, "SIGUSR1");
  await relayLink.waitForState(false, 5_000);
  await relayLink.waitForState(true, 8_000);
  assert.equal(relayLink.connected, true);
  pass("an interrupted TLS stream disconnects and reconnects over WSS");
} finally {
  await relayLink?.stop();
  if (fastApiProbe) await closeFastApiProbe(fastApiProbe.server);
  for (const proxy of proxies.reverse()) await stopProxy(proxy.child);
}

console.log(`PASS ${passed} WSS harness checks`);

function pass(message) {
  passed += 1;
  console.log(`PASS ${message}`);
}

function relayUrl(port, role) {
  const url = new URL(`wss://relay.atlas.test:${port}/ws`);
  url.searchParams.set("serverId", `atlas-wss-test-${Date.now()}`);
  url.searchParams.set("role", role);
  url.searchParams.set("v", "2");
  return url.toString();
}

function explicitTestLookup(hostname, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = {};
  }
  if (new Set(["relay.atlas.test", "wrong-host.atlas.test", "untrusted.atlas.test"]).has(hostname.toLowerCase())) {
    if (options?.all) callback(null, [{ address: "127.0.0.1", family: 4 }]);
    else callback(null, "127.0.0.1", 4);
    return;
  }
  if (options === undefined) dns.lookup(hostname, callback);
  else dns.lookup(hostname, options, callback);
}

async function startProxy(certName, keyName) {
  const proxyScript = join(repoRoot, "tools/relay-tls-test/tls-relay-proxy.py");
  const child = spawn("python3", [proxyScript, "--port", "0", "--cert", join(pkiDir, certName), "--key", join(pkiDir, keyName)], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.setEncoding("utf8").on("data", (part) => { stderr = `${stderr}${part}`.slice(-2_000); });
  const lines = createInterface({ input: child.stdout });
  return new Promise((resolveProxy, rejectProxy) => {
    let ready = false;
    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      rejectProxy(new Error(`TLS proxy startup timeout: ${stderr}`));
    }, 5_000);
    lines.on("line", (line) => {
      if (!line.startsWith("READY 127.0.0.1:")) return;
      ready = true;
      clearTimeout(timeout);
      resolveProxy({ child, port: Number(line.slice(line.lastIndexOf(":") + 1)) });
    });
    child.once("error", (error) => {
      clearTimeout(timeout);
      rejectProxy(error);
    });
    child.once("exit", (code) => {
      if (ready) return;
      clearTimeout(timeout);
      rejectProxy(new Error(`TLS proxy exited before readiness (${code}): ${stderr}`));
    });
  });
}

async function stopProxy(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, "exit").then(() => true);
  child.kill("SIGTERM");
  let timeout;
  const stopped = await Promise.race([
    exited,
    new Promise((resolveStopped) => { timeout = setTimeout(() => resolveStopped(false), 1_000); }),
  ]);
  clearTimeout(timeout);
  if (!stopped && child.exitCode === null && child.signalCode === null) {
    child.kill("SIGKILL");
    await exited;
  }
}

function openWebSocket(url, options) {
  const socket = new WebSocket(url, options);
  return new Promise((resolveSocket, rejectSocket) => {
    const timeout = setTimeout(() => {
      socket.terminate();
      rejectSocket(new Error("WSS open timed out"));
    }, 5_000);
    socket.once("open", () => {
      clearTimeout(timeout);
      resolveSocket(socket);
    });
    socket.once("error", (error) => {
      clearTimeout(timeout);
      rejectSocket(error);
    });
  });
}

function expectTlsFailure(url, options) {
  const socket = new WebSocket(url, options);
  return new Promise((resolveError, rejectUnexpectedOpen) => {
    const timeout = setTimeout(() => {
      socket.terminate();
      rejectUnexpectedOpen(new Error("TLS rejection timed out"));
    }, 5_000);
    socket.once("open", () => {
      clearTimeout(timeout);
      socket.close();
      rejectUnexpectedOpen(new Error("TLS unexpectedly opened the WebSocket"));
    });
    socket.once("error", (error) => {
      clearTimeout(timeout);
      resolveError(error);
    });
  });
}

function closeWebSocket(socket) {
  return new Promise((resolveClose) => {
    if (socket.readyState === WebSocket.CLOSED) return resolveClose();
    socket.once("close", resolveClose);
    socket.close(1000, "WSS harness check complete");
  });
}

async function startFastApiProbe() {
  let reached = false;
  const server = net.createServer((socket) => {
    socket.on("data", () => { reached = true; });
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(8100, "127.0.0.1", resolveListen);
  });
  return { server, wasReached: () => reached };
}

function closeFastApiProbe(server) {
  return new Promise((resolveClose) => server.close(() => resolveClose()));
}

async function provePlainHttpCannotReachFastApi(proxyPort, probe) {
  await new Promise((resolveRequest) => {
    const socket = net.createConnection({ host: "127.0.0.1", port: proxyPort });
    const timeout = setTimeout(() => {
      socket.destroy();
      resolveRequest();
    }, 2_000);
    socket.once("connect", () => socket.write("GET /health HTTP/1.1\r\nHost: 127.0.0.1:8100\r\nConnection: close\r\n\r\n"));
    socket.once("close", () => { clearTimeout(timeout); resolveRequest(); });
    socket.once("error", () => { clearTimeout(timeout); resolveRequest(); });
  });
  await new Promise((resolveWindow) => setTimeout(resolveWindow, 200));
  assert.equal(probe.wasReached(), false);
}
