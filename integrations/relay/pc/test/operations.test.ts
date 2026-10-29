import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { BackendClient, assertLoopbackHttpUrl } from "../src/backend.js";
import { RemoteError } from "../src/errors.js";
import { OperationExecutor } from "../src/operations.js";
import { PROTOCOL_V2, type Operation } from "../src/protocol.js";
import { FakeSecureChannel, hello, makeContext, paired, request } from "./helpers.js";

const STATE = { relayConnected: () => true, backendConfigured: () => true };
const ID_A = "00000000-0000-4000-8000-000000000101";
const ID_B = "00000000-0000-4000-8000-000000000102";
const ID_C = "00000000-0000-4000-8000-000000000103";
const ID_D = "00000000-0000-4000-8000-000000000104";
const ID_E = "00000000-0000-4000-8000-000000000105";
const ID_F = "00000000-0000-4000-8000-000000000106";
const ID_G = "00000000-0000-4000-8000-000000000107";
const ID_H = "00000000-0000-4000-8000-000000000108";
const ID_I = "00000000-0000-4000-8000-000000000109";
const ID_J = "00000000-0000-4000-8000-000000000110";
const V4_SENT_AT = "2026-10-29T08:02:04Z";

function v4Payloads(): Array<{ operation: Operation; payload: Record<string, unknown>; path: string }> {
  const syncBase = (capability: string, requestId: string) => ({
    contract_version: 4,
    schema_version: 4,
    request_id: requestId,
    capability,
    sent_at: V4_SENT_AT,
    consent_version: 1,
    idempotency_key: requestId,
  });
  return [
    {
      operation: "v4.plan.snapshot.upsert",
      path: "/v4/sync/day-plan-snapshots",
      payload: {
        ...syncBase("day_plan.snapshot.write", ID_A),
        data: {
          plan_id: ID_B,
          local_date: "2026-10-29",
          timezone: "Europe/Paris",
          version: 1,
          current_revision_id: ID_C,
          state: "APPROVED",
          origin: "LOCAL",
          observed_at: V4_SENT_AT,
          activities: [{
            activity_id: ID_D,
            kind: "FOCUS",
            title: "Synthetic focus",
            status: "PLANNED",
            scheduled_local: { local_date: "2026-10-29", start_minute: 540, duration_minutes: 30, timezone: "Europe/Paris" },
            priority: "IMPORTANT",
            goal_id: null,
            continuity_id: null,
            supersedes_activity_id: null,
            status_provenance: "UNKNOWN",
            status_decided_at: null,
          }],
        },
      },
    },
    {
      operation: "v4.activity-status.events.append",
      path: "/v4/sync/activity-status-events",
      payload: {
        ...syncBase("activity_status.write", ID_E),
        data: {
          event_id: ID_F,
          sequence: 1,
          plan_id: ID_B,
          activity_id: ID_D,
          continuity_id: null,
          plan_version: 1,
          from_status: "PLANNED",
          to_status: "IN_PROGRESS",
          provenance: "CONFIRMED",
          source: "USER_UI",
          decided_at: V4_SENT_AT,
          observed_at: V4_SENT_AT,
          focus_session_id: null,
        },
      },
    },
    {
      operation: "v4.usage.summary.upsert",
      path: "/v4/sync/usage-summaries",
      payload: {
        ...syncBase("usage_summary.write", ID_G),
        data: {
          window_start: "2026-10-29T00:00:00Z",
          window_end: V4_SENT_AT,
          timezone: "Europe/Paris",
          total_foreground_ms: 0,
          apps: [],
          coverage: { observed_ms: 0, blind_ms: 0, events_seen: 0, truncated: false },
          generated_at: V4_SENT_AT,
          consent_version: 1,
        },
      },
    },
    {
      operation: "v4.goal.snapshot.upsert",
      path: "/v4/sync/goal-progress-snapshots",
      payload: { ...syncBase("goal_progress.write", ID_H), data: { goals_version: 1, observed_at: V4_SENT_AT, goals: [] } },
    },
    {
      operation: "v4.training.snapshot.upsert",
      path: "/v4/sync/training-snapshots",
      payload: {
        ...syncBase("training_plan.write", ID_I),
        data: { provider: "wger", source: "ANDROID_SYNC", sync_state: "FRESH", observed_at: V4_SENT_AT, source_updated_at: null, routines: [], sessions: [], results: [] },
      },
    },
    {
      operation: "v4.proposals.pull",
      path: "/v4/agent/proposals/pull",
      payload: { contract_version: 4, schema_version: 4, request_id: ID_J, capability: "proposal.ack", consent_version: 1, page_size: 10, cursor: null },
    },
    {
      operation: "v4.proposals.ack",
      path: `/v4/agent/proposals/${ID_B}/ack`,
      payload: { contract_version: 4, schema_version: 4, ack_id: ID_C, capability: "proposal.ack", proposal_id: ID_B, sequence: 1, state: "UNCERTAIN", result_code: "RESULT_UNCERTAIN", occurred_at: V4_SENT_AT, consent_version: 1 },
    },
    {
      operation: "v4.coach.chat.request",
      path: "/v4/agent/chat",
      payload: { contract_version: 4, schema_version: 4, request_id: ID_D, capability: "coach.chat", sent_at: V4_SENT_AT, conversation_id: "synthetic-conversation", message: "Synthetic chat request", consent_version: 1, shared_categories: [] },
    },
    {
      operation: "v4.consent.state.upsert",
      path: "/v4/sync/consent-state",
      payload: { contract_version: 4, schema_version: 4, request_id: ID_F, capability: "consent.state.write", sent_at: V4_SENT_AT, category: "DAY_PLAN", consent_version: 1, state: "GRANTED", idempotency_key: ID_F },
    },
  ];
}

function v4Request(operation: Operation, payload: Record<string, unknown>) {
  const requestId = String(payload.request_id ?? ID_B);
  const idempotencyKey = String(payload.idempotency_key ?? payload.ack_id ?? payload.request_id ?? ID_B);
  return request("00000000-0000-4000-8000-000000000199", operation, payload, {
    protocol: PROTOCOL_V2,
    request_id: requestId,
    idempotency_key: idempotencyKey,
  });
}
const PLAN_REVISION_PAYLOAD = {
  schema_version: 3,
  request_id: "550e8400-e29b-41d4-a716-446655440000",
  conversation_id: null,
  message: "Adjust the synthetic plan",
  plan_context_consented: false,
  mandate_summary: { id: "synthetic-mandate", version: 1, mode: "proposals_only" },
  plan: {
    plan_id: "550e8400-e29b-41d4-a716-446655440001",
    local_date: "2026-01-01",
    timezone: "Europe/Paris",
    version: 1,
    state: "DRAFT",
    activities: [{
      activity_id: "550e8400-e29b-41d4-a716-446655440002",
      kind: "FOCUS",
      title: "Synthetic focus",
      start_minute: 540,
      duration_minutes: 30,
      priority: "OPTIONAL",
      goal_id: null,
    }],
  },
};

describe("closed operations and backend adapter", () => {
  it("maps health and coach status only to their fixed backend paths", async () => {
    const calls: Array<{ url: string; auth: string | null }> = [];
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async (input, init) => {
        calls.push({ url: String(input), auth: new Headers(init?.headers).get("authorization") });
        return new Response(JSON.stringify({ status: "ok", connected: true }), { status: 200 });
      },
    });
    expect(await backend.execute("backend.health", {})).toEqual({ status: "ok" });
    expect(await backend.execute("coach.status", {})).toMatchObject({ connected: true });
    expect(calls[0]).toEqual({ url: "http://127.0.0.1:8100/health", auth: null });
    expect(calls[1]).toEqual({ url: "http://127.0.0.1:8100/v2/agent/status", auth: "Bearer synthetic-service-token" });
  });

  it("refuses non-loopback backend URLs and generic route inputs", async () => {
    expect(() => assertLoopbackHttpUrl("http://example.com:8000")).toThrow();
    expect(() => assertLoopbackHttpUrl("http://127.0.0.1:8100/private?x=1")).toThrow();
    const calls: string[] = [];
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic",
      fetcher: async (input) => {
        calls.push(String(input));
        return new Response(JSON.stringify({ schema_version: 1 }), { status: 200 });
      },
    });
    await backend.execute("sport.plan.read", { local_date: "2026-01-01", timezone: "Europe/Paris" });
    expect(calls[0]).toBe("http://127.0.0.1:8100/v1/sport/plan?local_date=2026-01-01&timezone=Europe%2FParis");
    const executor = new OperationExecutor(backend, STATE);
    const bad = request("2d6f4701-21df-4f4f-a109-1028300572c9", "sport.plan.read", { local_date: "2026-01-01" });
    await expect(executor.execute("device-a", bad.session_id, bad)).rejects.toMatchObject({ code: "INVALID_REQUEST" });
  });

  it("replays an identical request from the ten-minute cache and rejects conflicts", async () => {
    let backendCalls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      fetcher: async () => {
        backendCalls += 1;
        return new Response(JSON.stringify({ status: "ok" }), { status: 200 });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "backend.health");
    const response = await executor.execute("device-a", first.session_id, first);
    const replay = await executor.execute("device-a", first.session_id, first);
    expect(response.replayed).toBe(false);
    expect(replay.replayed).toBe(true);
    expect(backendCalls).toBe(1);

    const conflict = { ...first, created_at: new Date(Date.now() + 1).toISOString() };
    await expect(executor.execute("device-a", first.session_id, conflict)).rejects.toMatchObject({ code: "IDEMPOTENCY_CONFLICT" });
    const reusedKey = { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" };
    expect((await executor.execute("device-a", first.session_id, reusedKey)).replayed).toBe(true);
    expect(backendCalls).toBe(1);
  });

  it("shares an in-flight idempotent backend request", async () => {
    let backendCalls = 0;
    let release!: () => void;
    const delay = new Promise<void>((resolve) => { release = resolve; });
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      fetcher: async () => {
        backendCalls += 1;
        await delay;
        return new Response(JSON.stringify({ status: "ok" }), { status: 200 });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "backend.health");
    const second = { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" };
    const initial = executor.execute("device-a", first.session_id, first);
    const replay = executor.execute("device-a", first.session_id, second);
    release();
    const [, replayed] = await Promise.all([initial, replay]);
    expect(replayed.replayed).toBe(true);
    expect(backendCalls).toBe(1);
  });

  it("replays a successful POST with the same idempotency key and rejects different content", async () => {
    let backendCalls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        backendCalls += 1;
        return new Response(JSON.stringify({ proposal_id: "synthetic-proposal" }), { status: 200 });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "plan.revision.request", PLAN_REVISION_PAYLOAD, { idempotency_key: "post-replay-key" });
    const second = { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" };

    const result = await executor.execute("device-a", first.session_id, first);
    const replay = await executor.execute("device-a", second.session_id, second);
    expect(result.result).toEqual({ proposal_id: "synthetic-proposal" });
    expect(replay).toMatchObject({ replayed: true, result: { proposal_id: "synthetic-proposal" } });
    expect(backendCalls).toBe(1);

    const conflict = { ...second, payload: { ...PLAN_REVISION_PAYLOAD, message: "Different content" }, request_id: "7f6e8701-5d90-4f24-89bf-106e6b6ed0be" };
    await expect(executor.execute("device-a", conflict.session_id, conflict)).rejects.toMatchObject({ code: "IDEMPOTENCY_CONFLICT" });
    expect(backendCalls).toBe(1);
  });

  it("shares a concurrent effectful request for the same key", async () => {
    let backendCalls = 0;
    let notifyStarted!: () => void;
    let release!: () => void;
    const started = new Promise<void>((resolve) => { notifyStarted = resolve; });
    const delay = new Promise<void>((resolve) => { release = resolve; });
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        backendCalls += 1;
        notifyStarted();
        await delay;
        return new Response(JSON.stringify({ proposal_id: "synthetic-proposal" }), { status: 200 });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "plan.revision.request", PLAN_REVISION_PAYLOAD, { idempotency_key: "concurrent-post-key" });
    const second = { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" };
    const initial = executor.execute("device-a", first.session_id, first);
    await started;
    const concurrent = executor.execute("device-a", second.session_id, second);
    release();
    const [firstResponse, secondResponse] = await Promise.all([initial, concurrent]);
    expect(firstResponse.replayed).toBe(false);
    expect(secondResponse.replayed).toBe(true);
    expect(backendCalls).toBe(1);
  });

  it("locks a dispatched POST after its response is lost, including across executor restart", async () => {
    const dir = await mkdtemp(join(tmpdir(), "atlas-idempotency-test-"));
    const journalPath = join(dir, "operations.json");
    let effects = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        effects += 1;
        throw new TypeError("synthetic connection dropped after backend effect");
      },
    });
    const firstExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "plan.revision.request", PLAN_REVISION_PAYLOAD, { idempotency_key: "lost-post-key" });
    try {
      await expect(firstExecutor.execute("device-a", first.session_id, first)).rejects.toMatchObject({
        code: "BACKEND_UNAVAILABLE",
        retryable: false,
        message: "backend outcome is uncertain; retry is blocked until the 10-minute idempotency window expires",
      });

      const retry = { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" };
      await expect(firstExecutor.execute("device-a", retry.session_id, retry)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      const different = { ...retry, payload: { ...PLAN_REVISION_PAYLOAD, message: "Different content" }, request_id: "7f6e8701-5d90-4f24-89bf-106e6b6ed0be" };
      await expect(firstExecutor.execute("device-a", different.session_id, different)).rejects.toMatchObject({ code: "IDEMPOTENCY_CONFLICT" });

      const restartedExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
      await restartedExecutor.initialize();
      await expect(restartedExecutor.execute("device-a", retry.session_id, retry)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(effects).toBe(1);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("replays a completed POST from the durable idempotency journal after restart", async () => {
    const dir = await mkdtemp(join(tmpdir(), "atlas-idempotency-test-"));
    const journalPath = join(dir, "operations.json");
    let backendCalls = 0;
    const firstBackend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        backendCalls += 1;
        return new Response(JSON.stringify({ proposal_id: "synthetic-proposal" }), { status: 200 });
      },
    });
    const firstExecutor = new OperationExecutor(firstBackend, STATE, Date.now, journalPath);
    const first = request("2d6f4701-21df-4f4f-a109-1028300572c8", "plan.revision.request", PLAN_REVISION_PAYLOAD, { idempotency_key: "completed-post-key" });
    try {
      await firstExecutor.execute("device-a", first.session_id, first);
      const restartedExecutor = new OperationExecutor(firstBackend, STATE, Date.now, journalPath);
      const replay = await restartedExecutor.execute("device-a", "d6e1603a-35c0-4fba-9c6a-d0509e990d3f", { ...first, request_id: "d6e1603a-35c0-4fba-9c6a-d0509e990d3f" });
      expect(replay).toMatchObject({ replayed: true, result: { proposal_id: "synthetic-proposal" } });
      expect(restartedExecutor.resultFor("device-a", first.request_id)).toMatchObject({ result: { proposal_id: "synthetic-proposal" } });
      expect(backendCalls).toBe(1);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("reports backend absence and succeeds when the loopback backend returns", async () => {
    let available = false;
    let calls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        calls += 1;
        if (!available) throw new TypeError("connection refused");
        return new Response(JSON.stringify({ connected: true, adapter: "mock" }), { status: 200 });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const status = request("2d6f4701-21df-4f4f-a109-1028300572c8", "coach.status", {}, { idempotency_key: "synthetic-get-retry" });
    await expect(executor.execute("device-a", status.session_id, status)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: true });
    available = true;
    expect(await executor.execute("device-a", status.session_id, status)).toMatchObject({ replayed: false, result: { connected: true } });
    expect(calls).toBe(2);
  });

  it("uses typed errors for oversized responses and missing service credentials", async () => {
    const huge = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => new Response(JSON.stringify({ value: "x".repeat(270_000) }), { status: 200 }),
    });
    await expect(huge.execute("coach.status", {})).rejects.toMatchObject({ code: "PAYLOAD_TOO_LARGE" });
    const unauthenticated = new BackendClient({ baseUrl: "http://127.0.0.1:8100" });
    await expect(unauthenticated.execute("coach.status", {})).rejects.toMatchObject({ code: "OPERATION_UNAVAILABLE" });
  });

  it("keeps V4 gated off in the production-default backend configuration", async () => {
    let calls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => { calls += 1; return new Response("{}", { status: 200 }); },
    });
    const example = v4Payloads().find((item) => item.operation === "v4.coach.chat.request")!;
    await expect(backend.execute(example.operation, example.payload, undefined, "paired-device-synthetic")).rejects.toMatchObject({ code: "OPERATION_UNAVAILABLE" });
    expect(calls).toBe(0);
  });

  it("dispatches every V4 operation to its fixed Core route with session-selected identity", async () => {
    const calls: Array<{ url: string; method: string; body: string | null; authorization: string | null; capability: string | null }> = [];
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      v4Enabled: true,
      pairedDeviceToken: (pairedDeviceId) => pairedDeviceId === "paired-device-synthetic" ? "synthetic-core-android-token" : undefined,
      fetcher: async (input, init) => {
        calls.push({
          url: String(input),
          method: String(init?.method),
          body: typeof init?.body === "string" ? init.body : null,
          authorization: new Headers(init?.headers).get("authorization"),
          capability: new Headers(init?.headers).get("x-atlas-capability"),
        });
        return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { "content-type": "application/json" } });
      },
    });
    const executor = new OperationExecutor(backend, STATE);
    const entries = v4Payloads();
    for (const entry of entries) {
      await executor.execute("paired-device-synthetic", ID_C, v4Request(entry.operation, entry.payload));
    }
    expect(calls.map((call) => new URL(call.url).pathname)).toEqual(entries.map((entry) => entry.path));
    expect(calls.every((call) => call.method === "POST" && call.authorization === "Bearer synthetic-core-android-token")).toBe(true);
    expect(calls.map((call) => call.capability)).toEqual([
      "day_plan.snapshot.write", "activity_status.write", "usage_summary.write", "goal_progress.write",
      "training_plan.write", "proposal.ack", "proposal.ack", "coach.chat", "consent.state.write",
    ]);
    const chat = calls.find((call) => new URL(call.url).pathname === "/v4/agent/chat");
    expect(chat?.url).toBe("http://127.0.0.1:8100/v4/agent/chat");
    expect(chat?.url).not.toContain("/v2/agent/chat");
    expect(calls.every((call) => !JSON.parse(call.body ?? "{}").device_id)).toBe(true);
  });

  it("rejects unknown V4 fields and invalid schemas before fetch", async () => {
    let calls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      v4Enabled: true,
      pairedDeviceToken: () => "synthetic-core-android-token",
      fetcher: async () => { calls += 1; return new Response("{}", { status: 200 }); },
    });
    const executor = new OperationExecutor(backend, STATE);
    const valid = v4Payloads()[0]!;
    const withUnknownRoot = { ...valid.payload, client_path: "/v2/agent/chat" };
    await expect(executor.execute("paired-device-synthetic", ID_C, v4Request(valid.operation, withUnknownRoot))).rejects.toMatchObject({ code: "INVALID_REQUEST" });

    const withUnknownNested = structuredClone(valid.payload);
    (((withUnknownNested.data as Record<string, unknown>).activities as Record<string, unknown>[])[0]!).private_note = "synthetic";
    await expect(executor.execute("paired-device-synthetic", ID_C, v4Request(valid.operation, withUnknownNested))).rejects.toMatchObject({ code: "INVALID_REQUEST" });

    const wrongCapability = { ...valid.payload, capability: "client.chosen.route" };
    await expect(executor.execute("paired-device-synthetic", ID_C, v4Request(valid.operation, wrongCapability))).rejects.toMatchObject({ code: "INVALID_REQUEST" });
    expect(calls).toBe(0);
  });

  it("negotiates V2, returns typed operation and capability errors, and keeps the session open", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context, ["sync.status"]);
      const channel = new FakeSecureChannel();
      const peer = context.manager.create(channel);
      const invalidHello = { ...hello(device.deviceId, device.token, { protocol: PROTOCOL_V2, capabilities: ["sync.status"] }), capabilities: ["v4.unknown"] };
      await peer.receive(JSON.stringify(invalidHello));
      expect(channel.lastMessage()).toMatchObject({ protocol: PROTOCOL_V2, kind: "error", error: { code: "OPERATION_UNAVAILABLE" } });
      expect(channel.closeEvents).toHaveLength(0);

      await peer.receive(JSON.stringify(hello(device.deviceId, device.token, { protocol: PROTOCOL_V2, capabilities: ["sync.status", "v4.day_plan.snapshot.write"] })));
      expect(channel.lastMessage()).toMatchObject({ protocol: PROTOCOL_V2, kind: "hello_ok", capabilities: ["sync.status"] });
      const sessionId = String(channel.lastMessage().session_id);

      const unknownOperation = { ...v4Request("v4.unknown" as Operation, {}), session_id: sessionId };
      await peer.receive(JSON.stringify(unknownOperation));
      expect(channel.lastMessage()).toMatchObject({ protocol: PROTOCOL_V2, kind: "error", error: { code: "UNKNOWN_OPERATION" } });
      expect(channel.closeEvents).toHaveLength(0);

      const denied = { ...v4Request("v4.plan.snapshot.upsert", {}), session_id: sessionId };
      await peer.receive(JSON.stringify(denied));
      expect(channel.lastMessage()).toMatchObject({ protocol: PROTOCOL_V2, kind: "error", error: { code: "OPERATION_UNAVAILABLE" } });
      expect(channel.closeEvents).toHaveLength(0);

      await peer.receive(JSON.stringify({ ...request(sessionId, "sync.status"), protocol: PROTOCOL_V2 }));
      expect(channel.lastMessage()).toMatchObject({ protocol: PROTOCOL_V2, kind: "response", operation: "sync.status" });
      expect(channel.closeEvents).toHaveLength(0);
      peer.close();
    } finally {
      await context.dispose();
    }
  });

  it("redacts V4 chat content from the durable journal and never replays after restart", async () => {
    const dir = await mkdtemp(join(tmpdir(), "atlas-v4-chat-journal-test-"));
    const journalPath = join(dir, "operations.json");
    const privateMessage = "synthetic-private-chat-marker";
    const privateReply = "synthetic-private-reply-marker";
    let backendCalls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      v4Enabled: true,
      pairedDeviceToken: () => "synthetic-core-android-token",
      fetcher: async () => {
        backendCalls += 1;
        return new Response(JSON.stringify({ reply: privateReply }), { status: 200 });
      },
    });
    const firstExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
    const payload = { ...v4Payloads().find((item) => item.operation === "v4.coach.chat.request")!.payload, message: privateMessage };
    const chatRequest = v4Request("v4.coach.chat.request", payload);
    try {
      await firstExecutor.execute("paired-device-synthetic", ID_C, chatRequest);
      const journalText = await (await import("node:fs/promises")).readFile(journalPath, "utf8");
      expect(journalText).not.toContain(privateMessage);
      expect(journalText).not.toContain(privateReply);
      expect(JSON.parse(journalText).entries[0]).toMatchObject({ operation: "v4.coach.chat.request", outcome: "completed_redacted" });
      expect(JSON.parse(journalText).entries[0]).not.toHaveProperty("response");

      await expect(firstExecutor.execute("paired-device-synthetic", ID_C, chatRequest)).resolves.toMatchObject({ replayed: true });
      const restartedExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
      await expect(restartedExecutor.execute("paired-device-synthetic", "00000000-0000-4000-8000-000000000198", chatRequest)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(backendCalls).toBe(1);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("keeps an uncertain V4 result blocked across restart without persisting chat text", async () => {
    const dir = await mkdtemp(join(tmpdir(), "atlas-v4-chat-uncertain-test-"));
    const journalPath = join(dir, "operations.json");
    const privateMessage = "synthetic-uncertain-chat-marker";
    let backendCalls = 0;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      v4Enabled: true,
      pairedDeviceToken: () => "synthetic-core-android-token",
      fetcher: async () => { backendCalls += 1; throw new TypeError("synthetic transport loss after dispatch"); },
    });
    const firstExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
    const payload = { ...v4Payloads().find((item) => item.operation === "v4.coach.chat.request")!.payload, message: privateMessage };
    const chatRequest = v4Request("v4.coach.chat.request", payload);
    try {
      await expect(firstExecutor.execute("paired-device-synthetic", ID_C, chatRequest)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      const journalText = await (await import("node:fs/promises")).readFile(journalPath, "utf8");
      expect(journalText).not.toContain(privateMessage);
      expect(JSON.parse(journalText).entries[0]).toMatchObject({ operation: "v4.coach.chat.request", outcome: "uncertain" });

      const restartedExecutor = new OperationExecutor(backend, STATE, Date.now, journalPath);
      await expect(restartedExecutor.execute("paired-device-synthetic", "00000000-0000-4000-8000-000000000198", chatRequest)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(backendCalls).toBe(1);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
