import { RemoteError } from "./errors.js";
import { assertCoachChatResponse, assertSmallResponse, isRecord, isV4Operation, type Operation, validatePayload } from "./protocol.js";

export type FetchLike = typeof fetch;

export interface BackendConfig {
  baseUrl: string;
  serviceToken?: string;
  timeoutMs?: number;
  chatTimeoutMs?: number;
  /** V4 stays disabled in the production connector until Relay-to-Core identity binding is proven. */
  v4Enabled?: boolean;
  /** Resolves a paired Relay device to its distinct Core Android bearer; never read from payload data. */
  pairedDeviceToken?: (pairedDeviceId: string) => string | undefined;
  fetcher?: FetchLike;
}

/** A backend error with an explicit statement about whether a POST may have taken effect. */
export class BackendRequestError extends RemoteError {
  constructor(
    code: ConstructorParameters<typeof RemoteError>[0],
    retryable: boolean,
    readonly outcomeMayHaveOccurred: boolean,
    message?: string,
    readonly preserveResponseError = false,
    readonly upstreamStatus?: number,
    readonly remoteCode?: string,
  ) {
    super(code, retryable, message);
  }
}

const V4_REMOTE_ERROR_CODES = new Set([
  "BAD_REQUEST",
  "UNKNOWN_FIELD",
  "UNSUPPORTED_VERSION",
  "CAPABILITY_UNAVAILABLE",
  "AUTH_REQUIRED",
  "CONSENT_REQUIRED",
  "PERMISSION_REQUIRED",
  "NOT_FOUND",
  "PLAN_NOT_FOUND",
  "ACTIVITY_NOT_FOUND",
  "PLAN_MISMATCH",
  "DATA_UNAVAILABLE",
  "DATA_UNKNOWN",
  "STALE_DATA",
  "STALE_VERSION",
  "CONFLICT",
  "VALIDATION_FAILED",
  "IDEMPOTENCY_CONFLICT",
  "EVENT_ID_CONFLICT",
  "ACK_ID_CONFLICT",
  "ACK_CONFLICT",
  "PROPOSAL_EXPIRED",
  "REPLAY_PENDING",
  "RESULT_UNCERTAIN",
  "QUOTA_EXCEEDED",
  "RATE_LIMITED",
  "PERIOD_TOO_LARGE",
  "CURSOR_INVALID",
  "CONFIG_REQUIRED",
  "AUTH_UPSTREAM_REQUIRED",
  "UPSTREAM_UNAVAILABLE",
  "TIMEOUT",
  "PAYLOAD_TOO_LARGE",
  "INTERNAL",
]);

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);
const DEFAULT_CHAT_TIMEOUT_MS = 55_000;

export function assertLoopbackHttpUrl(value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("backend URL must be an absolute loopback HTTP URL");
  }
  if (url.protocol !== "http:" || !LOOPBACK_HOSTS.has(url.hostname.toLowerCase()) || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("backend URL must be an absolute loopback HTTP URL");
  }
  return url;
}

export class BackendClient {
  readonly baseUrl: URL;
  private readonly fetcher: FetchLike;
  private readonly timeoutMs: number;
  private readonly chatTimeoutMs: number;

  constructor(private readonly config: BackendConfig) {
    this.baseUrl = assertLoopbackHttpUrl(config.baseUrl);
    this.fetcher = config.fetcher ?? fetch;
    this.timeoutMs = config.timeoutMs ?? 5_000;
    this.chatTimeoutMs = config.chatTimeoutMs ?? DEFAULT_CHAT_TIMEOUT_MS;
    if (!Number.isInteger(this.chatTimeoutMs) || this.chatTimeoutMs < 1 || this.chatTimeoutMs > DEFAULT_CHAT_TIMEOUT_MS) {
      throw new Error(`chat timeout must be an integer between 1 and ${DEFAULT_CHAT_TIMEOUT_MS} ms`);
    }
  }

  async execute(operation: Operation, payload: Record<string, unknown>, signal?: AbortSignal, pairedDeviceId?: string): Promise<Record<string, unknown>> {
    const v4Operation = isV4Operation(operation);
    if (v4Operation) validatePayload(operation, payload);
    if (v4Operation && (!this.config.v4Enabled || !pairedDeviceId || !this.config.pairedDeviceToken)) {
      throw new RemoteError("OPERATION_UNAVAILABLE");
    }
    const deviceToken = v4Operation ? this.config.pairedDeviceToken?.(pairedDeviceId!) : undefined;
    if (v4Operation && !deviceToken) throw new RemoteError("OPERATION_UNAVAILABLE");
    const route = routeFor(operation, payload);
    if (!route) throw new RemoteError("OPERATION_UNAVAILABLE");
    const bearerToken = route.v4 ? deviceToken : this.config.serviceToken;
    if (route.method !== "GET" && !bearerToken) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (route.auth && !bearerToken) throw new RemoteError("OPERATION_UNAVAILABLE");
    const headers: Record<string, string> = { Accept: "application/json" };
    if (route.auth && bearerToken) headers.Authorization = `Bearer ${bearerToken}`;
    if (route.v4) {
      headers["X-Atlas-Contract-Version"] = "4";
      headers["X-Atlas-Schema-Version"] = "4";
      headers["X-Atlas-Capability"] = route.v4.capability;
      headers["X-Atlas-Request-Id"] = route.v4.requestId;
    }
    if (route.body) headers["Content-Type"] = "application/json";
    const timeoutMs = operation === "coach.chat.request" || operation === "v4.coach.chat.request" ? this.chatTimeoutMs : this.timeoutMs;
    const timeoutSignal = AbortSignal.timeout(timeoutMs);
    const combinedSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal;
    const init: RequestInit = { method: route.method, headers, signal: combinedSignal, redirect: "error" };
    if (route.body) init.body = JSON.stringify(route.body);
    let response: Response;
    try {
      response = await this.fetcher(new URL(route.path, this.baseUrl), init);
    } catch (error) {
      if (signal?.aborted) {
        throw new BackendRequestError("CANCELLED", false, route.method === "POST");
      }
      throw new BackendRequestError("BACKEND_UNAVAILABLE", route.method === "GET", route.method === "POST");
    }
    if (response.status === 404 && !v4Operation) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (!response.ok) {
      const details = v4Operation ? await readV4ErrorDetails(response) : {};
      const mayHaveOccurred = route.method === "POST" && (response.status >= 500 || response.status === 408) && !details.remoteCode;
      throw new BackendRequestError(
        "BACKEND_REJECTED",
        details.retryable ?? response.status >= 500,
        mayHaveOccurred,
        undefined,
        false,
        v4Operation ? response.status : undefined,
        details.remoteCode,
      );
    }
    try {
      const result = await readBoundedJson(response);
      if (operation === "backend.health") {
        return { status: result.status === "ok" ? "ok" : "unavailable" };
      }
      assertSmallResponse(result);
      if (operation === "coach.chat.request") assertCoachChatResponse(result);
      return result;
    } catch (error) {
      if (route.method === "POST" && error instanceof RemoteError) {
        throw new BackendRequestError(error.code, error.retryable, true, error.message, operation === "coach.chat.request");
      }
      throw error;
    }
  }
}

interface V4ErrorDetails {
  remoteCode?: string;
  retryable?: boolean;
}

async function readV4ErrorDetails(response: Response): Promise<V4ErrorDetails> {
  const maxBytes = 16_384;
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > maxBytes) {
    try {
      await response.body?.cancel();
    } catch {
      // The upstream body is deliberately discarded when it exceeds the parsing bound.
    }
    return {};
  }
  if (!response.body) return {};

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return {};
      }
      chunks.push(value);
    }
  } catch {
    return {};
  } finally {
    reader.releaseLock();
  }

  try {
    const parsed: unknown = JSON.parse(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).toString("utf8"));
    if (!isRecord(parsed)) return {};
    const error = isRecord(parsed.error) ? parsed.error : parsed;
    const remoteCode = typeof error.code === "string" && V4_REMOTE_ERROR_CODES.has(error.code) ? error.code : undefined;
    const details: V4ErrorDetails = {};
    if (remoteCode) details.remoteCode = remoteCode;
    if (remoteCode && typeof error.retryable === "boolean") details.retryable = error.retryable;
    return details;
  } catch {
    return {};
  }
}

interface Route {
  method: "GET" | "POST";
  path: string;
  auth: boolean;
  body?: Record<string, unknown>;
  v4?: { capability: string; requestId: string };
}

function routeFor(operation: Operation, payload: Record<string, unknown>): Route | null {
  switch (operation) {
    case "backend.health":
      return { method: "GET", path: "/health", auth: false };
    case "coach.status":
      return { method: "GET", path: "/v2/agent/status", auth: true };
    case "coach.chat.request":
      return { method: "POST", path: "/v2/agent/chat", auth: true, body: payload };
    case "plan.today.read":
      return { method: "GET", path: "/v1/plan/today", auth: true };
    case "plan.revision.request":
      return { method: "POST", path: "/v3/agent/plan-revision", auth: true, body: payload };
    case "sport.plan.read": {
      const date = encodeURIComponent(String(payload.local_date));
      const timezone = encodeURIComponent(String(payload.timezone));
      return { method: "GET", path: `/v1/sport/plan?local_date=${date}&timezone=${timezone}`, auth: true };
    }
    case "plan.revision.result":
    case "sync.status":
      return null;
    case "v4.plan.snapshot.upsert":
      return v4Post(payload, "/v4/sync/day-plan-snapshots", "day_plan.snapshot.write");
    case "v4.activity-status.events.append":
      return v4Post(payload, "/v4/sync/activity-status-events", "activity_status.write");
    case "v4.usage.summary.upsert":
      return v4Post(payload, "/v4/sync/usage-summaries", "usage_summary.write");
    case "v4.goal.snapshot.upsert":
      return v4Post(payload, "/v4/sync/goal-progress-snapshots", "goal_progress.write");
    case "v4.training.snapshot.upsert":
      return v4Post(payload, "/v4/sync/training-snapshots", "training_plan.write");
    case "v4.proposals.pull":
      return v4Post(payload, "/v4/agent/proposals/pull", "proposal.ack");
    case "v4.proposals.ack":
      return v4Post(payload, `/v4/agent/proposals/${encodeURIComponent(String(payload.proposal_id))}/ack`, "proposal.ack");
    case "v4.coach.chat.request":
      return v4Post(payload, "/v4/agent/chat", "coach.chat");
    case "v4.consent.state.upsert":
      return v4Post(payload, "/v4/sync/consent-state", "consent.state.write");
  }
}

function v4Post(payload: Record<string, unknown>, path: string, capability: string): Route {
  const requestId = payload.request_id ?? payload.ack_id;
  return {
    method: "POST",
    path,
    auth: true,
    body: payload,
    v4: { capability, requestId: String(requestId) },
  };
}

async function readBoundedJson(response: Response): Promise<Record<string, unknown>> {
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > 262_144) throw new RemoteError("PAYLOAD_TOO_LARGE");
  if (!response.body) throw new RemoteError("BACKEND_REJECTED");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > 262_144) {
        await reader.cancel();
        throw new RemoteError("PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  let value: unknown;
  try {
    value = JSON.parse(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).toString("utf8")) as unknown;
  } catch {
    throw new RemoteError("BACKEND_REJECTED");
  }
  assertSmallResponse(value);
  return value;
}
