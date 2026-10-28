import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import type { SecureChannel } from "@getpaseo/relay/e2ee";
import { RemoteError, asRemoteError } from "./errors.js";
import type { DeviceRecord, DeviceRegistry } from "./device-registry.js";
import type { ConnectorMetrics } from "./metrics.js";
import { OperationExecutor } from "./operations.js";
import {
  CACHE_TTL_MS,
  MAX_REQUEST_BYTES,
  MAX_RESPONSE_BYTES,
  PROTOCOL,
  SESSION_SECONDS,
  isRecord,
  parseJsonFrame,
  validateHello,
  validateRequest,
  type Capability,
  type ErrorEnvelope,
  type Hello,
  type RequestEnvelope,
} from "./protocol.js";

interface SessionState {
  relayConnected(): boolean;
  backendConfigured(): boolean;
}

interface AuthenticatedDevice {
  record: DeviceRecord;
  sessionId: string;
  capabilities: Capability[];
  expiresAt: number;
}

interface QuotaBucket {
  times: number[];
}

const REVOKE_GRACE_MS = 200;

const OPERATION_CAPABILITY: Partial<Record<string, Capability>> = {
  "coach.chat.request": "coach.chat",
  "coach.status": "coach.status",
  "plan.today.read": "plan.today",
  "plan.revision.request": "plan.revision",
  "plan.revision.result": "plan.revision",
  "sport.plan.read": "sport.plan",
  "sync.status": "sync.status",
};

export class SessionManager {
  private readonly active = new Map<string, PeerSession>();
  private readonly nonces = new Map<string, number>();
  private readonly quotas = new Map<string, QuotaBucket>();

  constructor(
    private readonly registry: DeviceRegistry,
    private readonly operations: OperationExecutor,
    private readonly metrics: ConnectorMetrics,
    private readonly state: SessionState,
    private readonly now: () => number = Date.now,
  ) {}

  create(channel: SecureChannel): PeerSession {
    if (channel.securityMode() !== "protected") {
      channel.close(1008, "protected endpoint security required");
      throw new RemoteError("AUTH_FAILED");
    }
    return new PeerSession(this, channel, this.operations, this.metrics, this.state, this.now);
  }

  async authenticate(peer: PeerSession, hello: Hello): Promise<AuthenticatedDevice> {
    const now = this.now();
    this.cleanupNonces(now);
    const timestamp = Date.parse(hello.timestamp);
    if (!Number.isFinite(timestamp) || Math.abs(now - timestamp) > 120_000) throw new RemoteError("AUTH_FAILED");
    let record: DeviceRecord | undefined;
    try {
      record = await this.registry.get(hello.device_id);
    } catch {
      throw new RemoteError("AUTH_FAILED");
    }
    if (!record) throw new RemoteError("AUTH_FAILED");
    if (record.revoked_at) throw new RemoteError("DEVICE_REVOKED");
    const rawToken = Buffer.from(hello.auth.token, "base64url");
    const suppliedHash = createHash("sha256").update(rawToken).digest();
    const expectedHash = Buffer.from(record.token_sha256, "hex");
    if (expectedHash.length !== suppliedHash.length || !timingSafeEqual(expectedHash, suppliedHash)) throw new RemoteError("AUTH_FAILED");
    const nonceKey = `${hello.device_id}:${hello.client_nonce}`;
    if (this.nonces.has(nonceKey)) throw new RemoteError("AUTH_FAILED");
    if (hello.capabilities.some((capability) => !record.capabilities.includes(capability))) throw new RemoteError("AUTH_FAILED");
    this.nonces.set(nonceKey, now + CACHE_TTL_MS);

    const prior = this.active.get(hello.device_id);
    if (prior && prior !== peer) prior.close(4001, "session replaced");
    const authenticated: AuthenticatedDevice = {
      record,
      sessionId: randomUUID(),
      capabilities: [...hello.capabilities],
      expiresAt: now + SESSION_SECONDS * 1000,
    };
    this.active.set(hello.device_id, peer);
    this.metrics.sessionOpened();
    return authenticated;
  }

  async checkActive(deviceId: string, peer: PeerSession): Promise<DeviceRecord> {
    let device: DeviceRecord | undefined;
    try {
      device = await this.registry.get(deviceId);
    } catch {
      throw new RemoteError("AUTH_REQUIRED");
    }
    if (!device || device.revoked_at) throw new RemoteError("DEVICE_REVOKED");
    if (this.active.get(deviceId) !== peer) throw new RemoteError("AUTH_REQUIRED");
    return device;
  }

  takeQuota(deviceId: string): boolean {
    const now = this.now();
    const bucket = this.quotas.get(deviceId) ?? { times: [] };
    bucket.times = bucket.times.filter((time) => now - time < 60_000);
    if (bucket.times.length >= 60) {
      this.quotas.set(deviceId, bucket);
      return false;
    }
    bucket.times.push(now);
    this.quotas.set(deviceId, bucket);
    return true;
  }

  unregister(deviceId: string, peer: PeerSession): void {
    if (this.active.get(deviceId) === peer) this.active.delete(deviceId);
  }

  async closeRevokedSessions(): Promise<void> {
    for (const [deviceId, peer] of this.active) {
      try {
        const device = await this.registry.get(deviceId);
        if (!device || device.revoked_at) await peer.revoke();
      } catch {
        peer.close(4001, "device unavailable");
      }
    }
  }

  activeSessionCount(): number {
    return this.active.size;
  }

  private cleanupNonces(now: number): void {
    for (const [key, expiresAt] of this.nonces) if (expiresAt <= now) this.nonces.delete(key);
  }
}

export class PeerSession {
  private auth: AuthenticatedDevice | null = null;
  private closed = false;
  private finished = false;
  private inFlight = new Map<string, AbortController>();
  private outstandingPing: string | null = null;
  private lastActivity = Date.now();
  private heartbeat: ReturnType<typeof setInterval>;
  private readonly openedAt: number;

  constructor(
    private readonly manager: SessionManager,
    private readonly channel: SecureChannel,
    private readonly operations: OperationExecutor,
    private readonly metrics: ConnectorMetrics,
    private readonly state: SessionState,
    private readonly now: () => number = Date.now,
  ) {
    this.openedAt = now();
    this.heartbeat = setInterval(() => void this.checkLiveness(), 20_000);
    this.heartbeat.unref?.();
    channel.onClose(() => this.finish());
  }

  async receive(data: string | ArrayBuffer): Promise<void> {
    if (this.closed) return;
    this.lastActivity = this.now();
    let currentRequestId: string | null = null;
    const frameBytes = typeof data === "string" ? Buffer.byteLength(data, "utf8") : data.byteLength;
    try {
      const value = parseJsonFrame(data);
      if (!this.auth) {
        const hello = validateHello(value);
        this.auth = await this.manager.authenticate(this, hello);
        await this.send({
          protocol: PROTOCOL,
          kind: "hello_ok",
          session_id: this.auth.sessionId,
          server_time: new Date(this.now()).toISOString(),
          expires_in_seconds: SESSION_SECONDS,
          capabilities: this.auth.capabilities,
          max_request_bytes: MAX_REQUEST_BYTES,
          max_response_bytes: MAX_RESPONSE_BYTES,
        });
        return;
      }
      await this.manager.checkActive(this.auth.record.device_id, this);
      if (this.auth.expiresAt <= this.now() || this.now() - this.openedAt >= SESSION_SECONDS * 1000) {
        throw new RemoteError("AUTH_REQUIRED");
      }
      if (!isRecord(value) || value.protocol !== PROTOCOL || typeof value.kind !== "string") throw new RemoteError("INVALID_REQUEST");
      if (value.kind === "request") {
        currentRequestId = typeof value.request_id === "string" && UUID.test(value.request_id) ? value.request_id : null;
        if (frameBytes > MAX_REQUEST_BYTES) throw new RemoteError("PAYLOAD_TOO_LARGE");
        await this.handleRequest(value);
      } else if (value.kind === "ping") {
        if (Object.keys(value).length !== 3 || typeof value.nonce !== "string" || value.nonce.length < 1 || value.nonce.length > 32) throw new RemoteError("INVALID_REQUEST");
        await this.send({ protocol: PROTOCOL, kind: "pong", nonce: value.nonce });
      } else if (value.kind === "pong") {
        if (Object.keys(value).length !== 3 || typeof value.nonce !== "string" || value.nonce !== this.outstandingPing) throw new RemoteError("INVALID_REQUEST");
        this.outstandingPing = null;
      } else if (value.kind === "cancel") {
        await this.handleCancel(value);
      } else {
        throw new RemoteError("INVALID_REQUEST");
      }
    } catch (error) {
      const remoteError = asRemoteError(error);
      this.metrics.error();
      await this.sendError(remoteError, currentRequestId);
      if (!this.auth || remoteError.code === "AUTH_REQUIRED" || remoteError.code === "DEVICE_REVOKED") this.close(4001, remoteError.message);
    }
  }

  /** Notifies the client before closing so the UI can distinguish revocation from a network error. */
  async revoke(): Promise<void> {
    if (this.closed) return;
    await this.sendError(new RemoteError("DEVICE_REVOKED"), null);
    // The relay forwards the error over the paired data connection. Closing the
    // server data socket immediately can race that forwarding; a bounded grace
    // window lets the relay route the revocation frame before the close.
    await new Promise((resolve) => setTimeout(resolve, REVOKE_GRACE_MS));
    this.close(4001, "device revoked");
  }

  close(code = 1000, reason = "closed"): void {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.heartbeat);
    for (const controller of this.inFlight.values()) controller.abort();
    this.inFlight.clear();
    if (this.auth) this.manager.unregister(this.auth.record.device_id, this);
    this.channel.close(code, reason);
    this.finish();
  }

  private async handleRequest(value: Record<string, unknown>): Promise<void> {
    const auth = this.auth;
    if (!auth) throw new RemoteError("AUTH_REQUIRED");
    const request = validateRequest(value, auth.sessionId, this.now());
    const needed = OPERATION_CAPABILITY[request.operation];
    if (needed && !auth.capabilities.includes(needed)) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (!this.manager.takeQuota(auth.record.device_id)) throw new RemoteError("RATE_LIMITED", true);
    if (this.inFlight.size >= 4) throw new RemoteError("RATE_LIMITED", true);
    if (this.inFlight.has(request.request_id)) throw new RemoteError("IDEMPOTENCY_CONFLICT");
    const controller = new AbortController();
    this.inFlight.set(request.request_id, controller);
    const startedAt = this.now();
    this.metrics.request();
    try {
      const response = await this.operations.execute(auth.record.device_id, auth.sessionId, request, controller.signal);
      if (!this.closed) await this.send(response);
    } catch (error) {
      this.metrics.error();
      if (!this.closed) await this.sendError(asRemoteError(error), request.request_id);
    } finally {
      this.metrics.latency(Math.max(0, this.now() - startedAt));
      this.inFlight.delete(request.request_id);
    }
  }

  private async handleCancel(value: Record<string, unknown>): Promise<void> {
    if (Object.keys(value).length !== 4 || value.protocol !== PROTOCOL || value.kind !== "cancel" || typeof value.request_id !== "string" || typeof value.target_request_id !== "string" || !UUID.test(value.request_id) || !UUID.test(value.target_request_id)) {
      throw new RemoteError("INVALID_REQUEST");
    }
    const controller = this.inFlight.get(value.target_request_id);
    if (controller) controller.abort();
  }

  private async checkLiveness(): Promise<void> {
    if (this.closed) return;
    if (!this.auth) {
      if (this.now() - this.lastActivity > 120_000) this.close(4001, "authentication timeout");
      return;
    }
    if (this.auth.expiresAt <= this.now()) {
      await this.sendError(new RemoteError("AUTH_REQUIRED"), null);
      this.close(4001, "session expired");
      return;
    }
    if (this.outstandingPing) {
      if (this.now() - this.lastActivity >= 40_000) this.close(4001, "heartbeat timeout");
      return;
    }
    if (this.now() - this.lastActivity >= 20_000) {
      const nonce = randomUUID().replaceAll("-", "").slice(0, 32);
      this.outstandingPing = nonce;
      await this.send({ protocol: PROTOCOL, kind: "ping", nonce });
    }
  }

  private async send(value: unknown): Promise<void> {
    const serialized = JSON.stringify(value);
    if (Buffer.byteLength(serialized, "utf8") > MAX_RESPONSE_BYTES || Buffer.byteLength(serialized, "utf8") > MAX_REQUEST_BYTES * 8) {
      throw new RemoteError("PAYLOAD_TOO_LARGE");
    }
    await this.channel.send(serialized);
  }

  private async sendError(error: RemoteError, requestId: string | null): Promise<void> {
    if (this.closed) return;
    const envelope: ErrorEnvelope = {
      protocol: PROTOCOL,
      kind: "error",
      request_id: requestId,
      session_id: this.auth?.sessionId ?? null,
      error: { code: error.code, message: error.message, retryable: error.retryable },
      completed_at: new Date(this.now()).toISOString(),
    };
    try {
      await this.channel.send(JSON.stringify(envelope));
    } catch {
      this.close(4001, "channel closed");
    }
  }

  private finish(): void {
    if (this.finished) return;
    this.finished = true;
    this.closed = true;
    clearInterval(this.heartbeat);
    if (this.auth) {
      this.manager.unregister(this.auth.record.device_id, this);
      this.metrics.sessionClosed();
    }
  }
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
