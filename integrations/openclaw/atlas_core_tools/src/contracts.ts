import { Type, type TSchema } from "typebox";
import { Value } from "typebox/value";
import errorContract from "../contracts/v4/error.schema.json" with { type: "json" };
import requestContract from "../contracts/v4/tool-requests.schema.json" with { type: "json" };
import resultContract from "../contracts/v4/tool-results.schema.json" with { type: "json" };

export const TOOL_NAMES = [
  "atlas_get_day_plan",
  "atlas_get_activity_status",
  "atlas_propose_plan_revision",
  "atlas_get_usage_summary",
  "atlas_get_goal_progress",
  "atlas_get_training_plan",
] as const;

export type ToolName = (typeof TOOL_NAMES)[number];

export const CAPABILITIES = {
  atlas_get_day_plan: "day_plan.read",
  atlas_get_activity_status: "activity_status.read",
  atlas_propose_plan_revision: "plan_revision.propose",
  atlas_get_usage_summary: "usage_summary.read",
  atlas_get_goal_progress: "goal_progress.read",
  atlas_get_training_plan: "training_plan.read",
} as const satisfies Record<ToolName, string>;

export type Capability = (typeof CAPABILITIES)[ToolName];

type JsonSchema = Record<string, unknown>;
type ContractDocument = {
  $defs: Record<string, JsonSchema>;
};

const requestDocument = requestContract as unknown as ContractDocument;
const resultDocument = resultContract as unknown as ContractDocument;
const errorDocument = errorContract as unknown as JsonSchema;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Inline the proposal operation's local $defs references before passing the
 * contract schema to TypeBox. The source V4 document remains untouched.
 */
function inlineLocalDefinitions(value: unknown, inheritedDefs: Record<string, unknown> = {}): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => inlineLocalDefinitions(item, inheritedDefs));
  }
  if (!isRecord(value)) return value;

  const localDefs = isRecord(value.$defs) ? value.$defs : inheritedDefs;
  if (typeof value.$ref === "string" && value.$ref.startsWith("#/$defs/")) {
    const key = value.$ref.slice("#/$defs/".length);
    if (!Object.hasOwn(localDefs, key)) throw new Error(`Unresolved local contract reference: ${key}`);
    return inlineLocalDefinitions(localDefs[key], localDefs);
  }

  return Object.fromEntries(
    Object.entries(value).map(([key, child]) => [key, inlineLocalDefinitions(child, localDefs)]),
  );
}

function schemaForTool(toolName: ToolName): TSchema {
  const name = `${toolName}_arguments`;
  const schema = requestDocument.$defs[name];
  if (!schema) throw new Error(`Missing V4 request schema for ${toolName}`);
  return Type.Unsafe(inlineLocalDefinitions(schema) as TSchema);
}

function resultSchemaForTool(toolName: ToolName): TSchema {
  const schema = resultDocument.$defs[`${toolName}_response`];
  if (!schema) throw new Error(`Missing V4 result schema for ${toolName}`);
  return Type.Unsafe(schema as TSchema);
}

export const ARGUMENT_SCHEMAS = Object.fromEntries(
  TOOL_NAMES.map((toolName) => [toolName, schemaForTool(toolName)]),
) as Record<ToolName, TSchema>;

export const RESULT_SCHEMAS = Object.fromEntries(
  TOOL_NAMES.map((toolName) => [toolName, resultSchemaForTool(toolName)]),
) as Record<ToolName, TSchema>;

export const ERROR_SCHEMA = Type.Unsafe(errorDocument as TSchema);

export const OUTPUT_SCHEMAS = Object.fromEntries(
  TOOL_NAMES.map((toolName) => [toolName, Type.Union([RESULT_SCHEMAS[toolName], ERROR_SCHEMA])]),
) as unknown as Record<ToolName, TSchema>;

export const V4_ERROR_CODES = [
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
] as const;

export type V4ErrorCode = (typeof V4_ERROR_CODES)[number];

export interface V4Error {
  contract_version: 4;
  schema_version: 4;
  request_id: string;
  error: {
    code: V4ErrorCode;
    message: string;
    retryable: boolean;
    capability: Capability | null;
    retry_after_seconds: number | null;
  };
}

export function checkArguments(toolName: ToolName, value: unknown): boolean {
  return Value.Check(ARGUMENT_SCHEMAS[toolName], value);
}

export function checkResult(toolName: ToolName, value: unknown): boolean {
  return Value.Check(RESULT_SCHEMAS[toolName], value);
}

export function checkError(value: unknown): value is V4Error {
  return Value.Check(ERROR_SCHEMA, value);
}

export function makeV4Error(
  code: V4ErrorCode,
  requestId: string,
  capability: Capability | null,
  message: string,
  retryable = false,
): V4Error {
  return {
    contract_version: 4,
    schema_version: 4,
    request_id: requestId,
    error: {
      code,
      message: message.slice(0, 240),
      retryable,
      capability,
      retry_after_seconds: null,
    },
  };
}

export function hasUnknownProperties(toolName: ToolName, value: unknown): boolean {
  if (!isRecord(value)) return false;
  const schema = requestDocument.$defs[`${toolName}_arguments`];
  const allowed = new Set(Object.keys((schema.properties as Record<string, unknown> | undefined) ?? {}));
  return Object.keys(value).some((key) => !allowed.has(key));
}
