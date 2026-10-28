import { describe, expect, it } from "vitest";
import type { FetchLike } from "../src/backend.js";
import { FakeSecureChannel, hello, makeContext, request } from "./helpers.js";

const CHAT_PAYLOAD = {
  conversation_id: "synthetic-r3c-race-conversation",
  message: "Propose une prochaine étape synthétique.",
  shared_context: {
    consented_categories: ["projects"],
    categories: { projects: ["Projet synthétique"] },
  },
  mandate_summary: {
    id: "synthetic-r3c-race-mandate",
    version: 1,
    allowed_actions: ["home.set_directive", "focus.propose"],
    mode: "proposals_only",
    focus_window: null,
    app_policy_bounds: {},
  },
};

const CHAT_RESULT = {
  reply: "Voici une étape synthétique.",
  proposals: [{ proposal_id: "synthetic-r3c-race-proposal", state: "proposed" }],
  agent_status: "mock",
};

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

class RaceChannel extends FakeSecureChannel {
  readonly closeObserved = deferred<void>();

  constructor(
    private readonly events: string[],
    private readonly errorSendBarrier?: { started: () => void; release: Promise<void> },
  ) {
    super();
  }

  override async send(data: string | ArrayBuffer): Promise<void> {
    const message = typeof data === "string" ? JSON.parse(data) as Record<string, unknown> : null;
    const error = message?.kind === "error" ? message.error as Record<string, unknown> : undefined;
    if (error?.code === "DEVICE_REVOKED") {
      this.events.push("send-start:DEVICE_REVOKED");
      this.errorSendBarrier?.started();
      if (this.errorSendBarrier) await this.errorSendBarrier.release;
      await super.send(data);
      this.events.push("send-complete:DEVICE_REVOKED");
      return;
    }
    await super.send(data);
  }

  override close(code = 1000, reason = "closed"): void {
    this.events.push(`close:${code}`);
    super.close(code, reason);
    this.closeObserved.resolve();
  }
}

async function openChatSession(
  context: Awaited<ReturnType<typeof makeContext>>,
  events: string[],
  errorSendBarrier?: { started: () => void; release: Promise<void> },
) {
  const device = await context.registry.pair("synthetic R3-C race device", ["coach.chat"]);
  const channel = new RaceChannel(events, errorSendBarrier);
  const peer = context.manager.create(channel);
  await peer.receive(JSON.stringify(hello(device.device.device_id, device.token, { capabilities: ["coach.chat"] })));
  const sessionId = String(channel.lastMessage().session_id);
  return { peer, channel, deviceId: device.device.device_id, sessionId };
}

function chatRequest(sessionId: string) {
  return request(sessionId, "coach.chat.request", CHAT_PAYLOAD);
}

function successResponse(): Response {
  return new Response(JSON.stringify(CHAT_RESULT), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function errorAndCloseEvents(events: string[]) {
  return events.filter((event) => event === "send-complete:DEVICE_REVOKED" || event === "close:4001");
}

describe("R3-C consent and revocation races", () => {
  it("polling revocation aborts an in-flight chat, sends DEVICE_REVOKED before close, and drops a late response", async () => {
    const events: string[] = [];
    const fetchStarted = deferred<void>();
    const delayedResponse = deferred<Response>();
    let signal: AbortSignal | null | undefined;
    let backendEffects = 0;
    const fetcher: FetchLike = async (_input, init) => {
      backendEffects += 1;
      signal = init?.signal;
      signal?.addEventListener("abort", () => events.push("abort"), { once: true });
      fetchStarted.resolve();
      return await delayedResponse.promise;
    };
    const context = await makeContext({ fetcher });
    try {
      const session = await openChatSession(context, events);
      const pendingChat = session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      await fetchStarted.promise;

      await context.registry.revoke(session.deviceId);
      await context.manager.closeRevokedSessions();

      expect(signal?.aborted).toBe(true);
      expect(events.indexOf("abort")).toBeGreaterThanOrEqual(0);
      expect(errorAndCloseEvents(events)).toEqual(["send-complete:DEVICE_REVOKED", "close:4001"]);
      expect(events.indexOf("abort")).toBeLessThan(events.indexOf("send-start:DEVICE_REVOKED"));
      expect(events.indexOf("send-complete:DEVICE_REVOKED")).toBeLessThan(events.indexOf("close:4001"));

      delayedResponse.resolve(successResponse());
      await pendingChat;
      expect(session.channel.sent.filter((item) => (item as Record<string, unknown>).kind === "response")).toHaveLength(0);
      expect(backendEffects).toBe(1);
    } finally {
      await context.dispose();
    }
  });

  it("receive rejects a new request after revocation and aborts the earlier chat", async () => {
    const events: string[] = [];
    const fetchStarted = deferred<void>();
    const delayedResponse = deferred<Response>();
    let signal: AbortSignal | null | undefined;
    let backendEffects = 0;
    const fetcher: FetchLike = async (_input, init) => {
      backendEffects += 1;
      signal = init?.signal;
      signal?.addEventListener("abort", () => events.push("abort"), { once: true });
      fetchStarted.resolve();
      return await delayedResponse.promise;
    };
    const context = await makeContext({ fetcher });
    try {
      const session = await openChatSession(context, events);
      const pendingChat = session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      await fetchStarted.promise;

      await context.registry.revoke(session.deviceId);
      const rejectedRequest = chatRequest(session.sessionId);
      await session.peer.receive(JSON.stringify(rejectedRequest));

      expect(signal?.aborted).toBe(true);
      expect(backendEffects).toBe(1);
      expect(session.channel.closeEvents[0]).toMatchObject({ code: 4001 });
      // checkActive() rejects the frame before its request_id is extracted. The
      // contract is a session-scoped DEVICE_REVOKED, which the Android client
      // already handles by failing every queued and in-flight request.
      expect(session.channel.sent.at(-1)).toMatchObject({
        kind: "error",
        request_id: null,
        error: { code: "DEVICE_REVOKED" },
      });
      expect(errorAndCloseEvents(events)).toEqual(["send-complete:DEVICE_REVOKED", "close:4001"]);
      expect(events.indexOf("abort")).toBeLessThan(events.indexOf("send-start:DEVICE_REVOKED"));
      expect(events.indexOf("send-complete:DEVICE_REVOKED")).toBeLessThan(events.indexOf("close:4001"));

      delayedResponse.resolve(successResponse());
      await pendingChat;
      expect(session.channel.sent.filter((item) => (item as Record<string, unknown>).kind === "response")).toHaveLength(0);
      expect(backendEffects).toBe(1);
    } finally {
      await context.dispose();
    }
  });

  it("waits for a blocked DEVICE_REVOKED write before closing and records abort/send/close order", async () => {
    const events: string[] = [];
    const fetchStarted = deferred<void>();
    const delayedResponse = deferred<Response>();
    const errorSendStarted = deferred<void>();
    const releaseErrorSend = deferred<void>();
    let signal: AbortSignal | null | undefined;
    let backendEffects = 0;
    const fetcher: FetchLike = async (_input, init) => {
      backendEffects += 1;
      signal = init?.signal;
      signal?.addEventListener("abort", () => events.push("abort"), { once: true });
      fetchStarted.resolve();
      return await delayedResponse.promise;
    };
    const context = await makeContext({ fetcher });
    try {
      const session = await openChatSession(context, events, {
        started: () => errorSendStarted.resolve(),
        release: releaseErrorSend.promise,
      });
      const pendingChat = session.peer.receive(JSON.stringify(chatRequest(session.sessionId)));
      await fetchStarted.promise;

      await context.registry.revoke(session.deviceId);
      const revocation = context.manager.closeRevokedSessions();
      await errorSendStarted.promise;
      expect(signal?.aborted).toBe(true);

      // This bound only detects the forbidden early close; the send barrier controls progress.
      const closeWhileBlocked = await Promise.race([
        session.channel.closeObserved.promise.then(() => true),
        new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 350)),
      ]);
      expect(closeWhileBlocked).toBe(false);
      expect(events).not.toContain("close:4001");

      releaseErrorSend.resolve();
      await revocation;
      await session.channel.closeObserved.promise;
      expect(events).toEqual([
        "abort",
        "send-start:DEVICE_REVOKED",
        "send-complete:DEVICE_REVOKED",
        "close:4001",
      ]);

      delayedResponse.resolve(successResponse());
      await pendingChat;
      expect(session.channel.sent.filter((item) => (item as Record<string, unknown>).kind === "response")).toHaveLength(0);
      expect(backendEffects).toBe(1);
    } finally {
      releaseErrorSend.resolve();
      await context.dispose();
    }
  });
});
