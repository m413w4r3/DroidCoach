import { createHash } from "node:crypto";
import { BackendClient } from "./backend.js";
import { RemoteError } from "./errors.js";
import { CACHE_TTL_MS, type Operation, type RequestEnvelope, type ResponseEnvelope, PROTOCOL, validatePayload } from "./protocol.js";

interface CacheEntry {
  deviceId: string;
  requestId: string;
  idempotencyKey: string;
  operation: Operation;
  businessHash: string;
  requestHash: string;
  response: ResponseEnvelope;
  expiresAt: number;
}

export interface OperationState {
  relayConnected(): boolean;
  backendConfigured(): boolean;
}

export class OperationExecutor {
  private readonly requestCache = new Map<string, CacheEntry>();
  private readonly idempotencyCache = new Map<string, CacheEntry>();
  private readonly pendingRequests = new Map<string, { requestHash: string; promise: Promise<ResponseEnvelope> }>();
  private readonly pendingIdempotency = new Map<string, { businessHash: string; operation: Operation; promise: Promise<ResponseEnvelope> }>();

  constructor(
    private readonly backend: BackendClient,
    private readonly state: OperationState,
    private readonly now: () => number = Date.now,
  ) {}

  async execute(
    deviceId: string,
    sessionId: string,
    request: RequestEnvelope,
    signal?: AbortSignal,
  ): Promise<ResponseEnvelope> {
    validatePayload(request.operation, request.payload);
    this.cleanup();
    const requestKey = `${deviceId}:${request.request_id}`;
    const idempotencyKey = `${deviceId}:${request.idempotency_key}`;
    const businessHash = hash({ operation: request.operation, payload: request.payload });
    const requestHash = hash(request);
    const seenRequest = this.requestCache.get(requestKey);
    if (seenRequest) {
      if (seenRequest.requestHash !== requestHash) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      return { ...seenRequest.response, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
    }
    const seenIdempotency = this.idempotencyCache.get(idempotencyKey);
    if (seenIdempotency) {
      if (seenIdempotency.businessHash !== businessHash || seenIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      const replay = { ...seenIdempotency.response, request_id: request.request_id, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
      const entry = { ...seenIdempotency, requestId: request.request_id, requestHash, response: replay };
      this.store(requestKey, idempotencyKey, entry);
      return replay;
    }

    const pendingRequest = this.pendingRequests.get(requestKey);
    if (pendingRequest) {
      if (pendingRequest.requestHash !== requestHash) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      const completed = await pendingRequest.promise;
      return { ...completed, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
    }
    const pendingIdempotency = this.pendingIdempotency.get(idempotencyKey);
    if (pendingIdempotency) {
      if (pendingIdempotency.businessHash !== businessHash || pendingIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      const completed = await pendingIdempotency.promise;
      const replay = { ...completed, request_id: request.request_id, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
      this.store(requestKey, idempotencyKey, {
        deviceId,
        requestId: request.request_id,
        idempotencyKey: request.idempotency_key,
        operation: request.operation,
        businessHash,
        requestHash,
        response: replay,
        expiresAt: this.now() + CACHE_TTL_MS,
      });
      return replay;
    }

    const pending = this.executeFresh(deviceId, sessionId, request, businessHash, requestHash, requestKey, idempotencyKey, signal);
    const pendingEntry = { requestHash, promise: pending };
    this.pendingRequests.set(requestKey, pendingEntry);
    this.pendingIdempotency.set(idempotencyKey, { businessHash, operation: request.operation, promise: pending });
    try {
      return await pending;
    } finally {
      if (this.pendingRequests.get(requestKey) === pendingEntry) this.pendingRequests.delete(requestKey);
      if (this.pendingIdempotency.get(idempotencyKey)?.promise === pending) this.pendingIdempotency.delete(idempotencyKey);
    }
  }

  private async executeFresh(
    deviceId: string,
    sessionId: string,
    request: RequestEnvelope,
    businessHash: string,
    requestHash: string,
    requestKey: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ResponseEnvelope> {

    let result: Record<string, unknown>;
    if (request.operation === "sync.status") {
      result = {
        relay_connected: this.state.relayConnected(),
        backend_configured: this.state.backendConfigured(),
      };
    } else if (request.operation === "plan.revision.result") {
      const targetId = request.payload.request_id as string;
      const target = this.requestCache.get(`${deviceId}:${targetId}`);
      if (!target || target.expiresAt <= this.now()) throw new RemoteError("OPERATION_UNAVAILABLE");
      result = {
        request_id: target.requestId,
        operation: target.operation,
        status: target.response.status,
        result: target.response.result,
        completed_at: target.response.completed_at,
      };
    } else {
      result = await this.backend.execute(request.operation, request.payload, signal);
    }

    const completedAt = new Date(this.now()).toISOString();
    const response: ResponseEnvelope = {
      protocol: PROTOCOL,
      kind: "response",
      request_id: request.request_id,
      session_id: sessionId,
      operation: request.operation,
      status: "OK",
      result,
      completed_at: completedAt,
      replayed: false,
    };
    if (Buffer.byteLength(JSON.stringify(response), "utf8") > 262_144) throw new RemoteError("PAYLOAD_TOO_LARGE");
    this.store(requestKey, idempotencyKey, {
      deviceId,
      requestId: request.request_id,
      idempotencyKey: request.idempotency_key,
      operation: request.operation,
      businessHash,
      requestHash,
      response,
      expiresAt: this.now() + CACHE_TTL_MS,
    });
    return response;
  }

  resultFor(deviceId: string, requestId: string): ResponseEnvelope | undefined {
    this.cleanup();
    return this.requestCache.get(`${deviceId}:${requestId}`)?.response;
  }

  private store(requestKey: string, idempotencyKey: string, entry: CacheEntry): void {
    this.requestCache.set(requestKey, entry);
    this.idempotencyCache.set(idempotencyKey, entry);
  }

  private cleanup(): void {
    const now = this.now();
    for (const [key, entry] of this.requestCache) if (entry.expiresAt <= now) this.requestCache.delete(key);
    for (const [key, entry] of this.idempotencyCache) if (entry.expiresAt <= now) this.idempotencyCache.delete(key);
  }
}

function hash(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
