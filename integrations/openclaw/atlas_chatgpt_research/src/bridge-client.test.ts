import { describe, expect, it, vi } from "vitest";
import { BridgeClient, type BridgeConfig } from "./bridge-client.js";
import type { ResearchParameters } from "./contracts.js";

const config: BridgeConfig = {
  bridgeUrl: "http://127.0.0.1:8001",
  apiKey: "test-bridge-key",
};
const params: ResearchParameters = {
  topic: "Open source accessibility standards",
  freshness: "week",
  max_results: 3,
};

function jsonResponse(status: number, body: unknown, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(body), { status, headers });
}

function completed(id = "run-1"): object {
  return {
    id,
    object: "response",
    status: "completed",
    output_text: JSON.stringify({ summary: "A concise answer.", facts: ["Fact one."] }),
    metadata: { web_search_mode: "ui_tool", visible_citations: [] },
  };
}

describe("BridgeClient", () => {
  it("submits a bounded request with the stable idempotency key", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(200, completed()));
    const client = new BridgeClient(config, { fetchImpl: fetchMock });
    const result = await client.research(params, "tool-call-1");

    expect(result.id).toBe("run-1");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe("http://127.0.0.1:8001/v1/bridge/runs");
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("X-Idempotency-Key")).toBe("tool-call-1");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer test-bridge-key");
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
    expect(body).toMatchObject({ background: true, web_search: true, request_id: "tool-call-1" });
    expect(body.input).toContain("Open source accessibility standards");
  });

  it("polls queued runs with a 1s to 5s bounded backoff", async () => {
    const states = ["running", "running", "running", "running", "completed"];
    const sleeps: number[] = [];
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith("/v1/bridge/runs")) return jsonResponse(202, { id: "run-2", status: "queued" });
      const state = states.shift() ?? "completed";
      return jsonResponse(200, state === "completed" ? completed("run-2") : { id: "run-2", status: state });
    });
    const client = new BridgeClient(config, {
      fetchImpl: fetchMock,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); },
    });

    const result = await client.research({ topic: "Topic" }, "stable-call");

    expect(result.status).toBe("completed");
    expect(sleeps).toEqual([1_000, 2_000, 4_000, 5_000]);
    expect(fetchMock).toHaveBeenCalledTimes(6);
  });

  it("recovers a lost POST response by the same idempotency key", async () => {
    const calls: Array<{ url: string; method: string; key: string | null }> = [];
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      calls.push({ url, method, key: new Headers(init?.headers).get("X-Idempotency-Key") });
      if (method === "POST") throw new TypeError("simulated lost response");
      return jsonResponse(200, completed("durable-run"));
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock });

    const result = await client.research({ topic: "Topic" }, "recovery-key");

    expect(result.id).toBe("durable-run");
    expect(calls).toHaveLength(2);
    expect(calls[0]).toMatchObject({ method: "POST", key: "recovery-key" });
    expect(calls[1]).toMatchObject({ url: "http://127.0.0.1:8001/v1/bridge/runs/recovery-key", method: "GET" });
  });

  it("retries only the exact same body and key when lookup is temporarily absent", async () => {
    const postBodies: string[] = [];
    const postKeys: Array<string | null> = [];
    let lookup = 0;
    let createdRuns = 0;
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_input, init) => {
      if (init?.method === "POST") {
        postBodies.push(String(init.body));
        postKeys.push(new Headers(init.headers).get("X-Idempotency-Key"));
        if (createdRuns === 0) {
          createdRuns += 1;
          throw new TypeError("lost after acceptance");
        }
        return jsonResponse(200, completed("one-run"));
      }
      lookup += 1;
      return jsonResponse(404, { detail: "not persisted yet" });
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock });

    const result = await client.research(params, "same-key");

    expect(result.id).toBe("one-run");
    expect(createdRuns).toBe(1);
    expect(postBodies).toHaveLength(2);
    expect(postBodies[0]).toBe(postBodies[1]);
    expect(postKeys).toEqual(["same-key", "same-key"]);
    expect(lookup).toBe(1);
  });

  it("does not retry a 409 idempotency conflict", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(409, { detail: "conflict" }));
    const client = new BridgeClient(config, { fetchImpl: fetchMock });
    await expect(client.research(params, "conflict-key")).rejects.toMatchObject({ code: "conflict" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("honors a bounded Retry-After value for 429", async () => {
    const sleeps: number[] = [];
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("{}", { status: 429, headers: { "Retry-After": "4" } }))
      .mockResolvedValueOnce(jsonResponse(200, completed()));
    const client = new BridgeClient(config, {
      fetchImpl: fetchMock,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); },
    });
    const result = await client.research(params, "rate-key");

    expect(result.status).toBe("completed");
    expect(sleeps).toEqual([4_000]);
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(fetchMock.mock.calls[0]?.[1]?.body);
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("X-Idempotency-Key")).toBe("rate-key");
  });

  it("does not accept a completed payload carried by a 429 poll response", async () => {
    const sleeps: number[] = [];
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(202, { id: "rate-run", status: "queued" }))
      .mockResolvedValueOnce(jsonResponse(429, completed("rate-run"), { "Retry-After": "4" }))
      .mockResolvedValueOnce(jsonResponse(200, completed("rate-run")));
    const client = new BridgeClient(config, {
      fetchImpl: fetchMock,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); },
    });

    const result = await client.research(params, "poll-rate-key");

    expect(result.id).toBe("rate-run");
    expect(sleeps).toEqual([4_000]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("does not accept a completed payload carried by a 5xx poll response", async () => {
    const sleeps: number[] = [];
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(202, { id: "server-run", status: "queued" }))
      .mockResolvedValueOnce(jsonResponse(503, completed("server-run")))
      .mockResolvedValueOnce(jsonResponse(200, completed("server-run")));
    const client = new BridgeClient(config, {
      fetchImpl: fetchMock,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); },
    });

    const result = await client.research(params, "poll-server-key");

    expect(result.id).toBe("server-run");
    expect(sleeps).toEqual([1_000]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("surfaces 401 without exposing the configured key", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(401, { detail: "unauthorized" }));
    const client = new BridgeClient(config, { fetchImpl: fetchMock });
    let message = "";
    try {
      await client.research(params, "auth-key");
    } catch (error) {
      message = error instanceof Error ? error.message : String(error);
    }

    expect(message).toBe("Bridge authentication failed.");
    expect(message).not.toContain(config.apiKey);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("recovers a 5xx submission from the same run key", async () => {
    const calls: string[] = [];
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      calls.push(url);
      return url.endsWith("/v1/bridge/runs")
        ? jsonResponse(503, { error: "busy" })
        : jsonResponse(200, completed("existing-run"));
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock });
    const result = await client.research(params, "server-recovery-key");

    expect(result.id).toBe("existing-run");
    expect(calls).toEqual([
      "http://127.0.0.1:8001/v1/bridge/runs",
      "http://127.0.0.1:8001/v1/bridge/runs/server-recovery-key",
    ]);
  });

  it("reports 404 after checking the run id and idempotency key", async () => {
    const calls: string[] = [];
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      calls.push(String(input));
      return (init?.method ?? "GET") === "POST"
        ? jsonResponse(202, { id: "missing-run", status: "queued" })
        : jsonResponse(404, { detail: "missing" });
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock });

    await expect(client.research(params, "missing-key")).rejects.toMatchObject({ code: "not_found", ambiguous: true });
    expect(calls).toEqual([
      "http://127.0.0.1:8001/v1/bridge/runs",
      "http://127.0.0.1:8001/v1/bridge/runs/missing-run",
      "http://127.0.0.1:8001/v1/bridge/runs/missing-key",
    ]);
  });

  it("times out a hung request and retries only with the same key", async () => {
    let postCount = 0;
    let firstBody: string | undefined;
    let secondBody: string | undefined;
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_input, init) => {
      if (init?.method === "POST") {
        postCount += 1;
        if (postCount === 1) {
          firstBody = String(init.body);
          return new Promise<Response>(() => undefined);
        }
        secondBody = String(init.body);
        return jsonResponse(200, completed("timeout-recovered"));
      }
      return jsonResponse(404, { detail: "not yet persisted" });
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock, requestTimeoutMs: 5 });
    const result = await client.research(params, "timeout-key");
    const postCalls = fetchMock.mock.calls.filter(([, init]) => init?.method === "POST");

    expect(result.id).toBe("timeout-recovered");
    expect(postCount).toBe(2);
    expect(postCalls).toHaveLength(2);
    expect(firstBody).toBe(secondBody);
    expect(postCalls[1]?.[1]?.body).toBe(postCalls[0]?.[1]?.body);
    expect(new Headers(postCalls[1]?.[1]?.headers).get("X-Idempotency-Key")).toBe("timeout-key");
  });

  it("stops immediately when the caller aborts", async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(() => new Promise<Response>(() => undefined));
    const client = new BridgeClient(config, { fetchImpl: fetchMock, requestTimeoutMs: 1_000 });
    const pending = client.research(params, "abort-key", controller.signal);
    setTimeout(() => controller.abort(), 5);

    await expect(pending).rejects.toMatchObject({ code: "aborted", ambiguous: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("refuses malformed Bridge JSON after same-key recovery fails", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_input, init) => {
      if (init?.method === "POST") return new Response("{broken", { status: 200 });
      return jsonResponse(404, { detail: "missing" });
    });
    const client = new BridgeClient(config, { fetchImpl: fetchMock });

    await expect(client.research(params, "bad-json-key")).rejects.toMatchObject({ code: "malformed_json", ambiguous: true });
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
});
