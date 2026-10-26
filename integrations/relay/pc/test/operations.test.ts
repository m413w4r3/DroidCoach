import { describe, expect, it } from "vitest";
import { BackendClient, assertLoopbackHttpUrl } from "../src/backend.js";
import { RemoteError } from "../src/errors.js";
import { OperationExecutor } from "../src/operations.js";
import { request } from "./helpers.js";

const STATE = { relayConnected: () => true, backendConfigured: () => true };

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
    const backend = new BackendClient({ baseUrl: "http://127.0.0.1:8100", serviceToken: "synthetic" });
    await expect(backend.execute("sport.plan.read", { local_date: "2026-01-01" })).rejects.toMatchObject({ code: "OPERATION_UNAVAILABLE" });
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

  it("reports backend absence and succeeds when the loopback backend returns", async () => {
    let available = false;
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: "synthetic-service-token",
      fetcher: async () => {
        if (!available) throw new TypeError("connection refused");
        return new Response(JSON.stringify({ connected: true, adapter: "mock" }), { status: 200 });
      },
    });
    await expect(backend.execute("coach.status", {})).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: true });
    available = true;
    expect(await backend.execute("coach.status", {})).toMatchObject({ connected: true });
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
