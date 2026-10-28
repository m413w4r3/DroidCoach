import { describe, expect, it } from "vitest";
import { hello, makeContext, request, FakeSecureChannel } from "./helpers.js";

describe("R3-C compatibility with an older pairing", () => {
  it("negotiates only stored capabilities and keeps legacy operations usable", async () => {
    const context = await makeContext();
    try {
      const paired = await context.registry.pair("ancien appareil", ["sync.status"]);
      const registeredBefore = await context.registry.get(paired.device.device_id);
      const channel = new FakeSecureChannel();
      const peer = context.manager.create(channel);

      await peer.receive(JSON.stringify(hello(paired.device.device_id, paired.token, {
        capabilities: ["sync.status", "coach.chat"],
      })));

      expect(channel.lastMessage()).toMatchObject({
        kind: "hello_ok",
        capabilities: ["sync.status"],
      });
      const sessionId = String(channel.lastMessage().session_id);

      await peer.receive(JSON.stringify(request(sessionId, "coach.chat.request", {
        conversation_id: "synthetic-r3c-conversation",
        message: "synthetic chat request",
        shared_context: { consented_categories: [], categories: {} },
        mandate_summary: {
          id: "synthetic-r3c-mandate",
          version: 1,
          allowed_actions: [],
          mode: "proposals_only",
          focus_window: null,
          app_policy_bounds: {},
        },
      })));

      expect(channel.lastMessage()).toMatchObject({
        kind: "error",
        error: { code: "OPERATION_UNAVAILABLE" },
      });
      expect(channel.closeEvents).toEqual([]);

      await peer.receive(JSON.stringify(request(sessionId, "sync.status")));
      expect(channel.lastMessage()).toMatchObject({
        kind: "response",
        operation: "sync.status",
        status: "OK",
      });
      expect(channel.closeEvents).toEqual([]);
      expect(await context.registry.get(paired.device.device_id)).toEqual(registeredBefore);
      expect(context.backendCalls).toEqual([]);
      peer.close();
    } finally {
      await context.dispose();
    }
  });
});
