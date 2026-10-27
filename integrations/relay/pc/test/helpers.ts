import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes, randomUUID } from "node:crypto";
import type { SecureChannel } from "@getpaseo/relay/e2ee";
import { BackendClient, type FetchLike } from "../src/backend.js";
import { DeviceRegistry } from "../src/device-registry.js";
import { ConnectorMetrics } from "../src/metrics.js";
import { OperationExecutor } from "../src/operations.js";
import { PeerSession, SessionManager } from "../src/session.js";
import { PROTOCOL, type Capability, type Hello, type Operation, type RequestEnvelope } from "../src/protocol.js";

export class FakeSecureChannel implements SecureChannel {
  readonly sent: unknown[] = [];
  readonly closeEvents: Array<{ code: number; reason: string }> = [];
  private closeCallbacks: Array<() => void> = [];
  private opened = true;

  async send(data: string | ArrayBuffer): Promise<void> {
    this.sent.push(typeof data === "string" ? JSON.parse(data) as unknown : data);
  }

  close(code = 1000, reason = "closed"): void {
    if (!this.opened) return;
    this.opened = false;
    this.closeEvents.push({ code, reason });
    for (const callback of this.closeCallbacks) callback();
  }

  isOpen(): boolean {
    return this.opened;
  }

  securityMode(): "protected" {
    return "protected";
  }

  outboundWireByteLength(data: string | ArrayBuffer): number {
    return typeof data === "string" ? Buffer.byteLength(data) : data.byteLength;
  }

  onTransitionToOpen(_callback: () => void): void {}

  onClose(callback: () => void): void {
    this.closeCallbacks.push(callback);
  }

  lastMessage(): Record<string, unknown> {
    return this.sent.at(-1) as Record<string, unknown>;
  }
}

export interface TestContext {
  dir: string;
  registry: DeviceRegistry;
  metrics: ConnectorMetrics;
  manager: SessionManager;
  backendCalls: Array<{ url: string; method: string; body: string | null }>;
  dispose(): Promise<void>;
}

export async function makeContext(options: { now?: () => number; fetcher?: FetchLike; journalPath?: string } = {}): Promise<TestContext> {
  const dir = await mkdtemp(join(tmpdir(), "atlas-relay-test-"));
  const registry = new DeviceRegistry(join(dir, "devices.json"));
  const metrics = new ConnectorMetrics();
  const backendCalls: TestContext["backendCalls"] = [];
  const fetcher: FetchLike = options.fetcher ?? (async (input, init) => {
    backendCalls.push({ url: String(input), method: String(init?.method ?? "GET"), body: typeof init?.body === "string" ? init.body : null });
    return new Response(JSON.stringify({ status: "ok" }), { status: 200, headers: { "content-type": "application/json" } });
  });
  const backend = new BackendClient({ baseUrl: "http://127.0.0.1:8100", serviceToken: "synthetic-service-token", fetcher });
  const operations = new OperationExecutor(backend, { relayConnected: () => true, backendConfigured: () => true }, options.now, options.journalPath);
  const manager = new SessionManager(registry, operations, metrics, { relayConnected: () => true, backendConfigured: () => true }, options.now);
  return { dir, registry, metrics, manager, backendCalls, dispose: () => rm(dir, { recursive: true, force: true }) };
}

export async function paired(context: TestContext, capabilities: Capability[] = ["sync.status", "coach.status", "plan.today", "plan.revision", "sport.plan"]): Promise<{ deviceId: string; token: string }> {
  const result = await context.registry.pair("synthetic test device", capabilities);
  return { deviceId: result.device.device_id, token: result.token };
}

export function hello(deviceId: string, token: string, overrides: Partial<Hello> = {}): Hello {
  return {
    protocol: PROTOCOL,
    kind: "hello",
    device_id: deviceId,
    client_nonce: randomBytes(16).toString("base64"),
    client_version: "0.1.0-r3a-test",
    capabilities: ["sync.status", "coach.status", "plan.today", "plan.revision", "sport.plan"],
    auth: { scheme: "device-token-v1", token },
    timestamp: new Date().toISOString(),
    ...overrides,
  };
}

export function request(sessionId: string, operation: Operation, payload: Record<string, unknown> = {}, overrides: Partial<RequestEnvelope> = {}): RequestEnvelope {
  const now = Date.now();
  return {
    protocol: PROTOCOL,
    kind: "request",
    request_id: randomUUID(),
    session_id: sessionId,
    operation,
    idempotency_key: randomUUID(),
    created_at: new Date(now).toISOString(),
    expires_at: new Date(now + 30_000).toISOString(),
    payload,
    ...overrides,
  };
}

export async function authenticate(context: TestContext, tokenOverride?: string): Promise<{ peer: PeerSession; channel: FakeSecureChannel; deviceId: string; token: string; sessionId: string }> {
  const device = await paired(context);
  const channel = new FakeSecureChannel();
  const peer = context.manager.create(channel);
  await peer.receive(JSON.stringify(hello(device.deviceId, tokenOverride ?? device.token)));
  const helloOk = channel.lastMessage();
  return { peer, channel, deviceId: device.deviceId, token: device.token, sessionId: String(helloOk.session_id) };
}
