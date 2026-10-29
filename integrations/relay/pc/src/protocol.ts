import { RemoteError } from "./errors.js";

export const PROTOCOL = "atlas.remote.v1" as const;
export const PROTOCOL_V2 = "atlas.remote.v2" as const;
export const PROTOCOLS = [PROTOCOL, PROTOCOL_V2] as const;
export type Protocol = (typeof PROTOCOLS)[number];
export const MAX_FRAME_BYTES = 65_536;
export const MAX_REQUEST_BYTES = 32_768;
export const MAX_RESPONSE_BYTES = 262_144;
export const SESSION_SECONDS = 3_600;
export const CACHE_TTL_MS = 10 * 60_000;

export const V1_CAPABILITIES = [
  "plan.revision",
  "coach.chat",
  "coach.status",
  "plan.today",
  "sport.plan",
  "sync.status",
] as const;
export const V4_CAPABILITIES = [
  "v4.day_plan.snapshot.write",
  "v4.activity_status.write",
  "v4.usage_summary.write",
  "v4.goal_progress.write",
  "v4.training_plan.write",
  "v4.proposal.ack",
  "v4.coach.chat",
  "v4.consent.state.write",
] as const;
export const CAPABILITIES = [...V1_CAPABILITIES, ...V4_CAPABILITIES] as const;
export type Capability = (typeof CAPABILITIES)[number];

export const V1_OPERATIONS = [
  "backend.health",
  "coach.chat.request",
  "coach.status",
  "plan.today.read",
  "plan.revision.request",
  "plan.revision.result",
  "sport.plan.read",
  "sync.status",
] as const;
export const V4_OPERATIONS = [
  "v4.plan.snapshot.upsert",
  "v4.activity-status.events.append",
  "v4.usage.summary.upsert",
  "v4.goal.snapshot.upsert",
  "v4.training.snapshot.upsert",
  "v4.proposals.pull",
  "v4.proposals.ack",
  "v4.coach.chat.request",
  "v4.consent.state.upsert",
] as const;
export const OPERATIONS = [...V1_OPERATIONS, ...V4_OPERATIONS] as const;
export type Operation = (typeof OPERATIONS)[number];
export type V4Operation = (typeof V4_OPERATIONS)[number];

export interface Hello {
  protocol: Protocol;
  kind: "hello";
  device_id: string;
  client_nonce: string;
  client_version: string;
  capabilities: Capability[];
  auth: { scheme: "device-token-v1"; token: string };
  timestamp: string;
}

export interface RequestEnvelope {
  protocol: Protocol;
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
  protocol: Protocol;
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
  protocol: Protocol;
  kind: "error";
  request_id: string | null;
  session_id: string | null;
  // Additive upstream fields are emitted only for V4 operation errors on PROTOCOL_V2 sessions.
  // PROTOCOL_V1 keeps its original error shape so strict legacy clients remain compatible.
  error: { code: string; message: string; retryable: boolean; http_status?: number; remote_code?: string };
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
const V4_OPERATION_CAPABILITIES: Record<V4Operation, Capability> = {
  "v4.plan.snapshot.upsert": "v4.day_plan.snapshot.write",
  "v4.activity-status.events.append": "v4.activity_status.write",
  "v4.usage.summary.upsert": "v4.usage_summary.write",
  "v4.goal.snapshot.upsert": "v4.goal_progress.write",
  "v4.training.snapshot.upsert": "v4.training_plan.write",
  "v4.proposals.pull": "v4.proposal.ack",
  "v4.proposals.ack": "v4.proposal.ack",
  "v4.coach.chat.request": "v4.coach.chat",
  "v4.consent.state.upsert": "v4.consent.state.write",
};

export function isV4Operation(operation: Operation): operation is V4Operation {
  return (V4_OPERATIONS as readonly string[]).includes(operation);
}

export function capabilityForOperation(operation: Operation): Capability | undefined {
  if (isV4Operation(operation)) return V4_OPERATION_CAPABILITIES[operation];
  const legacy: Partial<Record<Operation, Capability>> = {
    "coach.chat.request": "coach.chat",
    "coach.status": "coach.status",
    "plan.today.read": "plan.today",
    "plan.revision.request": "plan.revision",
    "plan.revision.result": "plan.revision",
    "sport.plan.read": "sport.plan",
    "sync.status": "sync.status",
  };
  return legacy[operation];
}

export function protocolSupportsOperation(protocol: Protocol, operation: string): operation is Operation {
  return protocol === PROTOCOL
    ? (V1_OPERATIONS as readonly string[]).includes(operation)
    : (OPERATIONS as readonly string[]).includes(operation);
}

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
  if (!PROTOCOLS.includes(value.protocol as Protocol) || value.kind !== "hello") return invalid();
  if (typeof value.device_id !== "string" || !UUID.test(value.device_id)) return invalid();
  if (typeof value.client_nonce !== "string" || !NONCE.test(value.client_nonce)) return invalid();
  const nonce = canonicalBase64(value.client_nonce);
  if (!nonce || nonce.byteLength !== 16) return invalid();
  if (typeof value.client_version !== "string" || value.client_version.length < 1 || value.client_version.length > 40) return invalid();
  if (!Array.isArray(value.capabilities) || value.capabilities.length > (value.protocol === PROTOCOL ? 8 : CAPABILITIES.length)) return invalid();
  if (new Set(value.capabilities).size !== value.capabilities.length) return invalid();
  const allowedCapabilities: readonly string[] = value.protocol === PROTOCOL ? V1_CAPABILITIES : CAPABILITIES;
  if (!value.capabilities.every((item) => typeof item === "string" && allowedCapabilities.includes(item))) {
    if (value.protocol === PROTOCOL_V2) throw new RemoteError("OPERATION_UNAVAILABLE");
    return invalid();
  }
  if (!isRecord(value.auth) || !hasExactKeys(value.auth, ["scheme", "token"])) return invalid();
  if (value.auth.scheme !== "device-token-v1" || typeof value.auth.token !== "string" || !TOKEN.test(value.auth.token)) return invalid();
  const tokenBytes = Buffer.from(value.auth.token, "base64url");
  if (tokenBytes.byteLength !== 32 || tokenBytes.toString("base64url") !== value.auth.token) return invalid();
  if (parseTimestamp(value.timestamp) === null) return invalid();
  return value as unknown as Hello;
}

export function validateRequest(value: unknown, sessionId: string, now = Date.now(), protocol: Protocol = PROTOCOL): RequestEnvelope {
  if (!isRecord(value) || !hasExactKeys(value, REQUEST_KEYS)) return invalid();
  if (value.protocol !== protocol || value.kind !== "request") return invalid();
  if (typeof value.request_id !== "string" || !UUID.test(value.request_id)) return invalid();
  if (typeof value.session_id !== "string" || value.session_id !== sessionId || !UUID.test(value.session_id)) return invalid();
  if (typeof value.operation !== "string" || !protocolSupportsOperation(protocol, value.operation)) {
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
  if (operation === "coach.chat.request") {
    validateCoachChat(payload);
    return;
  }
  if (isV4Operation(operation)) {
    const schema = V4_PAYLOAD_SCHEMAS[operation];
    assertJsonSchema(payload, schema);
    validateV4CrossFields(operation, payload);
    return;
  }
  throw new RemoteError("UNKNOWN_OPERATION");
}

export interface RequestValidationContext {
  protocol: Protocol;
  requestId: string;
  idempotencyKey: string;
}

export function validateOperationPayload(operation: Operation, payload: Record<string, unknown>, context: RequestValidationContext): void {
  if (isV4Operation(operation) && context.protocol !== PROTOCOL_V2) throw new RemoteError("UNKNOWN_OPERATION");
  validatePayload(operation, payload);
  if (!isV4Operation(operation)) return;
  if (typeof payload.request_id === "string" && payload.request_id !== context.requestId) invalid();
  if (typeof payload.idempotency_key === "string" && payload.idempotency_key !== context.idempotencyKey) invalid();
  if (operation === "v4.coach.chat.request" && context.idempotencyKey !== payload.request_id) invalid();
  if (operation === "v4.proposals.ack" && context.idempotencyKey !== payload.ack_id) invalid();
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
  if (typeof value !== "string" || value.trim() !== value || [...value].length < min || [...value].length > max || /[\u0000-\u001f]/.test(value) || (pattern && !pattern.test(value))) invalid();
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

type JsonSchema = {
  type?: "object" | "array" | "string" | "integer" | "number" | "boolean" | "null";
  const?: unknown;
  enum?: readonly unknown[];
  properties?: Record<string, JsonSchema>;
  required?: readonly string[];
  additionalProperties?: boolean;
  items?: JsonSchema;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
  pattern?: RegExp;
  format?: "uuid" | "date" | "date-time";
  anyOf?: readonly JsonSchema[];
};

const jsonString = (minLength = 0, maxLength = Number.MAX_SAFE_INTEGER, pattern?: RegExp, format?: JsonSchema["format"]): JsonSchema => ({ type: "string", minLength, maxLength, ...(pattern ? { pattern } : {}), ...(format ? { format } : {}) });
const jsonInteger = (minimum = Number.MIN_SAFE_INTEGER, maximum = Number.MAX_SAFE_INTEGER): JsonSchema => ({ type: "integer", minimum, maximum });
const jsonEnum = (values: readonly string[]): JsonSchema => ({ type: "string", enum: values });
const jsonConst = (value: unknown): JsonSchema => ({ const: value });
const jsonNullable = (schema: JsonSchema): JsonSchema => ({ anyOf: [schema, { type: "null" }] });
const jsonArray = (items: JsonSchema, maxItems: number, minItems = 0, uniqueItems = false): JsonSchema => ({ type: "array", items, maxItems, minItems, ...(uniqueItems ? { uniqueItems: true } : {}) });
const jsonObject = (properties: Record<string, JsonSchema>, required: readonly string[] = Object.keys(properties)): JsonSchema => ({ type: "object", properties, required, additionalProperties: false });

const V4_UUID = jsonString(36, 36, undefined, "uuid");
const V4_UTC = jsonString(20, 40, /Z$/, "date-time");
const V4_DATE = jsonString(10, 10, undefined, "date");
const V4_TIMEZONE = jsonString(1, 64, /^(UTC|[A-Za-z_+-]+(?:\/[A-Za-z0-9_+.-]+)+)$/);
const V4_STATUSES = ["PLANNED", "IN_PROGRESS", "DONE", "MISSED", "CANCELLED", "POSTPONED"] as const;
const V4_PRIORITIES = ["REQUIRED", "IMPORTANT", "OPTIONAL"] as const;
const V4_CATEGORIES = ["DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN"] as const;

function v4SyncSchema(capability: string, data: JsonSchema): JsonSchema {
  return jsonObject({
    contract_version: jsonConst(4),
    schema_version: jsonConst(4),
    request_id: V4_UUID,
    capability: jsonConst(capability),
    sent_at: V4_UTC,
    consent_version: jsonInteger(1),
    idempotency_key: V4_UUID,
    data,
  });
}

const V4_DAY_PLAN = jsonObject({
  plan_id: V4_UUID,
  local_date: V4_DATE,
  timezone: V4_TIMEZONE,
  version: jsonInteger(1),
  current_revision_id: V4_UUID,
  state: jsonEnum(["DRAFT", "APPROVED"]),
  origin: jsonEnum(["LOCAL", "AGENT", "MIGRATED", "DEMO"]),
  observed_at: V4_UTC,
  activities: jsonArray(jsonObject({
    activity_id: V4_UUID,
    kind: jsonEnum(["FOCUS", "TRAINING", "APPOINTMENT", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"]),
    title: jsonString(1, 80),
    status: jsonEnum(V4_STATUSES),
    scheduled_local: jsonObject({
      local_date: V4_DATE,
      start_minute: jsonInteger(0, 1439),
      duration_minutes: jsonInteger(1, 1440),
      timezone: V4_TIMEZONE,
    }),
    priority: jsonEnum(V4_PRIORITIES),
    goal_id: jsonNullable(V4_UUID),
    continuity_id: jsonNullable(V4_UUID),
    supersedes_activity_id: jsonNullable(V4_UUID),
    status_provenance: jsonEnum(["DECLARED", "MEASURED", "CONFIRMED", "UNKNOWN"]),
    status_decided_at: jsonNullable(V4_UTC),
  }), 32),
});

const V4_ACTIVITY_EVENT = jsonObject({
  event_id: V4_UUID,
  sequence: jsonInteger(1),
  plan_id: V4_UUID,
  activity_id: V4_UUID,
  continuity_id: jsonNullable(V4_UUID),
  plan_version: jsonInteger(1),
  from_status: jsonEnum(V4_STATUSES),
  to_status: jsonEnum(V4_STATUSES),
  provenance: jsonEnum(["DECLARED", "MEASURED", "CONFIRMED", "UNKNOWN"]),
  source: jsonEnum(["USER_UI", "FOCUS_TIMER", "ANDROID", "UNKNOWN"]),
  decided_at: V4_UTC,
  observed_at: V4_UTC,
  focus_session_id: jsonNullable(V4_UUID),
});

const V4_USAGE = jsonObject({
  window_start: V4_UTC,
  window_end: V4_UTC,
  timezone: V4_TIMEZONE,
  total_foreground_ms: jsonInteger(0, 1_209_600_000),
  apps: jsonArray(jsonObject({
    app_ref: jsonString(1, 80, /^[A-Za-z0-9_-]+$/),
    display_name: jsonNullable(jsonString(1, 80)),
    foreground_ms: jsonInteger(0, 1_209_600_000),
    launch_count: jsonInteger(0, 100_000),
    last_used_at: jsonNullable(V4_UTC),
    reliability: jsonEnum(["HIGH", "MEDIUM", "LOW"]),
  }), 10),
  coverage: jsonObject({
    observed_ms: jsonInteger(0, 1_209_600_000),
    blind_ms: jsonInteger(0, 1_209_600_000),
    events_seen: jsonInteger(0, 1_000_000),
    truncated: { type: "boolean" },
  }),
  generated_at: V4_UTC,
  consent_version: jsonInteger(1),
});

const V4_GOALS = jsonObject({
  goals_version: jsonInteger(1),
  observed_at: V4_UTC,
  goals: jsonArray(jsonObject({
    goal_id: V4_UUID,
    title: jsonString(1, 80),
    status: jsonEnum(["ACTIVE", "PAUSED", "COMPLETED", "ABANDONED"]),
    progress_value: jsonNullable({ type: "number" }),
    unit: jsonNullable({ type: "string" }),
  }), 20),
});

const V4_TRAINING = jsonObject({
  provider: jsonConst("wger"),
  source: jsonEnum(["WGER_API", "ANDROID_SYNC"]),
  sync_state: jsonEnum(["FRESH", "STALE", "UNAVAILABLE", "MISMATCH", "UNKNOWN"]),
  observed_at: V4_UTC,
  source_updated_at: jsonNullable(V4_UTC),
  routines: jsonArray(jsonObject({
    routine_id: jsonInteger(1, 1_000_000_000),
    day_id: jsonNullable(jsonInteger(1, 1_000_000_000)),
  }), 20),
  sessions: jsonArray(jsonObject({
    session_id: jsonString(1, 128),
    scheduled_date: jsonNullable(V4_DATE),
    title: jsonString(1, 80),
  }), 20),
  results: jsonArray(jsonObject({
    result_id: V4_UUID,
    performed_at: V4_UTC,
    source: jsonEnum(["WGER_API", "USER_DECLARED", "IMPORTED"]),
    verification: jsonEnum(["API_READ_BACK", "USER_CONFIRMED", "UNVERIFIED"]),
  }), 50),
});

const V4_ACK = jsonObject({
  contract_version: jsonConst(4),
  schema_version: jsonConst(4),
  ack_id: V4_UUID,
  capability: jsonConst("proposal.ack"),
  proposal_id: V4_UUID,
  sequence: jsonInteger(1),
  state: jsonEnum(["APPLIED", "REJECTED", "STALE_VERSION", "EXPIRED", "VALIDATION_REJECTED", "UNCERTAIN", "RECONCILED"]),
  result_code: jsonEnum(["APPLIED", "USER_REJECTED", "STALE_VERSION", "PROPOSAL_EXPIRED", "VALIDATION_FAILED", "RESULT_UNCERTAIN", "RECONCILED_APPLIED", "RECONCILED_NOT_APPLIED"]),
  occurred_at: V4_UTC,
  plan_id: V4_UUID,
  applied_plan_version: jsonInteger(1),
  revision_id: V4_UUID,
  scope_hash: jsonString(71, 71, /^sha256:[0-9a-f]{64}$/),
  effect_ids: jsonArray(jsonString(1, 96, /^(PLAN_ACTIVITY_(CREATE|UPDATE|DELETE):[0-9a-f-]{36}|PLAN_APPLY:[0-9a-f-]{36})$/), 32, 1, true),
  consent_version: jsonInteger(1),
}, ["contract_version", "schema_version", "ack_id", "capability", "proposal_id", "sequence", "state", "result_code", "occurred_at", "consent_version"]);

const V4_PAYLOAD_SCHEMAS: Record<V4Operation, JsonSchema> = {
  "v4.plan.snapshot.upsert": v4SyncSchema("day_plan.snapshot.write", V4_DAY_PLAN),
  "v4.activity-status.events.append": v4SyncSchema("activity_status.write", V4_ACTIVITY_EVENT),
  "v4.usage.summary.upsert": v4SyncSchema("usage_summary.write", V4_USAGE),
  "v4.goal.snapshot.upsert": v4SyncSchema("goal_progress.write", V4_GOALS),
  "v4.training.snapshot.upsert": v4SyncSchema("training_plan.write", V4_TRAINING),
  "v4.proposals.pull": jsonObject({
    contract_version: jsonConst(4),
    schema_version: jsonConst(4),
    request_id: V4_UUID,
    capability: jsonConst("proposal.ack"),
    consent_version: jsonInteger(1),
    page_size: jsonInteger(1, 32),
    cursor: jsonNullable(jsonString(0, 32_768)),
  }),
  "v4.proposals.ack": V4_ACK,
  "v4.coach.chat.request": jsonObject({
    contract_version: jsonConst(4),
    schema_version: jsonConst(4),
    request_id: V4_UUID,
    capability: jsonConst("coach.chat"),
    sent_at: V4_UTC,
    conversation_id: jsonString(1, 80),
    message: jsonString(1, 4000),
    consent_version: jsonInteger(1),
    shared_categories: jsonArray(jsonEnum(V4_CATEGORIES), 5, 0, true),
  }),
  "v4.consent.state.upsert": jsonObject({
    contract_version: jsonConst(4),
    schema_version: jsonConst(4),
    request_id: V4_UUID,
    capability: jsonConst("consent.state.write"),
    sent_at: V4_UTC,
    category: jsonEnum(V4_CATEGORIES),
    consent_version: jsonInteger(1),
    state: jsonEnum(["GRANTED", "REVOKED", "UNKNOWN"]),
    idempotency_key: V4_UUID,
  }),
};

function assertJsonSchema(value: unknown, schema: JsonSchema): void {
  if (schema.anyOf) {
    for (const candidate of schema.anyOf) {
      try {
        assertJsonSchema(value, candidate);
        return;
      } catch {
        // Try the next schema branch; all failures collapse to the closed-schema error below.
      }
    }
    invalid();
  }
  if (Object.hasOwn(schema, "const") && value !== schema.const) invalid();
  if (schema.enum && !schema.enum.includes(value)) invalid();
  if (schema.type === "object") {
    if (!isRecord(value)) invalid();
    if (schema.required?.some((key) => !Object.hasOwn(value, key))) invalid();
    const properties = schema.properties ?? {};
    if (schema.additionalProperties === false && Object.keys(value).some((key) => !Object.hasOwn(properties, key))) invalid();
    for (const [key, child] of Object.entries(properties)) {
      if (Object.hasOwn(value, key)) assertJsonSchema(value[key], child);
    }
    return;
  }
  if (schema.type === "array") {
    if (!Array.isArray(value) || value.length < (schema.minItems ?? 0) || value.length > (schema.maxItems ?? Number.MAX_SAFE_INTEGER)) invalid();
    if (schema.uniqueItems && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) invalid();
    if (schema.items) for (const item of value) assertJsonSchema(item, schema.items);
    return;
  }
  if (schema.type === "string") {
    if (typeof value !== "string" || [...value].length < (schema.minLength ?? 0) || [...value].length > (schema.maxLength ?? Number.MAX_SAFE_INTEGER)) invalid();
    if (schema.pattern && !schema.pattern.test(value)) invalid();
    if (schema.format === "uuid" && !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)) invalid();
    if (schema.format === "date" && !isValidDate(value)) invalid();
    if (schema.format === "date-time" && (parseTimestamp(value) === null || !isValidDate(value.slice(0, 10)))) invalid();
    return;
  }
  if (schema.type === "integer") {
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value < (schema.minimum ?? Number.MIN_SAFE_INTEGER) || value > (schema.maximum ?? Number.MAX_SAFE_INTEGER)) invalid();
    return;
  }
  if (schema.type === "number") {
    if (typeof value !== "number" || !Number.isFinite(value) || value < (schema.minimum ?? Number.MIN_SAFE_INTEGER) || value > (schema.maximum ?? Number.MAX_SAFE_INTEGER)) invalid();
    return;
  }
  if (schema.type === "boolean" && typeof value !== "boolean") invalid();
  if (schema.type === "null" && value !== null) invalid();
}

function validateV4CrossFields(operation: V4Operation, payload: Record<string, unknown>): void {
  const requestIdOperation = operation !== "v4.proposals.ack";
  if (requestIdOperation && typeof payload.request_id !== "string") invalid();
  if (operation === "v4.proposals.ack") {
    const state = payload.state;
    const resultCode = payload.result_code;
    const detailFields = ["plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids"];
    const hasDetails = detailFields.some((key) => Object.hasOwn(payload, key));
    const matches: Record<string, string> = {
      APPLIED: "APPLIED",
      REJECTED: "USER_REJECTED",
      STALE_VERSION: "STALE_VERSION",
      EXPIRED: "PROPOSAL_EXPIRED",
      VALIDATION_REJECTED: "VALIDATION_FAILED",
      UNCERTAIN: "RESULT_UNCERTAIN",
    };
    if (typeof state === "string" && matches[state] && resultCode !== matches[state]) invalid();
    if ((state === "APPLIED" || (state === "RECONCILED" && resultCode === "RECONCILED_APPLIED")) &&
      detailFields.some((key) => !Object.hasOwn(payload, key))) invalid();
    if ((state === "REJECTED" || state === "UNCERTAIN" || ["USER_REJECTED", "STALE_VERSION", "PROPOSAL_EXPIRED", "VALIDATION_FAILED", "RESULT_UNCERTAIN", "RECONCILED_NOT_APPLIED"].includes(String(resultCode)) ||
      (state === "RECONCILED" && resultCode === "USER_REJECTED")) && hasDetails) invalid();
    if (state === "RECONCILED" && resultCode !== "RECONCILED_APPLIED" && !["RECONCILED_NOT_APPLIED", "USER_REJECTED"].includes(String(resultCode))) invalid();
  }
  if (operation === "v4.plan.snapshot.upsert") {
    const data = payload.data as Record<string, unknown>;
    assertTimezone(data.timezone);
    for (const activity of data.activities as Record<string, unknown>[]) assertTimezone((activity.scheduled_local as Record<string, unknown>).timezone);
  }
  if (operation === "v4.usage.summary.upsert") assertTimezone((payload.data as Record<string, unknown>).timezone);
}

const PLAN_UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const ACTIONS = ["home.set_directive", "home.reorder_cards", "focus.propose", "focus.plan", "focus.start", "focus.pause", "habit.suggest", "app_policy.propose", "app_policy.adjust", "content.recommend"];
const DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
const CONTEXT_CATEGORIES = ["focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals", "location_summary", "projects", "availability", "preferences", "package_names", "urls", "habit_titles"];

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

function validateCoachChat(value: Record<string, unknown>): void {
  assertObject(value, ["conversation_id", "message", "shared_context", "mandate_summary"]);
  assertString(value.conversation_id, 1, 120);
  assertString(value.message, 1, 2000);

  const sharedContext = value.shared_context;
  assertObject(sharedContext, ["consented_categories", "categories"]);
  const consented = sharedContext.consented_categories;
  if (!Array.isArray(consented) || consented.length > CONTEXT_CATEGORIES.length ||
    new Set(consented).size !== consented.length ||
    !consented.every((category) => typeof category === "string" && CONTEXT_CATEGORIES.includes(category))) invalid();
  const categories = sharedContext.categories;
  if (!isRecord(categories) || Object.keys(categories).length > CONTEXT_CATEGORIES.length ||
    Object.keys(categories).some((category) => !CONTEXT_CATEGORIES.includes(category))) invalid();

  const minimized: Record<string, string[]> = {};
  for (const [category, rawItems] of Object.entries(categories)) {
    if (!Array.isArray(rawItems) || rawItems.length > 10 || rawItems.some((item) => typeof item !== "string" || [...item].length > 200)) invalid();
    if (!consented.includes(category)) continue;
    const values = (rawItems as string[]).map((item) => item.trim()).filter(Boolean);
    if (values.length) minimized[category] = values;
  }
  if (Buffer.byteLength(JSON.stringify({ consented_categories: consented, categories: minimized }), "utf8") > 8192) {
    throw new RemoteError("PAYLOAD_TOO_LARGE");
  }

  validateMandateSummary(value.mandate_summary);
}

function validateMandateSummary(value: unknown): void {
  assertObject(value, ["id", "version"], ["allowed_actions", "mode", "focus_window", "app_policy_bounds"]);
  assertString(value.id, 1, 80);
  assertInt(value.version, 1, Number.MAX_SAFE_INTEGER);
  if (value.mode !== undefined && value.mode !== "proposals_only" && value.mode !== "preauthorized") invalid();
  if (value.allowed_actions !== undefined) {
    if (!Array.isArray(value.allowed_actions) || value.allowed_actions.length > 10 ||
      new Set(value.allowed_actions).size !== value.allowed_actions.length ||
      !value.allowed_actions.every((action) => ACTIONS.includes(action as string))) invalid();
  }
  if (value.app_policy_bounds !== undefined) {
    if (!isRecord(value.app_policy_bounds) || Object.keys(value.app_policy_bounds).length > 50) invalid();
    for (const bounds of Object.values(value.app_policy_bounds)) {
      if (!isRecord(bounds)) invalid();
      for (const bound of Object.values(bounds)) {
        if (typeof bound !== "string" && !Number.isSafeInteger(bound)) invalid();
      }
    }
  }
  if (value.focus_window !== undefined && value.focus_window !== null) {
    const window = value.focus_window;
    assertObject(window, ["days", "starts_at", "ends_at", "timezone", "max_minutes_per_session", "max_sessions_per_day"]);
    if (!Array.isArray(window.days) || window.days.length < 1 || window.days.length > 7 ||
      new Set(window.days).size !== window.days.length || !window.days.every((day) => DAYS.includes(day as string))) invalid();
    assertString(window.starts_at, 5, 5, /^(?:[01]\d|2[0-3]):[0-5]\d$/);
    assertString(window.ends_at, 5, 5, /^(?:[01]\d|2[0-3]):[0-5]\d$/);
    assertTimezone(window.timezone);
    assertInt(window.max_minutes_per_session, 1, 180);
    assertInt(window.max_sessions_per_day, 1, 8);
    if (window.starts_at >= window.ends_at) invalid();
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

export function assertCoachChatResponse(value: unknown): asserts value is Record<string, unknown> {
  if (!isRecord(value) || !hasExactKeys(value, ["reply", "proposals", "agent_status"]) ||
    typeof value.reply !== "string" || [...value.reply].length > 4000 ||
    !Array.isArray(value.proposals) || value.proposals.length > 5 || !value.proposals.every(isRecord) ||
    !["mock", "connected", "unavailable"].includes(String(value.agent_status))) {
    throw new RemoteError("BACKEND_REJECTED");
  }
}
