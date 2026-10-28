import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { BackendClient, type FetchLike } from "../src/backend.js";
import { OperationExecutor } from "../src/operations.js";
import { MAX_REQUEST_BYTES, PROTOCOL, type Capability, type RequestEnvelope } from "../src/protocol.js";
import { FakeSecureChannel, hello, makeContext, request } from "./helpers.js";

const STATE = { relayConnected: () => true, backendConfigured: () => true };
const SERVICE_TOKEN = "synthetic-service-token";
const CHAT_PAYLOAD = {
  conversation_id: "synthetic-conversation-r3b",
  message: "Propose une prochaine étape synthétique.",
  shared_context: {
    consented_categories: ["projects"],
    categories: { projects: ["Projet synthétique"] },
  },
  mandate_summary: {
    id: "synthetic-mandate-r3b",
    version: 1,
    allowed_actions: ["home.set_directive", "focus.propose"],
    mode: "proposals_only",
    focus_window: null,
    app_policy_bounds: {},
  },
};
const CHAT_RESULT = {
  reply: "Voici une étape synthétique.",
  proposals: [{ proposal_id: "synthetic-proposal-r3b", state: "proposed" }],
  agent_status: "mock",
};
const CHAT_CAPABILITY: Capability = "coach.chat";

function delayedJsonFetcher(delayMs: number, onCall: () => void = () => {}): FetchLike {
  return async (_input, init) => {
    onCall();
    return await new Promise<Response>((resolve, reject) => {
      const signal = init?.signal;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const abort = () => {
        if (timer !== undefined) clearTimeout(timer);
        reject(new DOMException("synthetic backend request aborted", "AbortError"));
      };
      if (signal?.aborted) {
        abort();
        return;
      }
      timer = setTimeout(() => {
        signal?.removeEventListener("abort", abort);
        resolve(new Response(JSON.stringify(CHAT_RESULT), { status: 200, headers: { "content-type": "application/json" } }));
      }, delayMs);
      signal?.addEventListener("abort", abort, { once: true });
    });
  };
}

async function openChatSession(
  context: Awaited<ReturnType<typeof makeContext>>,
  capabilities: Capability[] = [CHAT_CAPABILITY],
) {
  const device = await context.registry.pair("synthetic R3B chat device", capabilities);
  const channel = new FakeSecureChannel();
  const peer = context.manager.create(channel);
  await peer.receive(JSON.stringify(hello(device.device.device_id, device.token, { capabilities })));
  return { deviceId: device.device.device_id, token: device.token, channel, peer, sessionId: String(channel.lastMessage().session_id) };
}

function chatRequest(sessionId: string, payload: Record<string, unknown> = CHAT_PAYLOAD, overrides: Partial<RequestEnvelope> = {}) {
  return request(sessionId, "coach.chat.request", payload, overrides);
}

function chatPayloadAtContextByteLimit(): Record<string, unknown> {
  const consented = ["focus", "habit", "content", "coach_interaction"];
  const categories: Record<string, string[]> = Object.fromEntries(consented.map((category) => [category, Array(10).fill("x".repeat(200))]));
  const finalItem = categories.coach_interaction!;
  for (let length = 200; length >= 0; length -= 1) {
    finalItem[9] = "x".repeat(length);
    const size = Buffer.byteLength(JSON.stringify({ consented_categories: consented, categories }), "utf8");
    if (size === 8192) return { ...CHAT_PAYLOAD, shared_context: { consented_categories: consented, categories } };
  }
  throw new Error("could not construct exact 8192-byte context fixture");
}

function cancelRequest(targetRequestId: string) {
  return {
    protocol: PROTOCOL,
    kind: "cancel",
    request_id: randomUUID(),
    target_request_id: targetRequestId,
  };
}

function lastError(channel: FakeSecureChannel) {
  return channel.sent.filter((message) => (message as Record<string, unknown>).kind === "error").at(-1) as Record<string, unknown> | undefined;
}

describe("R3-B relayed coach chat", () => {
  it("uses the longer chat timeout while keeping the short timeout for other operations", async () => {
    const calls: string[] = [];
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: SERVICE_TOKEN,
      timeoutMs: 20,
      chatTimeoutMs: 300,
      fetcher: async (input, init) => {
        calls.push(String(input));
        return await delayedJsonFetcher(80)(input, init);
      },
    });

    await expect(backend.execute("coach.status", {})).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: true });
    await expect(backend.execute("coach.chat.request", CHAT_PAYLOAD)).resolves.toEqual(CHAT_RESULT);
    expect(calls).toEqual([
      "http://127.0.0.1:8100/v2/agent/status",
      "http://127.0.0.1:8100/v2/agent/chat",
    ]);
  });

  it("marks a timed-out chat uncertain and blocks a second backend call", async () => {
    const journalDir = await mkdtemp(join(tmpdir(), "atlas-chat-r3b-timeout-"));
    const journalPath = join(journalDir, "operations.json");
    let calls = 0;
    const fetcher = delayedJsonFetcher(120, () => { calls += 1; });
    const backend = new BackendClient({
      baseUrl: "http://127.0.0.1:8100",
      serviceToken: SERVICE_TOKEN,
      timeoutMs: 20,
      chatTimeoutMs: 60,
      fetcher,
    });
    const executor = new OperationExecutor(backend, STATE, Date.now, journalPath);
    const deviceId = "synthetic-chat-timeout-device";
    const sessionId = "ad64ed44-c498-4a20-b042-dcbf2ad8dc99";
    const chat = chatRequest(sessionId, CHAT_PAYLOAD, { idempotency_key: "chat-r3b-timeout-key" });
    try {
      await expect(executor.execute(deviceId, sessionId, chat)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      const retry = { ...chat, request_id: randomUUID() };
      await expect(executor.execute(deviceId, sessionId, retry)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });

      const restarted = new OperationExecutor(new BackendClient({
        baseUrl: "http://127.0.0.1:8100", serviceToken: SERVICE_TOKEN,
        chatTimeoutMs: 60,
        fetcher: delayedJsonFetcher(120, () => { calls += 1; }),
      }), STATE, Date.now, journalPath);
      await expect(restarted.execute(deviceId, sessionId, retry)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(calls).toBe(1);
    } finally {
      await rm(journalDir, { recursive: true, force: true });
    }
  });

  it("rejects the operation when the authenticated session lacks coach.chat", async () => {
    const context = await makeContext();
    try {
      const session = await openChatSession(context, ["sync.status"]);
      await session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "OPERATION_UNAVAILABLE" } });
      expect(context.backendCalls).toHaveLength(0);
    } finally {
      await context.dispose();
    }
  });

  it("posts the exact validated payload to the fixed chat route with the service bearer and replays durably", async () => {
    const journalDir = await mkdtemp(join(tmpdir(), "atlas-chat-r3b-journal-"));
    const journalPath = join(journalDir, "operations.json");
    const calls: Array<{ url: string; method: string; authorization: string | null; contentType: string | null; body: string | null }> = [];
    const fetcher: FetchLike = async (input, init) => {
      const headers = new Headers(init?.headers);
      calls.push({
        url: String(input), method: String(init?.method), authorization: headers.get("authorization"),
        contentType: headers.get("content-type"), body: typeof init?.body === "string" ? init.body : null,
      });
      return new Response(JSON.stringify(CHAT_RESULT), { status: 200, headers: { "content-type": "application/json" } });
    };
    const context = await makeContext({ fetcher, journalPath });
    try {
      const session = await openChatSession(context);
      const chat = chatRequest(session.sessionId, CHAT_PAYLOAD, { idempotency_key: "chat-r3b-complete-key" });
      await session.peer.receive(JSON.stringify(chat));
      const first = session.channel.lastMessage();
      expect(first).toMatchObject({ kind: "response", operation: "coach.chat.request", replayed: false, result: CHAT_RESULT });
      expect(calls).toEqual([{
        url: "http://127.0.0.1:8100/v2/agent/chat",
        method: "POST",
        authorization: `Bearer ${SERVICE_TOKEN}`,
        contentType: "application/json",
        body: JSON.stringify(CHAT_PAYLOAD),
      }]);
      expect(JSON.parse(calls[0]!.body!).device_id).toBeUndefined();

      await session.peer.receive(JSON.stringify(chat));
      expect(session.channel.lastMessage()).toMatchObject({ kind: "response", replayed: true, result: CHAT_RESULT });
      const restarted = new OperationExecutor(new BackendClient({
        baseUrl: "http://127.0.0.1:8100", serviceToken: SERVICE_TOKEN,
        fetcher: async () => {
          calls.push({ url: "unexpected", method: "POST", authorization: null, contentType: null, body: null });
          return new Response(JSON.stringify(CHAT_RESULT), { status: 200 });
        },
      }), STATE, Date.now, journalPath);
      const durableReplay = await restarted.execute(session.deviceId, session.sessionId, chat);
      expect(durableReplay).toMatchObject({ replayed: true, result: CHAT_RESULT });
      expect(calls).toHaveLength(1);

      const conflict = chatRequest(session.sessionId, { ...CHAT_PAYLOAD, message: "Autre message synthétique." }, {
        request_id: randomUUID(), idempotency_key: chat.idempotency_key,
      });
      await session.peer.receive(JSON.stringify(conflict));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "IDEMPOTENCY_CONFLICT" } });
      expect(calls).toHaveLength(1);
    } finally {
      await context.dispose();
      await rm(journalDir, { recursive: true, force: true });
    }
  });

  it("rejects invalid payloads, minimized contexts over 8 KiB, and requests over 32 KiB before POST", async () => {
    const calls: string[] = [];
    const context = await makeContext({
      fetcher: async (input) => {
        calls.push(String(input));
        return new Response(JSON.stringify(CHAT_RESULT), { status: 200 });
      },
    });
    try {
      const session = await openChatSession(context);
      const oversizedContext = {
        ...CHAT_PAYLOAD,
        shared_context: {
          consented_categories: ["focus", "habit", "content", "coach_interaction", "app_usage_summary"],
          categories: Object.fromEntries(["focus", "habit", "content", "coach_interaction", "app_usage_summary"].map((category) => [category, Array(10).fill("x".repeat(180))])),
        },
      };
      const oversizedRequest = {
        ...CHAT_PAYLOAD,
        mandate_summary: {
          ...CHAT_PAYLOAD.mandate_summary,
          app_policy_bounds: Object.fromEntries(Array.from({ length: 50 }, (_, index) => [`bound-${index}`, { value: "x".repeat(700) }])),
        },
      };
      const invalids: Array<{ payload: Record<string, unknown>; code: string }> = [
        { payload: { ...CHAT_PAYLOAD, message: "x".repeat(2001) }, code: "INVALID_REQUEST" },
        { payload: oversizedContext, code: "PAYLOAD_TOO_LARGE" },
        { payload: { ...CHAT_PAYLOAD, unexpected: "field" }, code: "INVALID_REQUEST" },
        { payload: { ...CHAT_PAYLOAD, mandate_summary: { ...CHAT_PAYLOAD.mandate_summary, mode: "unbounded" } }, code: "INVALID_REQUEST" },
        { payload: { ...CHAT_PAYLOAD, conversation_id: null }, code: "INVALID_REQUEST" },
        { payload: oversizedRequest, code: "PAYLOAD_TOO_LARGE" },
      ];
      for (const invalid of invalids) {
        await session.peer.receive(JSON.stringify(chatRequest(session.sessionId, invalid.payload)));
        expect(lastError(session.channel)).toMatchObject({ error: { code: invalid.code } });
      }
      const padded = JSON.stringify(chatRequest(session.sessionId));
      const paddedFrame = `${padded}${" ".repeat(MAX_REQUEST_BYTES + 1 - Buffer.byteLength(padded, "utf8"))}`;
      await session.peer.receive(paddedFrame);
      expect(lastError(session.channel)).toMatchObject({ error: { code: "PAYLOAD_TOO_LARGE" } });
      expect(calls).toHaveLength(0);
    } finally {
      await context.dispose();
    }
  });

  it("accepts a minimized context whose UTF-8 envelope is exactly 8192 bytes", async () => {
    let calls = 0;
    const context = await makeContext({
      fetcher: async () => {
        calls += 1;
        return new Response(JSON.stringify(CHAT_RESULT), { status: 200 });
      },
    });
    try {
      const session = await openChatSession(context);
      const payload = chatPayloadAtContextByteLimit();
      await session.peer.receive(JSON.stringify(chatRequest(session.sessionId, payload)));
      expect(session.channel.lastMessage()).toMatchObject({ kind: "response", operation: "coach.chat.request" });
      expect(calls).toBe(1);
    } finally {
      await context.dispose();
    }
  });

  it("checks revocation before routing and never calls FastAPI for a revoked device", async () => {
    const context = await makeContext();
    try {
      const session = await openChatSession(context);
      await context.registry.revoke(session.deviceId);
      await session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "DEVICE_REVOKED" } });
      expect(context.backendCalls).toHaveLength(0);
    } finally {
      await context.dispose();
    }
  });

  it("locks an uncertain POST in the persistent journal and never retries it", async () => {
    const journalDir = await mkdtemp(join(tmpdir(), "atlas-chat-r3b-uncertain-"));
    const journalPath = join(journalDir, "operations.json");
    let effects = 0;
    const fetcher: FetchLike = async () => {
      effects += 1;
      throw new TypeError("synthetic connection dropped after dispatch");
    };
    const context = await makeContext({ fetcher, journalPath });
    try {
      const session = await openChatSession(context);
      const chat = chatRequest(session.sessionId, CHAT_PAYLOAD, { idempotency_key: "chat-r3b-uncertain-key" });
      await session.peer.receive(JSON.stringify(chat));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "BACKEND_UNAVAILABLE", retryable: false } });
      await session.peer.receive(JSON.stringify({ ...chat, request_id: randomUUID() }));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "BACKEND_UNAVAILABLE", retryable: false } });

      const restarted = new OperationExecutor(new BackendClient({ baseUrl: "http://127.0.0.1:8100", serviceToken: SERVICE_TOKEN, fetcher }), STATE, Date.now, journalPath);
      await expect(restarted.execute(session.deviceId, session.sessionId, chat)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(effects).toBe(1);
    } finally {
      await context.dispose();
      await rm(journalDir, { recursive: true, force: true });
    }
  });

  it("fails closed on a corrupt idempotency journal without replacing it or posting chat", async () => {
    const journalDir = await mkdtemp(join(tmpdir(), "atlas-chat-r3b-corrupt-journal-"));
    const journalPath = join(journalDir, "operations.json");
    const corrupt = "{\"schema_version\":1,\"entries\":[";
    await writeFile(journalPath, corrupt, "utf8");
    const calls: string[] = [];
    const context = await makeContext({
      journalPath,
      fetcher: async (input) => {
        calls.push(String(input));
        return new Response(JSON.stringify(CHAT_RESULT), { status: 200 });
      },
    });
    try {
      const session = await openChatSession(context);
      await session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "INTERNAL" } });
      expect(calls).toHaveLength(0);
      expect(await readFile(journalPath, "utf8")).toBe(corrupt);
    } finally {
      await context.dispose();
      await rm(journalDir, { recursive: true, force: true });
    }
  });

  it("returns CANCELLED when a client aborts and persists the no-retry outcome", async () => {
    const journalDir = await mkdtemp(join(tmpdir(), "atlas-chat-r3b-cancel-"));
    const journalPath = join(journalDir, "operations.json");
    let effects = 0;
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    const fetcher: FetchLike = async (_input, init) => {
      effects += 1;
      markStarted();
      return await new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
      });
    };
    const context = await makeContext({ fetcher, journalPath });
    try {
      const session = await openChatSession(context);
      const chat = chatRequest(session.sessionId, CHAT_PAYLOAD, { idempotency_key: "chat-r3b-cancel-key" });
      const pending = session.peer.receive(JSON.stringify(chat));
      await started;
      await session.peer.receive(JSON.stringify(cancelRequest(chat.request_id)));
      await pending;
      expect(lastError(session.channel)).toMatchObject({ error: { code: "CANCELLED", retryable: false } });
      await session.peer.receive(JSON.stringify({ ...chat, request_id: randomUUID() }));
      expect(lastError(session.channel)).toMatchObject({ error: { code: "BACKEND_UNAVAILABLE", retryable: false } });
      const restarted = new OperationExecutor(new BackendClient({ baseUrl: "http://127.0.0.1:8100", serviceToken: SERVICE_TOKEN, fetcher }), STATE, Date.now, journalPath);
      await expect(restarted.execute(session.deviceId, session.sessionId, chat)).rejects.toMatchObject({ code: "BACKEND_UNAVAILABLE", retryable: false });
      expect(effects).toBe(1);
    } finally {
      await context.dispose();
      await rm(journalDir, { recursive: true, force: true });
    }
  });

  it("bounds malformed and oversized FastAPI responses without exposing response content", async () => {
    const bodies = [
      JSON.stringify({ reply: "synthetic raw response marker", proposals: [], agent_status: "bad", unexpected: "field" }),
      "x".repeat(262_145),
    ];
    for (const [index, body] of bodies.entries()) {
      const fetcher: FetchLike = async () => new Response(body, { status: 200 });
      const context = await makeContext({ fetcher });
      try {
        const session = await openChatSession(context);
        await session.peer.receive(JSON.stringify(chatRequest(session.sessionId, CHAT_PAYLOAD, { idempotency_key: `chat-r3b-bad-response-${index}` })));
        const error = lastError(session.channel)!;
        expect(error.error).toMatchObject({ code: index === 0 ? "BACKEND_REJECTED" : "PAYLOAD_TOO_LARGE", retryable: false });
        expect(JSON.stringify(error)).not.toContain("synthetic raw response marker");
        expect(JSON.stringify(error).length).toBeLessThan(512);
      } finally {
        await context.dispose();
      }
    }
  });

  it("does not log a message, service token, or backend response body", async () => {
    const messageMarker = "SYNTHETIC_CHAT_MESSAGE_NEVER_LOGGED";
    const resultMarker = "SYNTHETIC_CHAT_RESULT_NEVER_LOGGED";
    const output: string[] = [];
    const oldLog = console.log;
    const oldWarn = console.warn;
    const oldError = console.error;
    const capture = (...args: unknown[]) => output.push(args.map(String).join(" "));
    console.log = capture;
    console.warn = capture;
    console.error = capture;
    const fetcher: FetchLike = async () => new Response(JSON.stringify({ ...CHAT_RESULT, reply: resultMarker }), { status: 200 });
    const context = await makeContext({ fetcher });
    try {
      const session = await openChatSession(context);
      await session.peer.receive(JSON.stringify(chatRequest(session.sessionId, { ...CHAT_PAYLOAD, message: messageMarker })));
      const logs = output.join("\n");
      expect(logs).not.toContain(messageMarker);
      expect(logs).not.toContain(resultMarker);
      expect(logs).not.toContain(SERVICE_TOKEN);
    } finally {
      console.log = oldLog;
      console.warn = oldWarn;
      console.error = oldError;
      await context.dispose();
    }
  });
});
