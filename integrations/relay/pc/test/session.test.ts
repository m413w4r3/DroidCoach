import { chmod, stat } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { loadOrCreateIdentity } from "../src/identity.js";
import { RemoteError } from "../src/errors.js";
import { request, makeContext, authenticate, hello, paired, FakeSecureChannel } from "./helpers.js";

describe("device authentication and session controls", () => {
  it("persists an X25519 identity and the device registry as mode 0600", async () => {
    const context = await makeContext();
    try {
      const identityPath = join(context.dir, "identity.json");
      const first = await loadOrCreateIdentity(identityPath);
      const second = await loadOrCreateIdentity(identityPath);
      expect(first.identity.public_key).toBe(second.identity.public_key);
      expect((await stat(identityPath)).mode & 0o777).toBe(0o600);
      await chmod(identityPath, 0o644);
      await loadOrCreateIdentity(identityPath);
      expect((await stat(identityPath)).mode & 0o777).toBe(0o600);
      await paired(context);
      expect((await stat(join(context.dir, "devices.json"))).mode & 0o777).toBe(0o600);
      const stored = await (await import("node:fs/promises")).readFile(join(context.dir, "devices.json"), "utf8");
      expect(stored).not.toContain("token\": ");
      expect(stored).toContain("token_sha256");
    } finally {
      await context.dispose();
    }
  });

  it("authenticates an enrolled device and returns only requested capabilities", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context);
      const channel = new FakeSecureChannel();
      const peer = context.manager.create(channel);
      await peer.receive(JSON.stringify(hello(device.deviceId, device.token, { capabilities: ["sync.status"] })));
      expect(channel.lastMessage()).toMatchObject({ kind: "hello_ok", capabilities: ["sync.status"], expires_in_seconds: 3600 });
      expect(context.manager.activeSessionCount()).toBe(1);
      peer.close();
      expect(context.manager.activeSessionCount()).toBe(0);
    } finally {
      await context.dispose();
    }
  });

  it("rejects a bad token and a replayed client nonce with close code 4001", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context);
      const wrongChannel = new FakeSecureChannel();
      const wrongPeer = context.manager.create(wrongChannel);
      await wrongPeer.receive(JSON.stringify(hello(device.deviceId, Buffer.alloc(32, 9).toString("base64url"))));
      expect(wrongChannel.lastMessage()).toMatchObject({ kind: "error", error: { code: "AUTH_FAILED" } });
      expect(wrongChannel.closeEvents[0]?.code).toBe(4001);

      const nonce = Buffer.alloc(16, 1).toString("base64");
      const first = new FakeSecureChannel();
      await context.manager.create(first).receive(JSON.stringify(hello(device.deviceId, device.token, { client_nonce: nonce })));
      const replay = new FakeSecureChannel();
      await context.manager.create(replay).receive(JSON.stringify(hello(device.deviceId, device.token, { client_nonce: nonce })));
      expect(replay.lastMessage()).toMatchObject({ kind: "error", error: { code: "AUTH_FAILED" } });
      expect(replay.closeEvents[0]?.code).toBe(4001);
    } finally {
      await context.dispose();
    }
  });

  it("closes an active session when its device is revoked", async () => {
    const context = await makeContext();
    try {
      const session = await authenticate(context);
      await context.registry.revoke(session.deviceId);
      await context.manager.closeRevokedSessions();
      expect(session.channel.closeEvents[0]).toMatchObject({ code: 4001 });
      expect(context.manager.activeSessionCount()).toBe(0);
    } finally {
      await context.dispose();
    }
  });

  it("replaces a device's previous session and applies the per-minute quota", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context);
      const oldChannel = new FakeSecureChannel();
      await context.manager.create(oldChannel).receive(JSON.stringify(hello(device.deviceId, device.token)));
      const newChannel = new FakeSecureChannel();
      const newPeer = context.manager.create(newChannel);
      await newPeer.receive(JSON.stringify(hello(device.deviceId, device.token)));
      expect(oldChannel.closeEvents[0]?.code).toBe(4001);
      const sessionId = String(newChannel.lastMessage().session_id);
      for (let index = 0; index < 60; index += 1) {
        await newPeer.receive(JSON.stringify(request(sessionId, "sync.status")));
      }
      await newPeer.receive(JSON.stringify(request(sessionId, "sync.status")));
      expect(newChannel.lastMessage()).toMatchObject({ kind: "error", error: { code: "RATE_LIMITED" } });
    } finally {
      await context.dispose();
    }
  });

  it("refuses backend operations when the session lacks the matching capability", async () => {
    const context = await makeContext();
    try {
      const device = await paired(context);
      const channel = new FakeSecureChannel();
      const peer = context.manager.create(channel);
      await peer.receive(JSON.stringify(hello(device.deviceId, device.token, { capabilities: ["sync.status"] })));
      const sessionId = String(channel.lastMessage().session_id);
      await peer.receive(JSON.stringify(request(sessionId, "coach.status")));
      expect(channel.lastMessage()).toMatchObject({ kind: "error", error: { code: "OPERATION_UNAVAILABLE" } });
    } finally {
      await context.dispose();
    }
  });

  it("returns typed errors for expired requests without calling the backend", async () => {
    const context = await makeContext();
    try {
      const session = await authenticate(context);
      const expired = request(session.sessionId, "backend.health", {}, {
        created_at: new Date(Date.now() - 60_000).toISOString(),
        expires_at: new Date(Date.now() - 1).toISOString(),
      });
      await session.peer.receive(JSON.stringify(expired));
      expect(session.channel.lastMessage()).toMatchObject({ kind: "error", error: { code: "EXPIRED" } });
      expect(context.backendCalls).toHaveLength(0);
    } finally {
      await context.dispose();
    }
  });
});
