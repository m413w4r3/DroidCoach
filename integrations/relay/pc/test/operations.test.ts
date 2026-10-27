import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { BackendClient, assertLoopbackHttpUrl } from "../src/backend.js";
import { RemoteError } from "../src/errors.js";
import { OperationExecutor } from "../src/operations.js";
import { request } from "./helpers.js";

const STATE = { relayConnected: () => true, backendConfigured: () => true };
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
});
