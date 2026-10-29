import { mkdtemp, chmod, rm, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { getToolPluginMetadata } from "openclaw/plugin-sdk/tool-plugin";
import { Value } from "typebox/value";
import { afterEach, describe, expect, it, vi } from "vitest";
import plugin from "./index.js";
import {
  ARGUMENT_SCHEMAS,
  CAPABILITIES,
  OUTPUT_SCHEMAS,
  TOOL_NAMES,
  checkError,
  type Capability,
  type ToolName,
} from "./contracts.js";
import {
  DEFAULT_CORE_BASE_URL,
  MAX_CALLS_PER_TURN,
  MAX_RESPONSE_BYTES,
  createAtlasToolExecutor,
  createTurnQuota,
  AtlasCoreToolsConfigSchema,
  type AtlasCoreToolsConfig,
} from "./core-client.js";
import requestsSource from "../../../../contracts/v4/tool-requests.schema.json" with { type: "json" };
import resultsSource from "../../../../contracts/v4/tool-results.schema.json" with { type: "json" };
import errorsSource from "../../../../contracts/v4/error.schema.json" with { type: "json" };

const packageRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const copiedRequests = JSON.parse(readFileSync(join(packageRoot, "contracts/v4/tool-requests.schema.json"), "utf8")) as Record<string, any>;
const copiedResults = JSON.parse(readFileSync(join(packageRoot, "contracts/v4/tool-results.schema.json"), "utf8")) as Record<string, any>;
const copiedErrors = JSON.parse(readFileSync(join(packageRoot, "contracts/v4/error.schema.json"), "utf8")) as Record<string, any>;
const manifest = JSON.parse(readFileSync(join(packageRoot, "openclaw.plugin.json"), "utf8")) as Record<string, any>;
const sourceRequests = requestsSource as unknown as Record<string, any>;
const sourceResults = resultsSource as unknown as Record<string, any>;
const sourceErrors = errorsSource as unknown as Record<string, any>;
const requestExampleByTool = new Map<string, Record<string, unknown>>(
  sourceRequests.examples.map((example: { tool_name: string; arguments: Record<string, unknown> }) => [example.tool_name, example.arguments]),
);
const resultExampleByTool = new Map<string, Record<string, unknown>>(
  sourceResults.examples.map((example: Record<string, unknown>) => [String(example.capability), example]),
);

const capabilityByTool: Record<ToolName, Capability> = CAPABILITIES;
const capabilityFlags: NonNullable<AtlasCoreToolsConfig["capabilities"]> = {
  dayPlanRead: true,
  activityStatusRead: true,
  planRevisionPropose: true,
  usageSummaryRead: true,
  goalProgressRead: true,
  trainingPlanRead: true,
};

function enabledConfig(overrides: Partial<AtlasCoreToolsConfig> = {}): AtlasCoreToolsConfig {
  return {
    enabled: true,
    coreBaseUrl: DEFAULT_CORE_BASE_URL,
    capabilities: { ...capabilityFlags },
    ...overrides,
  };
}

function successPayload(toolName: ToolName, requestId: string): Record<string, unknown> {
  const example = resultExampleByTool.get(capabilityByTool[toolName]);
  if (!example) throw new Error(`Missing result fixture for ${toolName}`);
  return { ...example, request_id: requestId };
}

function errorPayload(
  requestId: string,
  capability: Capability,
  code: string,
): Record<string, unknown> {
  const example = sourceErrors.examples[0] as Record<string, any>;
  return {
    ...example,
    request_id: requestId,
    error: { ...example.error, code, capability },
  };
}

function headerValue(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
}

function successfulFetch(toolName: ToolName, calls: Array<{ url: URL; init: RequestInit }>) {
  return vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    calls.push({ url, init: init ?? {} });
    const requestId = headerValue(init, "X-Atlas-Request-Id") ?? "";
    return new Response(JSON.stringify(successPayload(toolName, requestId)), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }) as unknown as typeof fetch;
}

function makeExecutor(
  config: AtlasCoreToolsConfig,
  fetchImpl: typeof fetch,
  extra: Parameters<typeof createAtlasToolExecutor>[1] = {},
) {
  return createAtlasToolExecutor(config, {
    fetchImpl,
    tokenResolver: async () => "unit-test-bearer",
    logger: () => undefined,
    ...extra,
  });
}

function validArgs(toolName: ToolName): Record<string, unknown> {
  const args = requestExampleByTool.get(toolName);
  if (!args) throw new Error(`Missing request fixture for ${toolName}`);
  return structuredClone(args);
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("V4 contract parity and static plugin metadata", () => {
  it("keeps the copied request, result, and error contracts byte-for-data identical to the repository sources", () => {
    expect(copiedRequests).toEqual(sourceRequests);
    expect(copiedResults).toEqual(sourceResults);
    expect(copiedErrors).toEqual(sourceErrors);
  });

  it("declares exactly the six contract tool names in manifest, parameters, and static metadata", () => {
    const expectedNames = [...TOOL_NAMES];
    expect(manifest.id).toBe("atlas_core_tools");
    expect(manifest.contracts.tools).toEqual(expectedNames);
    expect(Object.keys(manifest.toolMetadata)).toEqual(expectedNames);
    const metadata = getToolPluginMetadata(plugin);
    expect(metadata).toBeDefined();
    expect(metadata?.tools.map((entry) => entry.name)).toEqual(expectedNames);
    for (const toolName of TOOL_NAMES) {
      const entry = metadata?.tools.find((candidate) => candidate.name === toolName);
      expect(entry?.optional).toBe(true);
      expect(entry?.parameters).toEqual(ARGUMENT_SCHEMAS[toolName]);
      expect(entry?.outputSchema).toBeUndefined();
      expect(Value.Check(ARGUMENT_SCHEMAS[toolName], validArgs(toolName))).toBe(true);
      expect(Value.Check(OUTPUT_SCHEMAS[toolName], successPayload(toolName, "00000000-0000-4000-8000-000000000101"))).toBe(true);
    }
  });

  it("keeps every object schema closed and the config strict without embedding a credential", () => {
    function assertClosed(value: unknown): void {
      if (Array.isArray(value)) {
        value.forEach(assertClosed);
        return;
      }
      if (!value || typeof value !== "object") return;
      const schema = value as Record<string, unknown>;
      if (schema.type === "object") expect(schema.additionalProperties).toBe(false);
      Object.values(schema).forEach(assertClosed);
    }
    assertClosed(sourceRequests);
    assertClosed(sourceResults);
    assertClosed(sourceErrors);
    expect(manifest.configSchema.additionalProperties).toBe(false);
    expect(manifest.configSchema.properties.capabilities.additionalProperties).toBe(false);
    expect(manifest.configSchema.properties.enabled.default).toBe(false);
    expect(Value.Check(AtlasCoreToolsConfigSchema, { enabled: true, unexpected: "field" })).toBe(false);
    expect(JSON.stringify(manifest)).not.toMatch(/"(?:token|bearer|apiKey|secret)"\s*:/i);
  });
});

describe.each(TOOL_NAMES)("%s", (toolName) => {
  const name = toolName as ToolName;
  const args = validArgs(name);

  it("calls its fixed loopback route with a strict request and validates a V4 success result", async () => {
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    const fetchImpl = successfulFetch(name, calls);
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute(name, args, { toolCallId: "test-call-1", quota: createTurnQuota() });

    expect(result).toEqual(successPayload(name, headerValue(calls[0]?.init, "X-Atlas-Request-Id") ?? ""));
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url.hostname).toBe("127.0.0.1");
    expect(calls[0]?.url.port).toBe("8100");
    expect(calls[0]?.init.redirect).toBe("error");
    expect(calls[0]?.init.method).toBe(name === "atlas_propose_plan_revision" ? "POST" : "GET");
    expect(headerValue(calls[0]?.init, "Authorization")).toBe("Bearer unit-test-bearer");
    expect(headerValue(calls[0]?.init, "X-Atlas-Capability")).toBe(capabilityByTool[name]);
    expect(headerValue(calls[0]?.init, "X-Forwarded-For")).toBeNull();
  });

  it("returns a typed error and never calls Core for invalid or unknown arguments", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const badArgs = { ...args, url: "http://attacker.invalid/" };
    const result = await execute(name, badArgs, { toolCallId: "bad-args", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("UNKNOWN_FIELD");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("turns a typed Core error into a typed tool result", async () => {
    const fetchImpl = vi.fn(async (_input: string | URL | Request, init?: RequestInit) => {
      const requestId = headerValue(init, "X-Atlas-Request-Id") ?? "";
      return new Response(JSON.stringify(errorPayload(requestId, capabilityByTool[name], "BAD_REQUEST")), {
        status: 400,
        headers: { "content-type": "application/json" },
      });
    }) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute(name, args, { toolCallId: "core-error", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("BAD_REQUEST");
  });

  it("returns a typed timeout when Core does not return response headers", async () => {
    const fetchImpl = vi.fn((_input: string | URL | Request, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal;
      if (signal?.aborted) reject(signal.reason);
      else signal?.addEventListener("abort", () => reject(signal.reason), { once: true });
    })) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl, { connectTimeoutMs: 5, responseTimeoutMs: 10 });
    const result = await execute(name, args, { toolCallId: "connect-timeout", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("TIMEOUT");
  });

  it("returns AUTH_REQUIRED when the Core refuses the dedicated bearer", async () => {
    const fetchImpl = vi.fn(async (_input: string | URL | Request, init?: RequestInit) => {
      const requestId = headerValue(init, "X-Atlas-Request-Id") ?? "";
      return new Response(JSON.stringify(errorPayload(requestId, capabilityByTool[name], "AUTH_REQUIRED")), {
        status: 401,
        headers: { "content-type": "application/json" },
      });
    }) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute(name, args, { toolCallId: "auth-refused", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("AUTH_REQUIRED");
  });

  it("returns CAPABILITY_UNAVAILABLE without calling Core when its flag is off", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig({ capabilities: {} }), fetchImpl);
    const result = await execute(name, args, { toolCallId: "capability-off", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("CAPABILITY_UNAVAILABLE");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("enforces the six-call turn quota", async () => {
    if (name === "atlas_propose_plan_revision") return;
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    const execute = makeExecutor(enabledConfig(), successfulFetch(name, calls));
    const quota = createTurnQuota();
    for (let index = 0; index < MAX_CALLS_PER_TURN; index += 1) {
      const result = await execute(name, args, { toolCallId: `quota-${index}`, quota });
      expect(checkError(result)).toBe(false);
    }
    const blocked = await execute(name, args, { toolCallId: "quota-over", quota }) as any;
    expect(checkError(blocked)).toBe(true);
    expect(blocked.error.code).toBe("QUOTA_EXCEEDED");
    expect(calls).toHaveLength(MAX_CALLS_PER_TURN);
  });
});

describe("bounded dispatch and security guards", () => {
  it("allows at most one proposal in a turn, even when the first proposal returned successfully", async () => {
    const name: ToolName = "atlas_propose_plan_revision";
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    const execute = makeExecutor(enabledConfig(), successfulFetch(name, calls));
    const quota = createTurnQuota();
    const first = await execute(name, validArgs(name), { toolCallId: "proposal-1", quota });
    const second = await execute(name, validArgs(name), { toolCallId: "proposal-2", quota }) as any;
    expect(checkError(first)).toBe(false);
    expect(checkError(second)).toBe(true);
    expect(second.error.code).toBe("QUOTA_EXCEEDED");
    expect(calls).toHaveLength(1);
  });

  it("uses a response-body deadline as well as the connection deadline", async () => {
    const name: ToolName = "atlas_get_goal_progress";
    const fetchImpl = vi.fn(async () => new Response(new ReadableStream<Uint8Array>({ start() {} }))) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl, { connectTimeoutMs: 10, responseTimeoutMs: 5 });
    const result = await execute(name, validArgs(name), { toolCallId: "response-timeout", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("TIMEOUT");
  });

  it("applies the six-call quota across all tools in one shared turn context", async () => {
    const quota = createTurnQuota();
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    for (const name of TOOL_NAMES) {
      const execute = makeExecutor(enabledConfig(), successfulFetch(name, calls));
      const result = await execute(name, validArgs(name), { toolCallId: `cross-tool-${name}`, quota });
      expect(checkError(result)).toBe(false);
    }
    const blocked = await makeExecutor(enabledConfig(), successfulFetch("atlas_get_day_plan", calls))(
      "atlas_get_day_plan",
      validArgs("atlas_get_day_plan"),
      { toolCallId: "cross-tool-over", quota },
    ) as any;
    expect(checkError(blocked)).toBe(true);
    expect(blocked.error.code).toBe("QUOTA_EXCEEDED");
    expect(calls).toHaveLength(MAX_CALLS_PER_TURN);
  });

  it("rejects non-loopback hosts before reading credentials or calling fetch", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const tokenResolver = vi.fn(async () => "unit-test-bearer");
    const execute = createAtlasToolExecutor(enabledConfig({ coreBaseUrl: "http://example.com:8100" }), {
      fetchImpl,
      logger: () => undefined,
      tokenResolver,
    });
    const result = await execute("atlas_get_day_plan", validArgs("atlas_get_day_plan"), {
      toolCallId: "non-loopback",
      quota: createTurnQuota(),
    }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("CONFIG_REQUIRED");
    expect(tokenResolver).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects a free-form URL field instead of using it as a destination", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute("atlas_get_day_plan", {
      ...validArgs("atlas_get_day_plan"),
      url: "http://127.0.0.1:9999/internal/v4/agent/chat",
    }, { toolCallId: "url-injection", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("UNKNOWN_FIELD");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("enforces nested strictness, field bounds, and the 14-day usage rule", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const proposalArgs = validArgs("atlas_propose_plan_revision");
    (proposalArgs.changes as Array<Record<string, unknown>>)[0].unknown = "field";
    const badActivityArgs = { ...validArgs("atlas_get_activity_status"), activity_id: "not-a-uuid" };
    const usageArgs = {
      window_start: "2026-10-01T00:00:00Z",
      window_end: "2026-10-16T00:00:00Z",
    };
    const results = await Promise.all([
      execute("atlas_propose_plan_revision", proposalArgs, { toolCallId: "nested-unknown", quota: createTurnQuota() }),
      execute("atlas_get_activity_status", badActivityArgs, { toolCallId: "bad-uuid", quota: createTurnQuota() }),
      execute("atlas_get_usage_summary", usageArgs, { toolCallId: "long-window", quota: createTurnQuota() }),
    ]) as Array<Record<string, any>>;
    expect(results.every(checkError)).toBe(true);
    expect(results[0]?.error.code).toBe("BAD_REQUEST");
    expect(results[1]?.error.code).toBe("BAD_REQUEST");
    expect(results[2]?.error.code).toBe("BAD_REQUEST");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects unknown tool names and malformed JSON with typed V4 errors", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const unknown = await execute("atlas_run_shell", {}, { toolCallId: "unknown-tool", quota: createTurnQuota() }) as any;
    const invalidJson = await execute("atlas_get_day_plan", "{invalid", { toolCallId: "bad-json", quota: createTurnQuota() }) as any;
    expect(checkError(unknown)).toBe(true);
    expect(unknown.error.code).toBe("BAD_REQUEST");
    expect(checkError(invalidJson)).toBe(true);
    expect(invalidJson.error.code).toBe("BAD_REQUEST");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects an unconfigured dedicated bearer", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const execute = createAtlasToolExecutor(enabledConfig(), {
      fetchImpl,
      logger: () => undefined,
      tokenResolver: async () => undefined,
    });
    const result = await execute("atlas_get_day_plan", validArgs("atlas_get_day_plan"), {
      toolCallId: "missing-token",
      quota: createTurnQuota(),
    }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("AUTH_REQUIRED");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("reads a dedicated bearer only from a 0600 file owned by the test process", async () => {
    const directory = await mkdtemp(join(process.cwd(), ".atlas-token-test-"));
    const tokenFile = join(directory, "core-token");
    try {
      await writeFile(tokenFile, "unit-test-file-bearer\n", { mode: 0o600 });
      await chmod(tokenFile, 0o600);
      vi.stubEnv("ATLAS_CORE_TOOLS_TOKEN", "");
      const calls: Array<{ url: URL; init: RequestInit }> = [];
      const name: ToolName = "atlas_get_day_plan";
      const execute = createAtlasToolExecutor(enabledConfig({ tokenFile }), {
        fetchImpl: successfulFetch(name, calls),
        logger: () => undefined,
      });
      const result = await execute(name, validArgs(name), { toolCallId: "file-token", quota: createTurnQuota() });
      expect(checkError(result)).toBe(false);
      expect(headerValue(calls[0]?.init, "Authorization")).toBe("Bearer unit-test-file-bearer");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("reads the dedicated bearer from the configured Gateway environment variable", async () => {
    vi.stubEnv("ATLAS_CORE_TOOLS_TOKEN", "unit-test-env-bearer");
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    const name: ToolName = "atlas_get_day_plan";
    const execute = createAtlasToolExecutor(enabledConfig(), {
      fetchImpl: successfulFetch(name, calls),
      logger: () => undefined,
    });
    const result = await execute(name, validArgs(name), { toolCallId: "env-token", quota: createTurnQuota() });
    expect(checkError(result)).toBe(false);
    expect(headerValue(calls[0]?.init, "Authorization")).toBe("Bearer unit-test-env-bearer");
  });

  it("bounds Core response size", async () => {
    const name: ToolName = "atlas_get_day_plan";
    const fetchImpl = vi.fn(async () => new Response("x".repeat(MAX_RESPONSE_BYTES + 1))) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute(name, validArgs(name), { toolCallId: "oversized-response", quota: createTurnQuota() }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("PAYLOAD_TOO_LARGE");
  });

  it("logs only the allowlisted redacted fields", async () => {
    const name: ToolName = "atlas_propose_plan_revision";
    const calls: Array<{ url: URL; init: RequestInit }> = [];
    const events: Array<Record<string, unknown>> = [];
    const logger = (event: Record<string, unknown>) => events.push(event);
    const privateTitle = "PRIVATE_TITLE_MUST_NEVER_APPEAR_IN_LOGS";
    const args = validArgs(name);
    (args.changes as Array<Record<string, unknown>>)[0] = {
      op: "create_activity",
      client_ref: "unit_test",
      kind: "FOCUS",
      title: privateTitle,
      start_minute: 640,
      duration_minutes: 20,
      priority: "OPTIONAL",
    };
    const execute = createAtlasToolExecutor(enabledConfig(), {
      fetchImpl: successfulFetch(name, calls),
      logger,
      tokenResolver: async () => "unit-test-bearer",
    });
    await execute(name, args, { toolCallId: "redaction-test", quota: createTurnQuota() });
    expect(events).toHaveLength(1);
    expect(Object.keys(events[0] ?? {}).sort()).toEqual([
      "call_id",
      "capability",
      "latency_ms",
      "request_hash",
      "result_code",
      "tool_name",
    ]);
    expect(JSON.stringify(events)).not.toContain(privateTitle);
    expect(JSON.stringify(events)).not.toContain("changes");
    expect(events[0]?.request_hash).toMatch(/^[a-f0-9]{64}$/);
  });

  it("turns an invalid successful Core payload into a typed error", async () => {
    const fetchImpl = vi.fn(async (_input: string | URL | Request, init?: RequestInit) => new Response(
      JSON.stringify({ ...successPayload("atlas_get_day_plan", headerValue(init, "X-Atlas-Request-Id") ?? ""), leaked: true }),
      { status: 200 },
    )) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute("atlas_get_day_plan", validArgs("atlas_get_day_plan"), {
      toolCallId: "invalid-core-schema",
      quota: createTurnQuota(),
    }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("INTERNAL");
  });

  it("turns invalid Core JSON into a typed V4 error", async () => {
    const fetchImpl = vi.fn(async () => new Response("{invalid-json", { status: 200 })) as unknown as typeof fetch;
    const execute = makeExecutor(enabledConfig(), fetchImpl);
    const result = await execute("atlas_get_day_plan", validArgs("atlas_get_day_plan"), {
      toolCallId: "invalid-core-json",
      quota: createTurnQuota(),
    }) as any;
    expect(checkError(result)).toBe(true);
    expect(result.error.code).toBe("INTERNAL");
  });
});
