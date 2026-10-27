import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { connect as tcpConnect } from "node:net";
import { connect as tlsConnect } from "node:tls";
import WebSocket from "ws";
import { validateProbeUrl } from "./lib/config.mjs";

const input = process.argv[2] ?? process.env.ATLAS_RELAY_URL ?? "ws://127.0.0.1:4400";
let url;
try {
  if (process.argv.length > 3) throw new Error("usage: diagnose-network.mjs [WS_OR_WSS_URL]");
  url = validateProbeUrl(input);
} catch {
  process.stderr.write("FAIL URL policy: use loopback ws:// or a TLS-protected wss:// URL without credentials, query, or fragment\n");
  process.exit(2);
}

const host = url.hostname.replace(/^\[|\]$/g, "");
const port = Number(url.port || (url.protocol === "wss:" ? 443 : 80));
let addresses;
try {
  addresses = isIP(host) ? [{ address: host }] : await lookup(host, { all: true, verbatim: true });
  process.stdout.write(`PASS DNS resolution (${addresses.length} address(es))\n`);
} catch {
  process.stderr.write("FAIL DNS resolution\n");
  process.exit(1);
}

try {
  await testTcp(host, port);
  process.stdout.write("PASS TCP reachability\n");
} catch {
  process.stderr.write("FAIL TCP reachability\n");
  process.exit(1);
}

if (url.protocol === "wss:") {
  try {
    await testTls(host, port);
    process.stdout.write("PASS TLS certificate and hostname validation\n");
  } catch {
    process.stderr.write("FAIL TLS certificate or hostname validation\n");
    process.exit(1);
  }
}

try {
  await testWebSocket(url);
  process.stdout.write("PASS WebSocket upgrade and clean close\n");
} catch {
  process.stderr.write("FAIL WebSocket upgrade or clean close\n");
  process.exit(1);
}

function testTcp(hostname, targetPort) {
  return new Promise((resolve, reject) => {
    const socket = tcpConnect({ host: hostname, port: targetPort });
    const timer = setTimeout(() => socket.destroy(new Error("timeout")), 5_000);
    socket.once("connect", () => { clearTimeout(timer); socket.end(); resolve(); });
    socket.once("error", (error) => { clearTimeout(timer); reject(error); });
  });
}

function testTls(hostname, targetPort) {
  return new Promise((resolve, reject) => {
    const options = { host: hostname, port: targetPort, rejectUnauthorized: true };
    if (!isIP(hostname)) options.servername = hostname;
    const socket = tlsConnect(options);
    const timer = setTimeout(() => socket.destroy(new Error("timeout")), 5_000);
    socket.once("secureConnect", () => {
      clearTimeout(timer);
      if (!socket.authorized) { socket.destroy(); reject(new Error("unauthorized")); return; }
      socket.end();
      resolve();
    });
    socket.once("error", (error) => { clearTimeout(timer); reject(error); });
  });
}

function testWebSocket(baseUrl) {
  return new Promise((resolve, reject) => {
    const socketUrl = new URL("/ws", baseUrl);
    socketUrl.searchParams.set("serverId", "atlas-network-diagnostic");
    socketUrl.searchParams.set("role", "server");
    socketUrl.searchParams.set("v", "2");
    const socket = new WebSocket(socketUrl, { handshakeTimeout: 5_000, maxPayload: 32 * 1024 });
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) { socket.terminate(); reject(error); }
      else resolve();
    };
    const timer = setTimeout(() => finish(new Error("timeout")), 6_000);
    socket.once("open", () => { socket.close(1000, "diagnostic complete"); });
    socket.once("close", (code) => code === 1000 ? finish() : finish(new Error("unexpected close")));
    socket.once("error", () => finish(new Error("websocket error")));
  });
}
