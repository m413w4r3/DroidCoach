import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes, randomUUID } from "node:crypto";
import WebSocket from "ws";
import {
  openClientSecureChannel,
  type SecureChannel,
  type Transport,
} from "@getpaseo/relay/e2ee";
import { afterEach, describe, expect, it } from "vitest";
import { createConnector, type ConnectorRuntime } from "../src/connector.js";
import { loadOrCreateIdentity } from "../src/identity.js";
import { PROTOCOL, type Hello, type RequestEnvelope } from "../src/protocol.js";

const RELAY_URL = process.env.ATLAS_TEST_RELAY_URL ?? "ws://127.0.0.1:4400";

class RelayClient {
  private readonly socket: WebSocket;
  private readonly inbox: Record<string, unknown>[] = [];
  private waiters: Array<{ predicate: (value: Record<string, unknown>) => boolean; resolve: (value: Record<string, unknown>) => void; reject: (error: Error) => void }> = [];
  private channel!: SecureChannel;

  private constructor(socket: WebSocket) {
    this.socket = socket;
  }

  static async connect(serverId: string, daemonPublicKeyB64: string): Promise<RelayClient> {
    const url = new URL("/ws", RELAY_URL);
    url.searchParams.set("serverId", serverId);
    url.searchParams.set("role", "client");
    url.searchParams.set("v", "2");
    const socket = new WebSocket(url);
    const client = new RelayClient(socket);
    socket.on("error", () => {});
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("client relay socket did not open")), 8_000);
      socket.once("open", () => { clearTimeout(timeout); resolve(); });
      socket.once("error", () => { clearTimeout(timeout); reject(new Error("client relay socket failed")); });
    });
    const transport: Transport = {
      onmessage: null,
      onclose: null,
      onerror: null,
      send: (data) => {
        if (socket.readyState !== WebSocket.OPEN) throw new Error("test client is closed");
        if (typeof data === "string") socket.send(data, { binary: false });
        else socket.send(Buffer.from(data), { binary: true });
      },
      close: (code = 1000, reason = "closed") => {
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(code, reason);
      },
    };
    socket.on("message", (data, isBinary) => {
      if (isBinary) {
        const copy = Uint8Array.from(data as Buffer);
        transport.onmessage?.({ data: copy.buffer, isBinary: true });
      } else {
        transport.onmessage?.({ data: data.toString("utf8"), isBinary: false });
      }
    });
    const open = new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("protected channel did not open")), 8_000);
      client.onOpen = () => { clearTimeout(timeout); resolve(); };
      client.onChannelError = () => { clearTimeout(timeout); reject(new Error("protected handshake failed")); };
    });
    client.channel = await openClientSecureChannel(transport, {
      daemonPublicKeyB64,
      minimumMode: "protected",
      events: {
        onopen: () => client.onOpen?.(),
        onmessage: (data) => client.receiveApplication(data),
        onerror: () => client.onChannelError?.(),
      },
    });
    await open;
    if (client.channel.securityMode() !== "protected") throw new Error("peer negotiated a non-protected mode");
    return client;
  }

  private onOpen: (() => void) | null = null;
  private onChannelError: (() => void) | null = null;

  private receiveApplication(data: string | ArrayBuffer): void {
    try {
      const text = typeof data === "string" ? data : new TextDecoder("utf-8", { fatal: true }).decode(data);
      const value = JSON.parse(text) as unknown;
      if (!isRecord(value)) return;
      this.inbox.push(value);
      this.flushWaiters();
    } catch {
      // The integration client does not retain unparseable application records.
    }
  }

  async send(value: unknown): Promise<void> {
    await this.channel.send(JSON.stringify(value));
  }

  async waitFor(predicate: (value: Record<string, unknown>) => boolean, timeoutMs = 8_000, label = "application response"): Promise<Record<string, unknown>> {
    const index = this.inbox.findIndex(predicate);
    if (index >= 0) return this.inbox.splice(index, 1)[0]!;
    return new Promise((resolve, reject) => {
      let timeout: ReturnType<typeof setTimeout>;
      const waiter = {
        predicate,
        resolve: (value: Record<string, unknown>) => { clearTimeout(timeout); resolve(value); },
        reject: (error: Error) => { clearTimeout(timeout); reject(error); },
      };
      timeout = setTimeout(() => {
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        const observed = this.inbox.map((message) => {
          const error = isRecord(message.error) ? message.error.code : undefined;
          return error ? `${String(message.kind)}:${String(error)}` : String(message.kind);
        }).join(",");
        reject(new Error(`${label} timeout; observed=${observed || "none"}`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  async close(): Promise<void> {
    this.channel.close(1000, "integration test complete");
    if (this.socket.readyState !== WebSocket.CLOSED) {
      await new Promise<void>((resolve) => {
        this.socket.once("close", () => resolve());
        setTimeout(resolve, 1_000).unref?.();
      });
    }
  }

  private flushWaiters(): void {
    for (const waiter of [...this.waiters]) {
      const index = this.inbox.findIndex(waiter.predicate);
      if (index < 0) continue;
      this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
      waiter.resolve(this.inbox.splice(index, 1)[0]!);
    }
  }
}

let activeRuntime: ConnectorRuntime | null = null;
let activeDir: string | null = null;

afterEach(async () => {
  await activeRuntime?.stop();
  activeRuntime = null;
  if (activeDir) await rm(activeDir, { recursive: true, force: true });
  activeDir = null;
});

describe("Paseo Relay v2 protected interoperability", () => {
  it("authenticates a Noise client over the real local relay and reconnects", async () => {
    const health = await fetch("http://127.0.0.1:4400/health");
    expect(health.ok).toBe(true);
    const dir = await mkdtemp(join(tmpdir(), "atlas-relay-real-"));
    activeDir = dir;
    const serverId = `atlas-r3a-${randomUUID()}`;
    const runtime = await createConnector({
      stateDir: dir,
      relayUrl: RELAY_URL,
      allowLoopbackWs: new URL(RELAY_URL).protocol === "ws:",
      serverId,
      backendUrl: "http://127.0.0.1:8100",
      metricsPort: 0,
    });
    activeRuntime = runtime;
    const identity = await loadOrCreateIdentity(runtime.identityPath);
    const device = await runtime.registry.pair("synthetic local Noise test", ["sync.status"]);
    await runtime.start();
    await runtime.relay.waitForControl(8_000);

    const firstClient = await RelayClient.connect(serverId, identity.identity.public_key);
    await performProtectedExchange(firstClient, device.device.device_id, device.token);
    await firstClient.close();

    await runtime.relay.stop();
    await runtime.relay.start();
    await runtime.relay.waitForControl(8_000);
    const secondClient = await RelayClient.connect(serverId, identity.identity.public_key);
    await performProtectedExchange(secondClient, device.device.device_id, device.token);
    await secondClient.close();
    expect(runtime.metrics.connectionsOpened).toBeGreaterThanOrEqual(2);
    expect(runtime.metrics.sessionsOpened).toBeGreaterThanOrEqual(2);
  }, 30_000);
});

async function performProtectedExchange(client: RelayClient, deviceId: string, token: string): Promise<void> {
  const hello: Hello = {
    protocol: PROTOCOL,
    kind: "hello",
    device_id: deviceId,
    client_nonce: randomBytes(16).toString("base64"),
    client_version: "0.1.0-r3a-node-test",
    capabilities: ["sync.status"],
    auth: { scheme: "device-token-v1", token },
    timestamp: new Date().toISOString(),
  };
  await client.send(hello);
  const helloOk = await client.waitFor((message) => message.kind === "hello_ok", 8_000, "hello_ok");
  expect(helloOk).toMatchObject({ protocol: PROTOCOL, expires_in_seconds: 3600, capabilities: ["sync.status"] });
  const sessionId = helloOk.session_id as string;
  const now = Date.now();
  const request: RequestEnvelope = {
    protocol: PROTOCOL,
    kind: "request",
    request_id: randomUUID(),
    session_id: sessionId,
    operation: "sync.status",
    idempotency_key: randomUUID(),
    created_at: new Date(now).toISOString(),
    expires_at: new Date(now + 30_000).toISOString(),
    payload: {},
  };
  await client.send(request);
  const response = await client.waitFor((message) => message.kind === "response", 8_000, "sync.status response");
  expect(response).toMatchObject({ operation: "sync.status", status: "OK", result: { relay_connected: true } });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
