import { constants } from "node:fs";
import { open } from "node:fs/promises";
import { isIP } from "node:net";
import { isAbsolute } from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { Type, type Static } from "typebox";
import {
  CAPABILITIES,
  checkArguments,
  checkError,
  checkResult,
  hasUnknownProperties,
  makeV4Error,
  TOOL_NAMES,
  type Capability,
  type ToolName,
  type V4ErrorCode,
} from "./contracts.js";

export const DEFAULT_CORE_BASE_URL = "http://127.0.0.1:8100";
export const CONNECTION_TIMEOUT_MS = 2_000;
export const RESPONSE_TIMEOUT_MS = 5_000;
export const MAX_REQUEST_BYTES = 64 * 1024;
export const MAX_RESPONSE_BYTES = 256 * 1024;
export const MAX_CALLS_PER_TURN = 6;

export const CAPABILITY_FLAGS = {
  "day_plan.read": "dayPlanRead",
  "activity_status.read": "activityStatusRead",
  "plan_revision.propose": "planRevisionPropose",
  "usage_summary.read": "usageSummaryRead",
  "goal_progress.read": "goalProgressRead",
  "training_plan.read": "trainingPlanRead",
} as const satisfies Record<Capability, string>;

const CapabilityFlagsSchema = Type.Object(
  {
    dayPlanRead: Type.Optional(Type.Boolean({ default: false })),
    activityStatusRead: Type.Optional(Type.Boolean({ default: false })),
    planRevisionPropose: Type.Optional(Type.Boolean({ default: false })),
    usageSummaryRead: Type.Optional(Type.Boolean({ default: false })),
    goalProgressRead: Type.Optional(Type.Boolean({ default: false })),
    trainingPlanRead: Type.Optional(Type.Boolean({ default: false })),
  },
  { additionalProperties: false },
);

export const AtlasCoreToolsConfigSchema = Type.Object(
  {
    enabled: Type.Optional(Type.Boolean({ default: false })),
    coreBaseUrl: Type.Optional(
      Type.String({ default: DEFAULT_CORE_BASE_URL, minLength: 1, maxLength: 256 }),
    ),
    tokenFile: Type.Optional(Type.String({ minLength: 1, maxLength: 4096 })),
    capabilities: Type.Optional(CapabilityFlagsSchema),
  },
  { additionalProperties: false },
);

export type AtlasCoreToolsConfig = Static<typeof AtlasCoreToolsConfigSchema>;
export type CapabilityFlags = NonNullable<AtlasCoreToolsConfig["capabilities"]>;

export interface SafeLogEvent {
  call_id: string;
  tool_name: string;
  capability: Capability | null;
  request_hash: string;
  result_code: string;
  latency_ms: number;
}

export type SafeLogger = (event: SafeLogEvent) => void;

export class TurnQuota {
  private callCount = 0;
  private proposalUsed = false;

  consume(toolName: ToolName): boolean {
    if (this.callCount >= MAX_CALLS_PER_TURN) return false;
    if (toolName === "atlas_propose_plan_revision" && this.proposalUsed) return false;
    this.callCount += 1;
    if (toolName === "atlas_propose_plan_revision") this.proposalUsed = true;
    return true;
  }

  get calls(): number {
    return this.callCount;
  }
}

export function createTurnQuota(): TurnQuota {
  return new TurnQuota();
}

export interface CoreClientOptions {
  fetchImpl?: typeof fetch;
  logger?: SafeLogger;
  tokenResolver?: (config: AtlasCoreToolsConfig) => Promise<string | undefined>;
  connectTimeoutMs?: number;
  responseTimeoutMs?: number;
  now?: () => number;
  requestId?: () => string;
}

export interface ToolExecutionContext {
  toolCallId: string;
  signal?: AbortSignal;
  quota?: TurnQuota;
}

interface RouteDefinition {
  capability: Capability;
  method: "GET" | "POST";
  path: string;
}

const ROUTES: Record<ToolName, RouteDefinition> = {
  atlas_get_day_plan: {
    capability: CAPABILITIES.atlas_get_day_plan,
    method: "GET",
    path: "/internal/v4/day-plan",
  },
  atlas_get_activity_status: {
    capability: CAPABILITIES.atlas_get_activity_status,
    method: "GET",
    path: "/internal/v4/activity-status/",
  },
  atlas_propose_plan_revision: {
    capability: CAPABILITIES.atlas_propose_plan_revision,
    method: "POST",
    path: "/internal/v4/plan-revision-proposals",
  },
  atlas_get_usage_summary: {
    capability: CAPABILITIES.atlas_get_usage_summary,
    method: "GET",
    path: "/internal/v4/usage-summary",
  },
  atlas_get_goal_progress: {
    capability: CAPABILITIES.atlas_get_goal_progress,
    method: "GET",
    path: "/internal/v4/goal-progress",
  },
  atlas_get_training_plan: {
    capability: CAPABILITIES.atlas_get_training_plan,
    method: "GET",
    path: "/internal/v4/training-plan",
  },
};

class TimeoutFailure extends Error {
  constructor(readonly stage: "connect" | "response") {
    super(`${stage} timeout`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (isRecord(value)) {
    const fields = Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`);
    return `{${fields.join(",")}}`;
  }
  const serialized = JSON.stringify(value);
  return serialized === undefined ? "null" : serialized;
}

function requestHash(value: unknown): string {
  let serialized = "<unserializable>";
  try {
    serialized = canonicalJson(value);
  } catch {
    if (typeof value === "string") serialized = value;
  }
  return createHash("sha256").update(serialized).digest("hex");
}

function safeCallId(value: string): string {
  return /^[A-Za-z0-9:_-]{1,128}$/.test(value) ? value : "invalid-call-id";
}

function emitLog(logger: SafeLogger, event: SafeLogEvent): void {
  const sanitized: SafeLogEvent = {
    call_id: event.call_id,
    tool_name: event.tool_name,
    capability: event.capability,
    request_hash: event.request_hash,
    result_code: event.result_code,
    latency_ms: event.latency_ms,
  };
  try {
    logger(sanitized);
  } catch {
    // Logging must not change the tool result or disclose request data.
  }
}

function defaultLogger(event: SafeLogEvent): void {
  console.info(`[atlas_core_tools] ${JSON.stringify(event)}`);
}

function makeUrl(baseUrlValue: string | undefined, toolName: ToolName, args: Record<string, unknown>): URL {
  const baseUrl = new URL(baseUrlValue ?? DEFAULT_CORE_BASE_URL);
  const hostname = baseUrl.hostname.replace(/^\[|\]$/g, "");
  const ipVersion = isIP(hostname);
  const loopback = ipVersion === 4
    ? Number(hostname.split(".")[0]) === 127
    : ipVersion === 6 && hostname.toLowerCase() === "::1";
  if (
    baseUrl.protocol !== "http:" ||
    !loopback ||
    baseUrl.username.length > 0 ||
    baseUrl.password.length > 0 ||
    baseUrl.pathname !== "/" ||
    baseUrl.search.length > 0 ||
    baseUrl.hash.length > 0
  ) {
    throw new Error("Core URL must be a plain HTTP loopback origin.");
  }

  const route = ROUTES[toolName];
  const path = toolName === "atlas_get_activity_status"
    ? `${route.path}${encodeURIComponent(String(args.activity_id))}`
    : route.path;
  const url = new URL(path, baseUrl);
  const queryKeys: Partial<Record<ToolName, readonly string[]>> = {
    atlas_get_day_plan: ["local_date", "page_size", "cursor"],
    atlas_get_activity_status: ["plan_id", "include_details"],
    atlas_get_usage_summary: ["window_start", "window_end", "include_app_labels", "app_limit"],
    atlas_get_goal_progress: ["page_size", "cursor", "include_subgoals"],
    atlas_get_training_plan: ["local_date", "include_results", "page_size", "cursor"],
  };
  for (const key of queryKeys[toolName] ?? []) {
    const value = args[key];
    if (value !== undefined) url.searchParams.set(key, String(value));
  }
  return url;
}

async function resolveBearerToken(config: AtlasCoreToolsConfig): Promise<string | undefined> {
  const fromEnvironment = process.env.ATLAS_CORE_TOOLS_TOKEN;
  if (fromEnvironment !== undefined && fromEnvironment.length > 0) return validateToken(fromEnvironment);
  if (!config.tokenFile || !isAbsolute(config.tokenFile)) return undefined;

  let fileHandle;
  try {
    fileHandle = await open(config.tokenFile, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
    const stats = await fileHandle.stat();
    if (
      !stats.isFile() ||
      (stats.mode & 0o777) !== 0o600 ||
      typeof process.getuid !== "function" ||
      stats.uid !== process.getuid() ||
      stats.size < 1 ||
      stats.size > 4096
    ) {
      return undefined;
    }
    const content = await fileHandle.readFile({ encoding: "utf8" });
    return validateToken(content.endsWith("\n") ? content.slice(0, -1).replace(/\r$/, "") : content);
  } catch {
    return undefined;
  } finally {
    await fileHandle?.close().catch(() => undefined);
  }
}

function validateToken(token: string): string | undefined {
  return token.length <= 4096 && /^[\x21-\x7e]+$/.test(token) ? token : undefined;
}

function parseArguments(raw: unknown): unknown {
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return undefined;
  }
}

function validateUsageWindow(args: Record<string, unknown>): boolean {
  if (typeof args.window_start !== "string" || typeof args.window_end !== "string") return true;
  const start = Date.parse(args.window_start);
  const end = Date.parse(args.window_end);
  return Number.isFinite(start) && Number.isFinite(end) && end > start && end - start <= 14 * 24 * 60 * 60 * 1000;
}

function buildRequest(
  toolName: ToolName,
  args: Record<string, unknown>,
  config: AtlasCoreToolsConfig,
  requestId: string,
  bearer: string,
): { url: URL; init: RequestInit } {
  const route = ROUTES[toolName];
  const url = makeUrl(config.coreBaseUrl, toolName, args);
  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${bearer}`,
    "Cache-Control": "no-store",
    "X-Atlas-Contract-Version": "4",
    "X-Atlas-Schema-Version": "4",
    "X-Atlas-Request-Id": requestId,
    "X-Atlas-Capability": route.capability,
  };
  const init: RequestInit = {
    method: route.method,
    headers,
    redirect: "error",
  };
  if (route.method === "POST") {
    const body = canonicalJson(args);
    headers["Content-Type"] = "application/json";
    init.body = body;
  }
  const requestBytes = Buffer.byteLength(url.href, "utf8") + Buffer.byteLength(String(init.body ?? ""), "utf8");
  if (requestBytes > MAX_REQUEST_BYTES) throw new RangeError("request too large");
  return { url, init };
}

async function readBoundedResponse(response: Response, signal: AbortSignal): Promise<string> {
  const contentLength = response.headers.get("content-length");
  if (contentLength && /^\d+$/.test(contentLength) && Number(contentLength) > MAX_RESPONSE_BYTES) {
    throw new RangeError("response too large");
  }
  if (!response.body) throw new SyntaxError("missing response body");

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const next = await readWithAbort(reader, signal);
      if (next.done) break;
      total += next.value.byteLength;
      if (total > MAX_RESPONSE_BYTES) throw new RangeError("response too large");
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

async function readWithAbort(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  if (signal.aborted) throw signal.reason ?? new Error("aborted");
  let onAbort: (() => void) | undefined;
  try {
    return await Promise.race([
      reader.read(),
      new Promise<never>((_, reject) => {
        onAbort = () => reject(signal.reason ?? new Error("aborted"));
        signal.addEventListener("abort", onAbort, { once: true });
      }),
    ]);
  } finally {
    if (onAbort) signal.removeEventListener("abort", onAbort);
  }
}

function getResultCode(value: unknown): string {
  return isRecord(value) && isRecord(value.error) && typeof value.error.code === "string"
    ? value.error.code
    : "OK";
}

function errorCodeFromHttp(status: number): V4ErrorCode {
  if (status === 401 || status === 403) return "AUTH_REQUIRED";
  if (status === 413) return "PAYLOAD_TOO_LARGE";
  if (status === 429) return "QUOTA_EXCEEDED";
  if (status >= 500) return "UPSTREAM_UNAVAILABLE";
  return "BAD_REQUEST";
}

export function createAtlasToolExecutor(
  config: AtlasCoreToolsConfig,
  options: CoreClientOptions = {},
): (toolName: ToolName | string, rawArgs: unknown, context: ToolExecutionContext) => Promise<unknown> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const logger = options.logger ?? defaultLogger;
  const tokenResolver = options.tokenResolver ?? resolveBearerToken;
  const connectTimeoutMs = options.connectTimeoutMs ?? CONNECTION_TIMEOUT_MS;
  const responseTimeoutMs = options.responseTimeoutMs ?? RESPONSE_TIMEOUT_MS;
  const now = options.now ?? Date.now;
  const requestIdFactory = options.requestId ?? randomUUID;

  return async (toolNameValue, rawArgs, context) => {
    const startedAt = now();
    const callId = safeCallId(context.toolCallId);
    const recognizedTool = (TOOL_NAMES as readonly string[]).includes(toolNameValue);
    const toolName = recognizedTool ? (toolNameValue as ToolName) : TOOL_NAMES[0];
    const capability = recognizedTool ? CAPABILITIES[toolName] : null;
    const logToolName = recognizedTool ? toolName : "unknown";
    const requestId = requestIdFactory();
    let parsedArgs: unknown;
    let hashInput = rawArgs;
    let result: unknown;

    try {
      if (!recognizedTool) {
        result = makeV4Error("BAD_REQUEST", requestId, null, "Unknown ATLAS tool name.");
        return result;
      }
      if (context.signal?.aborted) {
        result = makeV4Error("TIMEOUT", requestId, capability, "Tool call was cancelled.", true);
        return result;
      }

      parsedArgs = parseArguments(rawArgs);
      hashInput = parsedArgs ?? rawArgs;
      if (parsedArgs === undefined || !checkArguments(toolName, parsedArgs)) {
        const code = hasUnknownProperties(toolName, parsedArgs) ? "UNKNOWN_FIELD" : "BAD_REQUEST";
        result = makeV4Error(code, requestId, capability, "Tool arguments do not match the strict V4 schema.");
        return result;
      }
      const args = parsedArgs as Record<string, unknown>;
      if (toolName === "atlas_get_usage_summary" && !validateUsageWindow(args)) {
        result = makeV4Error("BAD_REQUEST", requestId, capability, "Usage window must be ordered and no longer than 14 days.");
        return result;
      }

      const activeCapability = capability as Capability;
      const flagName = CAPABILITY_FLAGS[activeCapability] as keyof CapabilityFlags;
      if (config.enabled !== true || config.capabilities?.[flagName] !== true) {
        result = makeV4Error("CAPABILITY_UNAVAILABLE", requestId, activeCapability, "ATLAS capability is disabled.");
        return result;
      }

      const quota = context.quota ?? createTurnQuota();
      if (!quota.consume(toolName)) {
        result = makeV4Error("QUOTA_EXCEEDED", requestId, activeCapability, "ATLAS tool quota was reached.");
        return result;
      }

      let request: { url: URL; init: RequestInit };
      try {
        makeUrl(config.coreBaseUrl, toolName, args);
      } catch {
        result = makeV4Error("CONFIG_REQUIRED", requestId, capability, "ATLAS Core configuration is invalid.");
        return result;
      }
      const bearer = await tokenResolver(config);
      if (!bearer) {
        result = makeV4Error("AUTH_REQUIRED", requestId, capability, "Dedicated ATLAS Core bearer is unavailable.");
        return result;
      }
      try {
        request = buildRequest(toolName, args, config, requestId, bearer);
      } catch (error) {
        const code = error instanceof RangeError ? "PAYLOAD_TOO_LARGE" : "CONFIG_REQUIRED";
        result = makeV4Error(code, requestId, capability, "ATLAS Core request configuration is invalid.");
        return result;
      }

      const controller = new AbortController();
      let timeoutStage: "connect" | "response" | undefined;
      const onCallerAbort = () => controller.abort(context.signal?.reason ?? new Error("caller aborted"));
      context.signal?.addEventListener("abort", onCallerAbort, { once: true });
      if (context.signal?.aborted) onCallerAbort();
      const connectTimer = setTimeout(() => {
        timeoutStage = "connect";
        controller.abort(new TimeoutFailure("connect"));
      }, connectTimeoutMs);

      try {
        const response = await fetchImpl(request.url, { ...request.init, signal: controller.signal });
        clearTimeout(connectTimer);
        const responseTimer = setTimeout(() => {
          timeoutStage = "response";
          controller.abort(new TimeoutFailure("response"));
        }, responseTimeoutMs);
        let responseText: string;
        try {
          responseText = await readBoundedResponse(response, controller.signal);
        } finally {
          clearTimeout(responseTimer);
        }

        let payload: unknown;
        try {
          payload = JSON.parse(responseText) as unknown;
        } catch {
          result = makeV4Error("INTERNAL", requestId, capability, "ATLAS Core returned invalid JSON.", true);
          return result;
        }

        if (checkError(payload)) {
          if (
            payload.request_id !== requestId ||
            (payload.error.capability !== null && payload.error.capability !== capability)
          ) {
            result = makeV4Error("INTERNAL", requestId, capability, "ATLAS Core response request id did not match.");
            return result;
          }
          result = payload;
          return result;
        }
        if (!response.ok) {
          result = makeV4Error(
            errorCodeFromHttp(response.status),
            requestId,
            capability,
            "ATLAS Core rejected the tool request.",
            response.status >= 500 || response.status === 429,
          );
          return result;
        }
        if (!checkResult(toolName, payload) || !isRecord(payload) || payload.request_id !== requestId) {
          result = makeV4Error("INTERNAL", requestId, capability, "ATLAS Core response did not match the strict V4 result schema.");
          return result;
        }
        result = payload;
        return result;
      } catch (error) {
        if (timeoutStage || context.signal?.aborted) {
          result = makeV4Error("TIMEOUT", requestId, capability, "ATLAS Core request timed out or was cancelled.", true);
        } else if (error instanceof RangeError) {
          result = makeV4Error("PAYLOAD_TOO_LARGE", requestId, capability, "ATLAS Core response exceeded the size limit.");
        } else {
          result = makeV4Error("UPSTREAM_UNAVAILABLE", requestId, capability, "ATLAS Core is unavailable.", true);
        }
        return result;
      } finally {
        clearTimeout(connectTimer);
        context.signal?.removeEventListener("abort", onCallerAbort);
      }
    } catch {
      result = makeV4Error("INTERNAL", requestId, recognizedTool ? capability : null, "ATLAS tool request could not be processed.");
      return result;
    } finally {
      const finalResultCode = getResultCode(result);
      emitLog(logger, {
        call_id: callId,
        tool_name: logToolName,
        capability,
        request_hash: requestHash(hashInput),
        result_code: finalResultCode,
        latency_ms: Math.max(0, Math.round(now() - startedAt)),
      });
    }
  };
}

export function getCapabilityForTool(toolName: ToolName): Capability {
  return ROUTES[toolName].capability;
}

export function getRouteForTool(toolName: ToolName): string {
  return ROUTES[toolName].path;
}
