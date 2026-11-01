import { describe, expect, it } from "vitest";
import { PROTOCOL, PROTOCOL_V2 } from "../src/protocol.js";
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

  it("keeps the V1 protocol for a revoked paired device", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context, ["sync.status"]);
      await context.registry.revoke(device.deviceId);

      const channel = new FakeSecureChannel();
      await context.manager.create(channel).receive(JSON.stringify(hello(device.deviceId, device.token, {
        protocol: PROTOCOL,
        capabilities: ["sync.status"],
      })));

      expect(channel.lastMessage()).toMatchObject({
        protocol: PROTOCOL,
        kind: "error",
        error: { code: "DEVICE_REVOKED" },
      });
      expect(channel.closeEvents[0]?.code).toBe(4001);
    } finally {
      await context.dispose();
    }
  });

  it("does not disclose revocation to a caller with the wrong device token", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context, ["v4.coach.chat"]);
      await context.registry.revoke(device.deviceId);

      const channel = new FakeSecureChannel();
      await context.manager.create(channel).receive(JSON.stringify(hello(
        device.deviceId,
        Buffer.alloc(32, 9).toString("base64url"),
        { protocol: PROTOCOL_V2, capabilities: ["v4.coach.chat"] },
      )));

      expect(channel.lastMessage()).toMatchObject({
        protocol: PROTOCOL_V2,
        kind: "error",
        error: { code: "AUTH_FAILED" },
      });
      expect(channel.sent.some((message) =>
        (message as { error?: { code?: string } }).error?.code === "DEVICE_REVOKED",
      )).toBe(false);
      expect(channel.closeEvents[0]?.code).toBe(4001);
    } finally {
      await context.dispose();
    }
  });

  it("rejects an unknown device as AUTH_FAILED", async () => {
    const context = await makeContext();
    try {
      const channel = new FakeSecureChannel();
      await context.manager.create(channel).receive(JSON.stringify(hello(
        "00000000-0000-4000-8000-000000000001",
        Buffer.alloc(32, 1).toString("base64url"),
        { protocol: PROTOCOL_V2, capabilities: ["v4.coach.chat"] },
      )));

      expect(channel.lastMessage()).toMatchObject({
        protocol: PROTOCOL_V2,
        kind: "error",
        error: { code: "AUTH_FAILED" },
      });
      expect(channel.sent.some((message) =>
        (message as { error?: { code?: string } }).error?.code === "DEVICE_REVOKED",
      )).toBe(false);
      expect(channel.closeEvents[0]?.code).toBe(4001);
    } finally {
      await context.dispose();
    }
  });

  it("uses the authenticated protocol when polling detects revocation", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context, ["v4.coach.chat"]);
      const channel = new FakeSecureChannel();
      await context.manager.create(channel).receive(JSON.stringify(hello(device.deviceId, device.token, {
        protocol: PROTOCOL_V2,
        capabilities: ["v4.coach.chat"],
      })));

      await context.registry.revoke(device.deviceId);
      await context.manager.closeRevokedSessions();

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
