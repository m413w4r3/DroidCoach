import { AsyncLocalStorage } from "node:async_hooks";
import { createHash } from "node:crypto";
import type { Operation } from "./protocol.js";

export const DIAGNOSTIC_BUFFER_LIMIT = 512;
const DIAGNOSTIC_STDERR_LINE_MAX_BYTES = 1_024;

export type DiagnosticOperation = Operation | "session";

export type DiagnosticStage =
  | "operation_received"
  | "backend_execute_start"
  | "backend_execute_end"
  | "core_post_start"
  | "core_post_end"
  | "journal_persist_start"
  | "journal_persist_end"
  | "response_ready"
  | "ws_send_attempt"
  | "ws_send_result"
  | "session_active"
  | "session_changed"
  | "replay"
  | "uncertain";

export type DiagnosticResult =
  | "received"
  | "started"
  | "success"
  | "failure"
  | "accepted"
  | "rejected"
  | "ready"
  | "replayed"
  | "blocked"
  | "uncertain"
  | "active"
  | "changed"
  | "in_flight"
  | "completed"
  | "removed"
  | "maintenance";

export type DiagnosticResultCode =
  | "ACCEPTED"
  | "DUPLICATE"
  | "APPLIED"
  | "USER_REJECTED"
  | "STALE_VERSION"
  | "PROPOSAL_EXPIRED"
  | "VALIDATION_FAILED"
  | "RESULT_UNCERTAIN"
  | "RECONCILED_APPLIED"
  | "RECONCILED_NOT_APPLIED"
  | "REPLAY_PENDING"
  | "CONFLICT"
  | "PERSISTED"
  | "PERSIST_FAILED"
  | "NO_JOURNAL"
  | "SEND_FAILED"
  | "SESSION_REPLACED"
  | "REPLAYED"
  | "OK"
  | "BACKEND_UNAVAILABLE"
  | "BACKEND_REJECTED"
  | "CANCELLED"
  | "OPERATION_UNAVAILABLE"
  | "IDEMPOTENCY_CONFLICT"
  | "INVALID_REQUEST"
  | "RATE_LIMITED"
  | "PAYLOAD_TOO_LARGE"
  | "INTERNAL"
  | "CONSENT_REQUIRED"
  | "AUTH_REQUIRED"
  | "CAPABILITY_UNAVAILABLE"
  | "NOT_FOUND"
  | "PERMISSION_REQUIRED"
  | "AUTH_UPSTREAM_REQUIRED"
  | "UPSTREAM_UNAVAILABLE"
  | "TIMEOUT"
  | "QUOTA_EXCEEDED"
  | "ACK_CONFLICT"
  | "ACK_ID_CONFLICT"
  | "EVENT_ID_CONFLICT"
  | "PLAN_MISMATCH"
  | "PLAN_NOT_FOUND"
  | "ACTIVITY_NOT_FOUND"
  | "DATA_UNAVAILABLE"
  | "DATA_UNKNOWN"
  | "STALE_DATA"
  | "PERIOD_TOO_LARGE"
  | "CURSOR_INVALID"
  | "CONFIG_REQUIRED"
  | "UNKNOWN_FIELD"
  | "UNSUPPORTED_VERSION"
  | "BAD_REQUEST";

export interface DiagnosticContext {
  readonly sessionOrdinal: number;
  readonly correlationTag: string;
  readonly operation: DiagnosticOperation;
}

export interface DiagnosticEvent {
  readonly timestamp: string;
  readonly sessionOrdinal: number;
  readonly correlationTag: string;
  readonly operation: DiagnosticOperation;
  readonly stage: DiagnosticStage;
  readonly result: DiagnosticResult;
  readonly httpStatus: number | null;
  readonly resultCode: DiagnosticResultCode | null;
  readonly durationMs: number | null;
}

interface BackendObservation {
  httpStatus: number | null;
}

const NO_REQUEST_TAG = "00000000";
const RESULT_CODES = new Set<DiagnosticResultCode>([
  "ACCEPTED", "DUPLICATE", "APPLIED", "USER_REJECTED", "STALE_VERSION", "PROPOSAL_EXPIRED",
  "VALIDATION_FAILED", "RESULT_UNCERTAIN", "RECONCILED_APPLIED", "RECONCILED_NOT_APPLIED",
  "REPLAY_PENDING", "CONFLICT", "PERSISTED", "PERSIST_FAILED", "NO_JOURNAL", "SEND_FAILED",
  "SESSION_REPLACED", "REPLAYED", "OK", "BACKEND_UNAVAILABLE", "BACKEND_REJECTED", "CANCELLED",
  "OPERATION_UNAVAILABLE", "IDEMPOTENCY_CONFLICT", "INVALID_REQUEST", "RATE_LIMITED",
  "PAYLOAD_TOO_LARGE", "INTERNAL", "CONSENT_REQUIRED", "AUTH_REQUIRED", "CAPABILITY_UNAVAILABLE",
  "NOT_FOUND", "PERMISSION_REQUIRED", "AUTH_UPSTREAM_REQUIRED", "UPSTREAM_UNAVAILABLE", "TIMEOUT",
  "QUOTA_EXCEEDED", "ACK_CONFLICT", "ACK_ID_CONFLICT", "EVENT_ID_CONFLICT", "PLAN_MISMATCH",
  "PLAN_NOT_FOUND", "ACTIVITY_NOT_FOUND", "DATA_UNAVAILABLE", "DATA_UNKNOWN", "STALE_DATA",
  "PERIOD_TOO_LARGE", "CURSOR_INVALID", "CONFIG_REQUIRED", "UNKNOWN_FIELD", "UNSUPPORTED_VERSION",
  "BAD_REQUEST",
]);

/** Bounded in-memory trace. Runtime construction reads only ATLAS_DIAG_TRACE=1. */
export class ConnectorDiagnostics {
  readonly enabled: boolean;
  private readonly events: DiagnosticEvent[] = [];
  private readonly contextStartedAt = new WeakMap<DiagnosticContext, number>();
  private readonly begun = new WeakSet<DiagnosticContext>();
  private readonly responseReady = new WeakSet<DiagnosticContext>();
  private readonly backendObservation = new AsyncLocalStorage<BackendObservation>();
  private sessionOrdinal = 0;

  constructor() {
    this.enabled = process.env.ATLAS_DIAG_TRACE === "1";
  }

  nextSessionOrdinal(): number {
    this.sessionOrdinal += 1;
    return this.sessionOrdinal;
  }

  sessionContext(sessionOrdinal: number): DiagnosticContext {
    return Object.freeze({ sessionOrdinal, correlationTag: NO_REQUEST_TAG, operation: "session" });
  }

  operationContext(sessionOrdinal: number, requestId: string, operation: Operation): DiagnosticContext {
    let correlationTag = NO_REQUEST_TAG;
    if (this.enabled) {
      try {
        correlationTag = createHash("sha256").update(requestId, "utf8").digest("hex").slice(0, 8);
      } catch {
        // The trace is best effort and must never affect operation handling.
      }
    }
    const context = Object.freeze({ sessionOrdinal, correlationTag, operation });
    if (this.enabled) this.contextStartedAt.set(context, performance.now());
    return context;
  }

  beginOperation(context: DiagnosticContext | undefined): void {
    if (!this.enabled || !context || this.begun.has(context)) return;
    this.begun.add(context);
    this.record(context, "operation_received", "received");
  }

  ready(context: DiagnosticContext | undefined, result: "ready" | "failure", resultCode?: unknown): void {
    if (!this.enabled || !context || this.responseReady.has(context)) return;
    this.responseReady.add(context);
    this.record(context, "response_ready", result, {
      resultCode: resultCode ?? (result === "ready" ? "OK" : undefined),
      durationMs: this.elapsed(context),
    });
  }

  record(
    context: DiagnosticContext | undefined,
    stage: DiagnosticStage,
    result: DiagnosticResult,
    details: { httpStatus?: unknown; resultCode?: unknown; durationMs?: unknown } = {},
  ): void {
    if (!this.enabled || !context) return;
    try {
      const event: DiagnosticEvent = Object.freeze({
        timestamp: new Date().toISOString(),
        sessionOrdinal: finiteOrdinal(context.sessionOrdinal),
        correlationTag: /^[0-9a-f]{8}$/.test(context.correlationTag) ? context.correlationTag : NO_REQUEST_TAG,
        operation: context.operation,
        stage,
        result,
        httpStatus: safeHttpStatus(details.httpStatus),
        resultCode: safeResultCode(details.resultCode),
        durationMs: safeDuration(details.durationMs),
      });
      this.events.push(event);
      if (this.events.length > DIAGNOSTIC_BUFFER_LIMIT) this.events.shift();
      const line = `${JSON.stringify(event)}\n`;
      if (Buffer.byteLength(line, "utf8") <= DIAGNOSTIC_STDERR_LINE_MAX_BYTES) {
        process.stderr.write(line, () => undefined);
      }
    } catch {
      // Diagnostic failures must not change connector behavior.
    }
  }

  async measureBackend<T>(context: DiagnosticContext | undefined, isCorePost: boolean, execute: () => Promise<T>): Promise<T> {
    if (!this.enabled) return execute();
    const traceContext = context ?? this.sessionContext(0);
    const start = performance.now();
    const observation: BackendObservation = { httpStatus: null };
    const startStage = isCorePost ? "core_post_start" : "backend_execute_start";
    const endStage = isCorePost ? "core_post_end" : "backend_execute_end";
    this.record(traceContext, startStage, "started");
    try {
      const result = await this.backendObservation.run(observation, execute);
      this.record(traceContext, endStage, "success", {
        httpStatus: observation.httpStatus,
        resultCode: resultCodeFrom(result),
        durationMs: performance.now() - start,
      });
      return result;
    } catch (error) {
      this.record(traceContext, endStage, "failure", {
        httpStatus: observation.httpStatus ?? statusFromError(error),
        resultCode: resultCodeFrom(error),
        durationMs: performance.now() - start,
      });
      throw error;
    }
  }

  observeHttpStatus(status: number): void {
    const observation = this.backendObservation.getStore();
    if (!observation || !this.enabled) return;
    observation.httpStatus = safeHttpStatus(status);
  }

  elapsed(context: DiagnosticContext | undefined): number | null {
    if (!this.enabled || !context) return null;
    const start = this.contextStartedAt.get(context);
    return start === undefined ? null : performance.now() - start;
  }

  snapshot(): readonly DiagnosticEvent[] {
    return Object.freeze(this.events.map((event) => Object.freeze({ ...event })));
  }
}

function finiteOrdinal(value: number): number {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function safeHttpStatus(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) && value >= 100 && value <= 599 ? value : null;
}

function safeDuration(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return Math.min(86_400_000, Math.max(0, Math.round(value)));
}

function safeResultCode(value: unknown): DiagnosticResultCode | null {
  return typeof value === "string" && RESULT_CODES.has(value as DiagnosticResultCode)
    ? value as DiagnosticResultCode
    : null;
}

function resultCodeFrom(value: unknown): DiagnosticResultCode | null {
  if (typeof value !== "object" || value === null) return null;
  const record = value as Record<string, unknown>;
  return safeResultCode(record.result_code ?? record.remoteCode ?? record.code);
}

function statusFromError(error: unknown): number | null {
  if (typeof error !== "object" || error === null) return null;
  return safeHttpStatus((error as Record<string, unknown>).upstreamStatus);
}
