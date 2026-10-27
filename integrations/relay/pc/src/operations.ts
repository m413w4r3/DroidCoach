import { open } from "node:fs/promises";
import { dirname } from "node:path";
import { createHash } from "node:crypto";
import { readPrivateJson, writePrivateJson } from "./secure-files.js";
import { BackendRequestError, BackendClient } from "./backend.js";
import { RemoteError } from "./errors.js";
import { CACHE_TTL_MS, type Operation, type RequestEnvelope, type ResponseEnvelope, PROTOCOL, isRecord, validatePayload } from "./protocol.js";

const EFFECTFUL_OPERATIONS = new Set<Operation>(["plan.revision.request"]);
const OUTCOME_UNCERTAIN_MESSAGE = "backend outcome is uncertain; retry is blocked until the 10-minute idempotency window expires";

interface CacheEntry {
  deviceId: string;
  requestId: string;
  idempotencyKey: string;
  operation: Operation;
  businessHash: string;
  requestHash: string;
  outcome: "in_flight" | "uncertain" | "completed";
  response?: ResponseEnvelope;
  expiresAt: number;
}

interface JournalDocument {
  schema_version: 1;
  entries: CacheEntry[];
}

interface PendingRequest {
  requestHash: string;
  promise: Promise<ResponseEnvelope>;
}

interface PendingIdempotency {
  businessHash: string;
  operation: Operation;
  promise: Promise<ResponseEnvelope>;
}

export interface OperationState {
  relayConnected(): boolean;
  backendConfigured(): boolean;
  uncertainOutcome?(): void;
}

export class OperationExecutor {
  private readonly requestCache = new Map<string, CacheEntry>();
  private readonly idempotencyCache = new Map<string, CacheEntry>();
  private readonly pendingRequests = new Map<string, PendingRequest>();
  private readonly pendingIdempotency = new Map<string, PendingIdempotency>();
  private journalLoad: Promise<void> | null = null;
  private journalWrite: Promise<void> = Promise.resolve();

  constructor(
    private readonly backend: BackendClient,
    private readonly state: OperationState,
    private readonly now: () => number = Date.now,
    private readonly journalPath?: string,
  ) {}

  async initialize(): Promise<void> {
    if (!this.journalPath) return;
    if (!this.journalLoad) this.journalLoad = this.loadJournal();
    await this.journalLoad;
  }

  async execute(
    deviceId: string,
    sessionId: string,
    request: RequestEnvelope,
    signal?: AbortSignal,
  ): Promise<ResponseEnvelope> {
    await this.initialize();
    validatePayload(request.operation, request.payload);
    this.cleanup();
    const requestKey = cacheKey(deviceId, request.request_id);
    const idempotencyKey = cacheKey(deviceId, request.idempotency_key);
    const businessHash = hash({ operation: request.operation, payload: request.payload });
    const requestHash = hash(request);

    const pendingRequest = this.pendingRequests.get(requestKey);
    if (pendingRequest) {
      if (pendingRequest.requestHash !== requestHash) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      const completed = await pendingRequest.promise;
      return this.replay(completed, request.request_id, sessionId);
    }
    const pendingIdempotency = this.pendingIdempotency.get(idempotencyKey);
    if (pendingIdempotency) {
      if (pendingIdempotency.businessHash !== businessHash || pendingIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      const completed = await pendingIdempotency.promise;
      const replay = this.replay(completed, request.request_id, sessionId);
      this.requestCache.set(requestKey, {
        deviceId,
        requestId: request.request_id,
        idempotencyKey: request.idempotency_key,
        operation: request.operation,
        businessHash,
        requestHash,
        outcome: "completed",
        response: replay,
        expiresAt: this.now() + CACHE_TTL_MS,
      });
      return replay;
    }

    const seenRequest = this.requestCache.get(requestKey);
    if (seenRequest) {
      if (seenRequest.requestHash !== requestHash) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      return this.responseFor(seenRequest, request.request_id, sessionId);
    }
    const seenIdempotency = this.idempotencyCache.get(idempotencyKey);
    if (seenIdempotency) {
      if (seenIdempotency.businessHash !== businessHash || seenIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      const replay = this.responseFor(seenIdempotency, request.request_id, sessionId);
      this.requestCache.set(requestKey, {
        ...seenIdempotency,
        requestId: request.request_id,
        requestHash,
        outcome: "completed",
        response: replay,
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

  resultFor(deviceId: string, requestId: string): ResponseEnvelope | undefined {
    this.cleanup();
    return this.requestCache.get(cacheKey(deviceId, requestId))?.response;
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
    const effectful = EFFECTFUL_OPERATIONS.has(request.operation);
    let journalEntry: CacheEntry | undefined;
    if (effectful) {
      journalEntry = {
        deviceId,
        requestId: request.request_id,
        idempotencyKey: request.idempotency_key,
        operation: request.operation,
        businessHash,
        requestHash,
        outcome: "in_flight",
        expiresAt: this.now() + CACHE_TTL_MS,
      };
      this.idempotencyCache.set(idempotencyKey, journalEntry);
      try {
        await this.persistJournal();
      } catch {
        this.idempotencyCache.delete(idempotencyKey);
        throw new RemoteError("INTERNAL");
      }
    }

    let result: Record<string, unknown>;
    try {
      if (request.operation === "sync.status") {
        result = {
          relay_connected: this.state.relayConnected(),
          backend_configured: this.state.backendConfigured(),
        };
      } else if (request.operation === "plan.revision.result") {
        const targetId = request.payload.request_id as string;
        const target = this.requestCache.get(cacheKey(deviceId, targetId));
        if (!target || target.expiresAt <= this.now() || !target.response) throw new RemoteError("OPERATION_UNAVAILABLE");
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
    } catch (error) {
      if (journalEntry) {
        const mayHaveOccurred = error instanceof BackendRequestError
          ? error.outcomeMayHaveOccurred
          : !(error instanceof RemoteError);
        if (mayHaveOccurred) {
          await this.rememberUncertain(idempotencyKey, journalEntry);
          throw uncertainOutcomeError();
        }
        this.idempotencyCache.delete(idempotencyKey);
        try {
          await this.persistJournal();
        } catch {
          await this.rememberUncertain(idempotencyKey, journalEntry);
          throw uncertainOutcomeError();
        }
      }
      throw error;
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
    if (Buffer.byteLength(JSON.stringify(response), "utf8") > 262_144) {
      if (journalEntry) {
        await this.rememberUncertain(idempotencyKey, journalEntry);
        throw uncertainOutcomeError();
      }
      throw new RemoteError("PAYLOAD_TOO_LARGE");
    }

    const entry: CacheEntry = {
      deviceId,
      requestId: request.request_id,
      idempotencyKey: request.idempotency_key,
      operation: request.operation,
      businessHash,
      requestHash,
      outcome: "completed",
      response,
      expiresAt: this.now() + CACHE_TTL_MS,
    };
    if (journalEntry) {
      this.idempotencyCache.set(idempotencyKey, entry);
      try {
        await this.persistJournal();
      } catch {
        const uncertainEntry: CacheEntry = { ...entry, outcome: "in_flight" };
        delete uncertainEntry.response;
        await this.rememberUncertain(idempotencyKey, uncertainEntry);
        throw uncertainOutcomeError();
      }
      this.requestCache.set(requestKey, entry);
      return response;
    }

    this.requestCache.set(requestKey, entry);
    this.idempotencyCache.set(idempotencyKey, entry);
    return response;
  }

  private replay(entry: CacheEntry | ResponseEnvelope, requestId: string, sessionId: string): ResponseEnvelope {
    if ("outcome" in entry) return this.responseFor(entry, requestId, sessionId);
    return { ...entry, request_id: requestId, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
  }

  private responseFor(entry: CacheEntry, requestId: string, sessionId: string): ResponseEnvelope {
    if (entry.outcome !== "completed" || !entry.response) throw uncertainOutcomeError();
    return { ...entry.response, request_id: requestId, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
  }

  private async rememberUncertain(key: string, entry: CacheEntry): Promise<void> {
    const firstUncertain = entry.outcome !== "uncertain";
    const uncertain: CacheEntry = { ...entry, outcome: "uncertain" };
    delete uncertain.response;
    this.idempotencyCache.set(key, uncertain);
    if (firstUncertain) this.state.uncertainOutcome?.();
    try {
      await this.persistJournal();
    } catch {
      // A durable in_flight entry is already fail-closed on restart.
    }
  }

  private async loadJournal(): Promise<void> {
    if (!this.journalPath) return;
    const raw = await readPrivateJson<unknown>(this.journalPath);
    if (raw === null) return;
    const document = validateJournal(raw);
    let mustRewrite = false;
    const now = this.now();
    for (const entry of document.entries) {
      if (entry.expiresAt <= now) {
        mustRewrite = true;
        continue;
      }
      if (entry.outcome === "in_flight") {
        entry.outcome = "uncertain";
        delete entry.response;
        mustRewrite = true;
        this.state.uncertainOutcome?.();
      }
      this.idempotencyCache.set(cacheKey(entry.deviceId, entry.idempotencyKey), entry);
      if (entry.outcome === "completed" && entry.response) {
        this.requestCache.set(cacheKey(entry.deviceId, entry.requestId), entry);
      }
    }
    if (mustRewrite) await this.persistJournal();
  }

  private persistJournal(): Promise<void> {
    if (!this.journalPath) return Promise.resolve();
    const write = this.journalWrite.catch(() => undefined).then(async () => {
      const entries = [...this.idempotencyCache.values()]
        .filter((entry) => EFFECTFUL_OPERATIONS.has(entry.operation) && entry.expiresAt > this.now())
        .map((entry) => ({ ...entry }));
      const document: JournalDocument = { schema_version: 1, entries };
      await writePrivateJson(this.journalPath!, document);
      const directory = await open(dirname(this.journalPath!), "r");
      try {
        await directory.sync();
      } finally {
        await directory.close();
      }
    });
    this.journalWrite = write;
    return write;
  }

  private cleanup(): void {
    const now = this.now();
    for (const [key, entry] of this.requestCache) if (entry.expiresAt <= now) this.requestCache.delete(key);
    for (const [key, entry] of this.idempotencyCache) if (entry.expiresAt <= now) this.idempotencyCache.delete(key);
  }
}

function validateJournal(value: unknown): JournalDocument {
  if (!isRecord(value) || value.schema_version !== 1 || !Array.isArray(value.entries)) throw new Error("invalid operation journal");
  const entries: CacheEntry[] = [];
  const seen = new Set<string>();
  for (const valueEntry of value.entries) {
    if (!isRecord(valueEntry) || typeof valueEntry.deviceId !== "string" || valueEntry.deviceId.length === 0 ||
      typeof valueEntry.requestId !== "string" || typeof valueEntry.idempotencyKey !== "string" || valueEntry.idempotencyKey.length === 0 ||
      valueEntry.operation !== "plan.revision.request" || typeof valueEntry.businessHash !== "string" || !/^[0-9a-f]{64}$/.test(valueEntry.businessHash) ||
      typeof valueEntry.requestHash !== "string" || !/^[0-9a-f]{64}$/.test(valueEntry.requestHash) ||
      !["in_flight", "uncertain", "completed"].includes(String(valueEntry.outcome)) ||
      typeof valueEntry.expiresAt !== "number" || !Number.isFinite(valueEntry.expiresAt)) {
      throw new Error("invalid operation journal");
    }
    const entry = valueEntry as unknown as CacheEntry;
    if (entry.outcome === "completed" && !isResponseEnvelope(entry.response)) throw new Error("invalid operation journal");
    if (entry.outcome !== "completed" && entry.response !== undefined) throw new Error("invalid operation journal");
    const key = cacheKey(entry.deviceId, entry.idempotencyKey);
    if (seen.has(key)) throw new Error("invalid operation journal");
    seen.add(key);
    entries.push(entry);
  }
  return { schema_version: 1, entries };
}

function isResponseEnvelope(value: unknown): value is ResponseEnvelope {
  return isRecord(value) && value.protocol === PROTOCOL && value.kind === "response" &&
    typeof value.request_id === "string" && typeof value.session_id === "string" &&
    value.operation === "plan.revision.request" && value.status === "OK" && isRecord(value.result) &&
    typeof value.completed_at === "string" && typeof value.replayed === "boolean";
}

function uncertainOutcomeError(): RemoteError {
  return new RemoteError("BACKEND_UNAVAILABLE", false, OUTCOME_UNCERTAIN_MESSAGE);
}

function cacheKey(deviceId: string, key: string): string {
  return JSON.stringify([deviceId, key]);
}

function hash(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
