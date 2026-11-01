import { describe, expect, it } from "vitest";
import { PROTOCOL_V2 } from "../src/protocol.js";
import { FakeSecureChannel, hello, makeContext, paired } from "./helpers.js";

describe("V7-06D revocation authentication", () => {
  it("uses the incoming V2 protocol for a revoked paired device and closes with 4001", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context, ["v4.coach.chat"]);
      await context.registry.revoke(device.deviceId);

      const channel = new FakeSecureChannel();
      const peer = context.manager.create(channel);
      await peer.receive(JSON.stringify(hello(device.deviceId, device.token, {
        protocol: PROTOCOL_V2,
        capabilities: ["v4.coach.chat"],
      })));

      expect(channel.lastMessage()).toMatchObject({
        protocol: PROTOCOL_V2,
        kind: "error",
        error: { code: "DEVICE_REVOKED" },
      });
      expect(channel.closeEvents[0]?.code).toBe(4001);
    } finally {
      await context.dispose();
    }
  });
});
