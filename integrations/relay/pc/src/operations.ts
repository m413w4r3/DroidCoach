import { open } from "node:fs/promises";
import { dirname } from "node:path";
import { createHash } from "node:crypto";
import { readPrivateJson, writePrivateJson } from "./secure-files.js";
import { BackendRequestError, BackendClient } from "./backend.js";
import { ConnectorDiagnostics, type DiagnosticContext } from "./diagnostics.js";
import { RemoteError } from "./errors.js";
import { CACHE_TTL_MS, PROTOCOL_V2, PROTOCOLS, type Operation, type RequestEnvelope, type ResponseEnvelope, isRecord, isV4Operation, validateOperationPayload } from "./protocol.js";

const EFFECTFUL_OPERATIONS = new Set<Operation>([
  "plan.revision.request",
  "coach.chat.request",
  "v4.plan.snapshot.upsert",
  "v4.activity-status.events.append",
  "v4.usage.summary.upsert",
  "v4.goal.snapshot.upsert",
  "v4.training.snapshot.upsert",
  "v4.proposals.pull",
  "v4.proposals.ack",
  "v4.coach.chat.request",
  "v4.consent.state.upsert",
]);
const OUTCOME_UNCERTAIN_MESSAGE = "backend outcome is uncertain; retry is blocked until the 10-minute idempotency window expires";
const CONSENT_OPERATION: Operation = "v4.consent.state.upsert";

interface CacheEntry {
  deviceId: string;
  requestId: string;
  idempotencyKey: string;
  operation: Operation;
  businessHash: string;
  requestHash: string;
  outcome: "in_flight" | "uncertain" | "completed" | "completed_redacted";
  response?: ResponseEnvelope;
  consentReceipt?: Record<string, unknown>;
  expiresAt: number;
}

interface JournalDocument {
  schema_version: 1;
  entries: CacheEntry[];
}

interface PendingRequest {
  requestHash: string;
  operation: Operation;
  idempotencyKey: string;
  businessHash: string;
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
    readonly diagnostics: ConnectorDiagnostics = new ConnectorDiagnostics(),
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
    diagnosticContext?: DiagnosticContext,
  ): Promise<ResponseEnvelope> {
    let context = diagnosticContext;
    try {
      await this.initialize();
      validateOperationPayload(request.operation, request.payload, {
        protocol: request.protocol,
        requestId: request.request_id,
        idempotencyKey: request.idempotency_key,
      });
      context ??= this.diagnostics.enabled
        ? this.diagnostics.operationContext(0, request.request_id, request.operation)
        : undefined;
      this.diagnostics.beginOperation(context);
      const response = await this.executeValidated(deviceId, sessionId, request, signal, context);
      this.diagnostics.ready(context, "ready", "OK");
      return response;
    } catch (error) {
      if (context) this.diagnostics.ready(context, "failure", (error as { code?: unknown } | null)?.code);
      throw error;
    }
  }

  private async executeValidated(
    deviceId: string,
    sessionId: string,
    request: RequestEnvelope,
    signal: AbortSignal | undefined,
    context: DiagnosticContext | undefined,
  ): Promise<ResponseEnvelope> {
    this.cleanup();
    const requestKey = cacheKey(deviceId, request.request_id);
    const idempotencyKey = cacheKey(deviceId, request.idempotency_key);
    const businessHash = hash({ operation: request.operation, payload: request.payload });
    const requestHash = hash(request);

    const pendingRequest = this.pendingRequests.get(requestKey);
    if (pendingRequest) {
      if (!matchesRequestIdentity(pendingRequest, request, businessHash, requestHash)) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      const completed = await pendingRequest.promise;
      const replayed = this.replay(completed, request.request_id, sessionId, context);
      this.diagnostics.record(context, "replay", "replayed", { resultCode: "REPLAYED" });
      return replayed;
    }
    const pendingIdempotency = this.pendingIdempotency.get(idempotencyKey);
    if (pendingIdempotency) {
      if (pendingIdempotency.businessHash !== businessHash || pendingIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      const completed = await pendingIdempotency.promise;
      const replay = this.replay(completed, request.request_id, sessionId, context);
      this.diagnostics.record(context, "replay", "replayed", { resultCode: "REPLAYED" });
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
      if (!matchesRequestIdentity(seenRequest, request, businessHash, requestHash)) throw new RemoteError("IDEMPOTENCY_CONFLICT");
      const response = this.responseFor(seenRequest, request.request_id, sessionId, context);
      this.diagnostics.record(context, "replay", "replayed", { resultCode: "REPLAYED" });
      return response;
    }
    const seenIdempotency = this.idempotencyCache.get(idempotencyKey);
    if (seenIdempotency) {
      if (seenIdempotency.businessHash !== businessHash || seenIdempotency.operation !== request.operation) {
        throw new RemoteError("IDEMPOTENCY_CONFLICT");
      }
      if (!canReexecuteConsent(seenIdempotency)) {
        const replay = this.responseFor(seenIdempotency, request.request_id, sessionId, context);
        this.diagnostics.record(context, "replay", "replayed", { resultCode: "REPLAYED" });
        this.requestCache.set(requestKey, {
          ...seenIdempotency,
          requestId: request.request_id,
          requestHash,
          outcome: "completed",
          response: replay,
        });
        return replay;
      }
    }

    const pending = this.executeFresh(deviceId, sessionId, request, businessHash, requestHash, requestKey, idempotencyKey, signal, context);
    const pendingEntry = {
      requestHash,
      operation: request.operation,
      idempotencyKey: request.idempotency_key,
      businessHash,
      promise: pending,
    };
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
    context?: DiagnosticContext,
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
        await this.persistJournal(context, "in_flight");
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
        result = this.diagnostics.enabled
          ? await this.diagnostics.measureBackend(
            context ?? this.diagnostics.operationContext(0, request.request_id, request.operation),
            isV4Operation(request.operation),
            () => this.backend.execute(request.operation, request.payload, signal, deviceId),
          )
          : await this.backend.execute(request.operation, request.payload, signal, deviceId);
      }
    } catch (error) {
      if (journalEntry) {
        const mayHaveOccurred = error instanceof BackendRequestError
          ? error.outcomeMayHaveOccurred
          : !(error instanceof RemoteError);
        if (mayHaveOccurred) {
          await this.rememberUncertain(idempotencyKey, journalEntry, context);
          if (signal?.aborted || (error instanceof BackendRequestError && error.code === "CANCELLED")) {
            throw new RemoteError("CANCELLED");
          }
          if (error instanceof BackendRequestError && isV4Operation(request.operation) && error.upstreamStatus !== undefined) {
            throw new BackendRequestError("BACKEND_UNAVAILABLE", false, true, undefined, false, error.upstreamStatus, error.remoteCode);
          }
          if (error instanceof BackendRequestError && error.preserveResponseError) {
            throw new RemoteError(error.code);
          }
          throw uncertainOutcomeError(request.operation);
        }
        this.idempotencyCache.delete(idempotencyKey);
        try {
          await this.persistJournal(context, "removed");
        } catch {
          await this.rememberUncertain(idempotencyKey, journalEntry, context);
          throw uncertainOutcomeError(request.operation);
        }
      }
      throw error;
    }

    const completedAt = new Date(this.now()).toISOString();
    const response: ResponseEnvelope = {
      protocol: request.protocol,
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
        await this.rememberUncertain(idempotencyKey, journalEntry, context);
        throw uncertainOutcomeError(request.operation);
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
      ...(request.operation === CONSENT_OPERATION && isConsentReceipt(result) ? { consentReceipt: pickConsentReceipt(result) } : {}),
      expiresAt: this.now() + CACHE_TTL_MS,
    };
    if (journalEntry) {
      this.idempotencyCache.set(idempotencyKey, entry);
      try {
        await this.persistJournal(context, "completed");
      } catch {
        const uncertainEntry: CacheEntry = { ...entry, outcome: "in_flight" };
        delete uncertainEntry.response;
        await this.rememberUncertain(idempotencyKey, uncertainEntry, context);
        throw uncertainOutcomeError(request.operation);
      }
      this.requestCache.set(requestKey, entry);
      return response;
    }

    this.requestCache.set(requestKey, entry);
    this.idempotencyCache.set(idempotencyKey, entry);
    return response;
  }

  private replay(entry: CacheEntry | ResponseEnvelope, requestId: string, sessionId: string, context?: DiagnosticContext): ResponseEnvelope {
    if ("outcome" in entry) return this.responseFor(entry, requestId, sessionId, context);
    return { ...entry, request_id: requestId, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
  }

  private responseFor(entry: CacheEntry, requestId: string, sessionId: string, context?: DiagnosticContext): ResponseEnvelope {
    if (entry.outcome === "completed_redacted") {
      if (context) this.diagnostics.record(context, "replay", "blocked", { resultCode: "BACKEND_UNAVAILABLE" });
      throw new RemoteError("BACKEND_UNAVAILABLE", false, "completed result was redacted; retry is blocked");
    }
    if (entry.outcome !== "completed" || !entry.response) {
      if (context) this.diagnostics.record(context, "uncertain", "uncertain", { resultCode: "RESULT_UNCERTAIN" });
      throw uncertainOutcomeError(entry.operation);
    }
    return { ...entry.response, request_id: requestId, session_id: sessionId, replayed: true, completed_at: new Date(this.now()).toISOString() };
  }

  private async rememberUncertain(key: string, entry: CacheEntry, context?: DiagnosticContext): Promise<void> {
    const firstUncertain = entry.outcome !== "uncertain";
    const uncertain: CacheEntry = { ...entry, outcome: "uncertain" };
    delete uncertain.response;
    delete uncertain.consentReceipt;
    this.idempotencyCache.set(key, uncertain);
    if (firstUncertain) this.state.uncertainOutcome?.();
    const traceContext = context ?? (this.diagnostics.enabled ? this.diagnostics.sessionContext(0) : undefined);
    this.diagnostics.record(traceContext, "uncertain", "uncertain", { resultCode: "RESULT_UNCERTAIN" });
    try {
      await this.persistJournal(context, "uncertain");
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
      if (entry.outcome === "completed_redacted" && entry.operation === CONSENT_OPERATION && entry.consentReceipt) {
        entry.outcome = "completed";
        entry.response = responseFromConsentReceipt(entry);
      }
      this.idempotencyCache.set(cacheKey(entry.deviceId, entry.idempotencyKey), entry);
      if (entry.outcome === "completed" && entry.response) {
        this.requestCache.set(cacheKey(entry.deviceId, entry.requestId), entry);
      }
    }
    if (mustRewrite) await this.persistJournal(undefined, "maintenance");
  }

  private persistJournal(
    context?: DiagnosticContext,
    outcome: "in_flight" | "completed" | "uncertain" | "removed" | "maintenance" = "maintenance",
  ): Promise<void> {
    const tracing = this.diagnostics.enabled;
    const traceContext = context ?? (tracing ? this.diagnostics.sessionContext(0) : undefined);
    const startedAt = tracing ? performance.now() : 0;
    const finish = (resultCode: "PERSISTED" | "PERSIST_FAILED" | "NO_JOURNAL") => {
      if (!tracing) return;
      this.diagnostics.record(traceContext, "journal_persist_end", outcome, {
        resultCode,
        durationMs: performance.now() - startedAt,
      });
    };
    if (tracing) this.diagnostics.record(traceContext, "journal_persist_start", outcome);
    if (!this.journalPath) {
      finish("NO_JOURNAL");
      return Promise.resolve();
    }
    const write = this.journalWrite.catch(() => undefined).then(async () => {
      try {
        const entries = [...this.idempotencyCache.values()]
          .filter((entry) => EFFECTFUL_OPERATIONS.has(entry.operation) && entry.expiresAt > this.now())
          .map((entry) => {
            const persisted = { ...entry };
            if (isV4Operation(entry.operation)) {
              if (persisted.outcome === "completed") persisted.outcome = "completed_redacted";
              delete persisted.response;
              if (entry.operation !== CONSENT_OPERATION || persisted.outcome !== "completed_redacted") {
                delete persisted.consentReceipt;
              }
            }
            return persisted;
          });
        const document: JournalDocument = { schema_version: 1, entries };
        await writePrivateJson(this.journalPath!, document);
        const directory = await open(dirname(this.journalPath!), "r");
        try {
          await directory.sync();
        } finally {
          await directory.close();
        }
      } catch (error) {
        finish("PERSIST_FAILED");
        throw error;
      }
      finish("PERSISTED");
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
      typeof valueEntry.operation !== "string" || !EFFECTFUL_OPERATIONS.has(valueEntry.operation as Operation) ||
      typeof valueEntry.businessHash !== "string" || !/^[0-9a-f]{64}$/.test(valueEntry.businessHash) ||
      typeof valueEntry.requestHash !== "string" || !/^[0-9a-f]{64}$/.test(valueEntry.requestHash) ||
      !["in_flight", "uncertain", "completed", "completed_redacted"].includes(String(valueEntry.outcome)) ||
      typeof valueEntry.expiresAt !== "number" || !Number.isFinite(valueEntry.expiresAt)) {
      throw new Error("invalid operation journal");
    }
    const entry = valueEntry as unknown as CacheEntry;
    if (entry.outcome === "completed" && (!isResponseEnvelope(entry.response) || entry.response.operation !== entry.operation)) throw new Error("invalid operation journal");
    if (entry.outcome === "completed_redacted" && (!isV4Operation(entry.operation) || entry.response !== undefined)) throw new Error("invalid operation journal");
    if (entry.outcome !== "completed" && entry.outcome !== "completed_redacted" && entry.response !== undefined) throw new Error("invalid operation journal");
    if (Object.hasOwn(valueEntry, "consentReceipt") &&
      (entry.outcome !== "completed_redacted" || entry.operation !== CONSENT_OPERATION || !isConsentReceipt(entry.consentReceipt))) {
      throw new Error("invalid operation journal");
    }
    const key = cacheKey(entry.deviceId, entry.idempotencyKey);
    if (seen.has(key)) throw new Error("invalid operation journal");
    seen.add(key);
    entries.push(entry);
  }
  return { schema_version: 1, entries };
}

function canReexecuteConsent(entry: CacheEntry): boolean {
  return entry.operation === CONSENT_OPERATION &&
    (entry.outcome === "uncertain" || (entry.outcome === "completed_redacted" && entry.consentReceipt === undefined));
}

function matchesRequestIdentity(
  previous: Pick<CacheEntry, "operation" | "idempotencyKey" | "businessHash" | "requestHash">,
  request: RequestEnvelope,
  businessHash: string,
  requestHash: string,
): boolean {
  if (!isV4Operation(request.operation)) return previous.requestHash === requestHash;
  return previous.operation === request.operation &&
    previous.idempotencyKey === request.idempotency_key &&
    previous.businessHash === businessHash;
}

const CONSENT_CATEGORIES = new Set(["DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN"]);
const CONSENT_STATES = new Set(["GRANTED", "REVOKED", "UNKNOWN"]);
const CONSENT_RECEIPT_CODES = new Set(["ACCEPTED", "DUPLICATE"]);
const CONSENT_RECEIPT_KEYS = [
  "contract_version", "schema_version", "request_id", "capability", "category", "consent_version", "state",
  "updated_at", "source", "replayed", "result_code",
] as const;
const CONSENT_RECEIPT_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function isConsentReceipt(value: unknown): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  const keys = Object.keys(value);
  if (keys.length > CONSENT_RECEIPT_KEYS.length || keys.some((key) => !CONSENT_RECEIPT_KEYS.includes(key as (typeof CONSENT_RECEIPT_KEYS)[number]))) return false;
  if (!("category" in value) || !("consent_version" in value) || !("state" in value) || !("updated_at" in value) || !("result_code" in value)) return false;
  if (typeof value.category !== "string" || !CONSENT_CATEGORIES.has(value.category) ||
    typeof value.consent_version !== "number" || !Number.isSafeInteger(value.consent_version) || value.consent_version < 1 ||
    typeof value.state !== "string" || !CONSENT_STATES.has(value.state) ||
    typeof value.updated_at !== "string" || value.updated_at.length < 20 || value.updated_at.length > 40 ||
    !isValidConsentTimestamp(value.updated_at) ||
    typeof value.result_code !== "string" || !CONSENT_RECEIPT_CODES.has(value.result_code)) return false;
  if ("contract_version" in value && value.contract_version !== 4) return false;
  if ("schema_version" in value && value.schema_version !== 4) return false;
  if ("request_id" in value && (typeof value.request_id !== "string" || !CANONICAL_UUID.test(value.request_id))) return false;
  if ("capability" in value && value.capability !== "consent.state.write") return false;
  if ("source" in value && value.source !== "ANDROID_SYNC") return false;
  if ("replayed" in value && typeof value.replayed !== "boolean") return false;
  return Buffer.byteLength(JSON.stringify(value), "utf8") <= 2_048;
}

function isValidConsentTimestamp(value: string): boolean {
  if (!CONSENT_RECEIPT_TIMESTAMP.test(value) || !Number.isFinite(Date.parse(value))) return false;
  return new Date(value).toISOString().slice(0, 19) === value.slice(0, 19);
}

function pickConsentReceipt(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(CONSENT_RECEIPT_KEYS.filter((key) => key in value).map((key) => [key, value[key]]));
}

function responseFromConsentReceipt(entry: CacheEntry): ResponseEnvelope {
  const receipt = entry.consentReceipt!;
  return {
    protocol: PROTOCOL_V2,
    kind: "response",
    request_id: entry.requestId,
    session_id: "",
    operation: CONSENT_OPERATION,
    status: "OK",
    result: receipt,
    completed_at: receipt.updated_at as string,
    replayed: false,
  };
}

function isResponseEnvelope(value: unknown): value is ResponseEnvelope {
  return isRecord(value) && PROTOCOLS.includes(value.protocol as (typeof PROTOCOLS)[number]) && value.kind === "response" &&
    typeof value.request_id === "string" && typeof value.session_id === "string" &&
    typeof value.operation === "string" && EFFECTFUL_OPERATIONS.has(value.operation as Operation) &&
    value.status === "OK" && isRecord(value.result) &&
    typeof value.completed_at === "string" && typeof value.replayed === "boolean";
}

function uncertainOutcomeError(operation?: Operation): RemoteError {
  const message = operation === CONSENT_OPERATION
    ? "backend outcome is uncertain; retrying consent with the same idempotency key is safe"
    : OUTCOME_UNCERTAIN_MESSAGE;
  return new RemoteError("BACKEND_UNAVAILABLE", false, message);
}

function cacheKey(deviceId: string, key: string): string {
  return JSON.stringify([deviceId, key]);
}

function hash(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
