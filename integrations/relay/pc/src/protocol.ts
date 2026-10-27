import { RemoteError } from "./errors.js";

export const PROTOCOL = "atlas.remote.v1" as const;
export const MAX_FRAME_BYTES = 65_536;
export const MAX_REQUEST_BYTES = 32_768;
export const MAX_RESPONSE_BYTES = 262_144;
export const SESSION_SECONDS = 3_600;
export const CACHE_TTL_MS = 10 * 60_000;

export const CAPABILITIES = [
  "plan.revision",
  "coach.status",
  "plan.today",
  "sport.plan",
  "sync.status",
] as const;
export type Capability = (typeof CAPABILITIES)[number];

export const OPERATIONS = [
  "backend.health",
  "coach.status",
  "plan.today.read",
  "plan.revision.request",
  "plan.revision.result",
  "sport.plan.read",
  "sync.status",
] as const;
export type Operation = (typeof OPERATIONS)[number];

export interface Hello {
  protocol: typeof PROTOCOL;
  kind: "hello";
  device_id: string;
  client_nonce: string;
  client_version: string;
  capabilities: Capability[];
  auth: { scheme: "device-token-v1"; token: string };
  timestamp: string;
}

export interface RequestEnvelope {
  protocol: typeof PROTOCOL;
  kind: "request";
  request_id: string;
  session_id: string;
  operation: Operation;
  idempotency_key: string;
  created_at: string;
  expires_at: string;
  payload: Record<string, unknown>;
}

export interface ResponseEnvelope {
  protocol: typeof PROTOCOL;
  kind: "response";
  request_id: string;
  session_id: string;
  operation: Operation;
  status: "OK";
  result: Record<string, unknown>;
  completed_at: string;
  replayed: boolean;
}

export interface ErrorEnvelope {
  protocol: typeof PROTOCOL;
  kind: "error";
  request_id: string | null;
  session_id: string | null;
  error: { code: string; message: string; retryable: boolean };
  completed_at: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{1,160}$/;
const NONCE = /^[A-Za-z0-9+/]{22}==$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const ISO_DATETIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const REQUEST_KEYS = ["protocol", "kind", "request_id", "session_id", "operation", "idempotency_key", "created_at", "expires_at", "payload"];
const HELLO_KEYS = ["protocol", "kind", "device_id", "client_nonce", "client_version", "capabilities", "auth", "timestamp"];

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && Object.keys(value).every((key) => keys.includes(key));
}

function parseTimestamp(value: unknown): number | null {
  if (typeof value !== "string" || !ISO_DATETIME.test(value)) return null;
  const result = Date.parse(value);
  return Number.isFinite(result) ? result : null;
}

function canonicalBase64(value: string): Buffer | null {
  try {
    const bytes = Buffer.from(value, "base64");
    return bytes.toString("base64") === value ? bytes : null;
  } catch {
    return null;
  }
}

function invalid(): never {
  throw new RemoteError("INVALID_REQUEST");
}

export function parseJsonFrame(data: string | ArrayBuffer): unknown {
  let text: string;
  if (typeof data === "string") text = data;
  else text = new TextDecoder("utf-8", { fatal: true }).decode(data);
  if (Buffer.byteLength(text, "utf8") > MAX_FRAME_BYTES) {
    throw new RemoteError("PAYLOAD_TOO_LARGE");
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return invalid();
  }
}

export function validateHello(value: unknown): Hello {
  if (!isRecord(value) || !hasExactKeys(value, HELLO_KEYS)) return invalid();
  if (value.protocol !== PROTOCOL || value.kind !== "hello") return invalid();
  if (typeof value.device_id !== "string" || !UUID.test(value.device_id)) return invalid();
  if (typeof value.client_nonce !== "string" || !NONCE.test(value.client_nonce)) return invalid();
  const nonce = canonicalBase64(value.client_nonce);
  if (!nonce || nonce.byteLength !== 16) return invalid();
  if (typeof value.client_version !== "string" || value.client_version.length < 1 || value.client_version.length > 40) return invalid();
  if (!Array.isArray(value.capabilities) || value.capabilities.length > 8) return invalid();
  if (new Set(value.capabilities).size !== value.capabilities.length) return invalid();
  if (!value.capabilities.every((item) => CAPABILITIES.includes(item as Capability))) return invalid();
  if (!isRecord(value.auth) || !hasExactKeys(value.auth, ["scheme", "token"])) return invalid();
  if (value.auth.scheme !== "device-token-v1" || typeof value.auth.token !== "string" || !TOKEN.test(value.auth.token)) return invalid();
  const tokenBytes = Buffer.from(value.auth.token, "base64url");
  if (tokenBytes.byteLength !== 32 || tokenBytes.toString("base64url") !== value.auth.token) return invalid();
  if (parseTimestamp(value.timestamp) === null) return invalid();
  return value as unknown as Hello;
}

export function validateRequest(value: unknown, sessionId: string, now = Date.now()): RequestEnvelope {
  if (!isRecord(value) || !hasExactKeys(value, REQUEST_KEYS)) return invalid();
  if (value.protocol !== PROTOCOL || value.kind !== "request") return invalid();
  if (typeof value.request_id !== "string" || !UUID.test(value.request_id)) return invalid();
  if (typeof value.session_id !== "string" || value.session_id !== sessionId || !UUID.test(value.session_id)) return invalid();
  if (typeof value.operation !== "string" || !OPERATIONS.includes(value.operation as Operation)) {
    throw new RemoteError("UNKNOWN_OPERATION");
  }
  if (typeof value.idempotency_key !== "string" || !IDEMPOTENCY_KEY.test(value.idempotency_key)) return invalid();
  const createdAt = parseTimestamp(value.created_at);
  const expiresAt = parseTimestamp(value.expires_at);
  if (createdAt === null || expiresAt === null || expiresAt <= createdAt || expiresAt > createdAt + 60_000) return invalid();
  if (expiresAt <= now) throw new RemoteError("EXPIRED");
  if (Math.abs(now - createdAt) > 120_000) throw new RemoteError("EXPIRED");
  if (!isRecord(value.payload)) return invalid();
  const size = Buffer.byteLength(JSON.stringify(value), "utf8");
  if (size > MAX_REQUEST_BYTES) throw new RemoteError("PAYLOAD_TOO_LARGE");
  return value as unknown as RequestEnvelope;
}

export function validatePayload(operation: Operation, payload: Record<string, unknown>): void {
  const empty = () => Object.keys(payload).length === 0;
  if (operation === "backend.health" || operation === "coach.status" || operation === "plan.today.read" || operation === "sync.status") {
    if (!empty()) invalid();
    return;
  }
  if (operation === "sport.plan.read") {
    if (!hasExactKeys(payload, ["local_date", "timezone"]) ||
      typeof payload.local_date !== "string" || !isValidDate(payload.local_date) ||
      typeof payload.timezone !== "string" || payload.timezone.length < 1 || payload.timezone.length > 100) invalid();
    try {
      new Intl.DateTimeFormat("en-US", { timeZone: payload.timezone }).format(0);
    } catch {
      invalid();
    }
    return;
  }
  if (operation === "plan.revision.result") {
    if (!hasExactKeys(payload, ["request_id"]) || typeof payload.request_id !== "string" || !UUID.test(payload.request_id)) invalid();
    return;
  }
  if (operation === "plan.revision.request") {
    validatePlanRevision(payload);
    return;
  }
  throw new RemoteError("UNKNOWN_OPERATION");
}

export function isValidDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function assertObject(value: unknown, required: readonly string[], optional: readonly string[] = []): asserts value is Record<string, unknown> {
  if (!isRecord(value)) invalid();
  const allowed = new Set([...required, ...optional]);
  if (required.some((key) => !(key in value)) || Object.keys(value).some((key) => !allowed.has(key))) invalid();
}

function assertString(value: unknown, min: number, max: number, pattern?: RegExp): asserts value is string {
  if (typeof value !== "string" || value.trim() !== value || value.length < min || value.length > max || /[\u0000-\u001f]/.test(value) || (pattern && !pattern.test(value))) invalid();
}

function assertInt(value: unknown, min: number, max: number): asserts value is number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) invalid();
}

function assertTimezone(value: unknown): asserts value is string {
  assertString(value, 1, 64);
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format(0);
  } catch {
    invalid();
  }
}

const PLAN_UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const ACTIONS = ["home.set_directive", "home.reorder_cards", "focus.propose", "focus.plan", "focus.start", "focus.pause", "habit.suggest", "app_policy.propose", "app_policy.adjust", "content.recommend"];
const DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];

function validatePlanRevision(value: Record<string, unknown>): void {
  assertObject(value, ["schema_version", "request_id", "conversation_id", "message", "plan_context_consented", "mandate_summary", "plan"]);
  if (value.schema_version !== 3 || typeof value.plan_context_consented !== "boolean") invalid();
  assertString(value.request_id, 36, 36, PLAN_UUID);
  if (value.conversation_id !== null) assertString(value.conversation_id, 1, 120);
  assertString(value.message, 1, 2000);
  const mandate = value.mandate_summary;
  assertObject(mandate, ["id", "version"], ["allowed_actions", "mode", "focus_window", "app_policy_bounds"]);
  assertString(mandate.id, 1, 80);
  assertInt(mandate.version, 1, Number.MAX_SAFE_INTEGER);
  if (mandate.mode !== undefined && mandate.mode !== "proposals_only" && mandate.mode !== "preauthorized") invalid();
  if (mandate.allowed_actions !== undefined) {
    if (!Array.isArray(mandate.allowed_actions) || mandate.allowed_actions.length > 10 || new Set(mandate.allowed_actions).size !== mandate.allowed_actions.length || !mandate.allowed_actions.every((x) => ACTIONS.includes(x as string))) invalid();
  }
  if (mandate.app_policy_bounds !== undefined) {
    if (!isRecord(mandate.app_policy_bounds) || Object.keys(mandate.app_policy_bounds).length > 50) invalid();
    for (const bounds of Object.values(mandate.app_policy_bounds)) {
      if (!isRecord(bounds)) invalid();
      for (const bound of Object.values(bounds)) if (typeof bound !== "string" && !Number.isSafeInteger(bound)) invalid();
    }
  }
  if (mandate.focus_window !== undefined && mandate.focus_window !== null) {
    const window = mandate.focus_window;
    assertObject(window, ["days", "starts_at", "ends_at", "timezone", "max_minutes_per_session", "max_sessions_per_day"]);
    if (!Array.isArray(window.days) || window.days.length < 1 || window.days.length > 7 || new Set(window.days).size !== window.days.length || !window.days.every((day) => DAYS.includes(day as string))) invalid();
    assertString(window.starts_at, 5, 5, /^(?:[01]\d|2[0-3]):[0-5]\d$/);
    assertString(window.ends_at, 5, 5, /^(?:[01]\d|2[0-3]):[0-5]\d$/);
    assertTimezone(window.timezone);
    assertInt(window.max_minutes_per_session, 1, 180);
    assertInt(window.max_sessions_per_day, 1, 8);
    if (window.starts_at >= window.ends_at) invalid();
  }
  const plan = value.plan;
  assertObject(plan, ["plan_id", "local_date", "timezone", "version", "state", "activities"]);
  assertString(plan.plan_id, 36, 36, PLAN_UUID);
  assertString(plan.local_date, 10, 10, ISO_DATE);
  if (!isValidDate(plan.local_date)) invalid();
  assertTimezone(plan.timezone);
  assertInt(plan.version, 1, Number.MAX_SAFE_INTEGER);
  if (plan.state !== "DRAFT" && plan.state !== "APPROVED") invalid();
  if (!Array.isArray(plan.activities) || plan.activities.length > 32) invalid();
  const ids = new Set<string>();
  for (const item of plan.activities) {
    assertObject(item, ["activity_id", "kind", "title", "start_minute", "duration_minutes", "priority", "goal_id"]);
    assertString(item.activity_id, 36, 36, PLAN_UUID);
    if (ids.has(item.activity_id.toLowerCase())) invalid();
    ids.add(item.activity_id.toLowerCase());
    if (!["FOCUS", "TRAINING", "APPOINTMENT", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"].includes(item.kind as string)) invalid();
    assertString(item.title, 1, 80);
    assertInt(item.start_minute, 0, 1439);
    assertInt(item.duration_minutes, 1, 1440);
    if (!["REQUIRED", "IMPORTANT", "OPTIONAL"].includes(item.priority as string)) invalid();
    if (item.goal_id !== null) assertString(item.goal_id, 36, 36, PLAN_UUID);
  }
}

export function decodeAppString(value: string | ArrayBuffer): string {
  if (typeof value === "string") return value;
  return new TextDecoder("utf-8", { fatal: true }).decode(value);
}

export function assertSmallResponse(value: unknown): asserts value is Record<string, unknown> {
  if (!isRecord(value)) throw new RemoteError("BACKEND_REJECTED");
  if (Buffer.byteLength(JSON.stringify(value), "utf8") > MAX_RESPONSE_BYTES) throw new RemoteError("PAYLOAD_TOO_LARGE");
}
