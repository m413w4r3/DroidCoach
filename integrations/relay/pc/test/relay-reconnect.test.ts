import net from "node:net";
import { randomUUID } from "node:crypto";
import WebSocket, { WebSocketServer } from "ws";
import { generateKeyPair } from "@getpaseo/relay/e2ee";
import { describe, expect, it } from "vitest";
import { RelayLink } from "../src/relay-link.js";
import { makeContext } from "./helpers.js";

describe("Relay control reconnect", () => {
  it("recovers when a loopback relay is absent and later disconnects and returns", async () => {
    const context = await makeContext();
    const port = await freeLoopbackPort();
    const link = new RelayLink({
      relayUrl: `ws://127.0.0.1:${port}`,
      allowLoopbackWs: true,
      serverId: `atlas-local-test-${randomUUID()}`,
      daemonKeyPair: generateKeyPair(),
      sessions: context.manager,
      metrics: context.metrics,
      reconnectMinMs: 100,
      reconnectMaxMs: 250,
    });
    let fakeRelay: WebSocketServer | null = null;
    try {
      await link.start();
      await expect(link.waitForControl(150)).rejects.toThrow("timeout");

      fakeRelay = await startFakeRelay(port);
      await link.waitForControl(3_000);
      expect(link.connected).toBe(true);
      expect(context.metrics.relayConnected).toBe(true);

      await closeFakeRelay(fakeRelay);
      fakeRelay = null;
      await link.waitForState(false, 3_000);

      fakeRelay = await startFakeRelay(port);
      await link.waitForState(true, 3_000);
      expect(link.connected).toBe(true);
    } finally {
      await link.stop();
      if (fakeRelay) await closeFakeRelay(fakeRelay);
      await context.dispose();
    }
  }, 15_000);
});

async function freeLoopbackPort(): Promise<number> {
  const server = net.createServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("loopback port unavailable");
  await new Promise<void>((resolve) => server.close(() => resolve()));
  return address.port;
}

async function startFakeRelay(port: number): Promise<WebSocketServer> {
  const server = new WebSocketServer({ host: "127.0.0.1", port });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.once("listening", () => resolve());
  });
  server.on("connection", (socket) => {
    socket.send(JSON.stringify({ type: "sync", connectionIds: [] }));
    socket.on("message", (data, isBinary) => {
      if (isBinary) return;
      try {
        const message = JSON.parse(data.toString("utf8")) as Record<string, unknown>;
        if (message.type === "ping") socket.send(JSON.stringify({ type: "pong", ts: Date.now() }));
      } catch {
        // The test relay only accepts the control ping shape.
      }
    });
  });
  return server;
}

async function closeFakeRelay(server: WebSocketServer): Promise<void> {
  for (const client of server.clients) client.close(1001, "test relay stopped");
  await new Promise<void>((resolve) => server.close(() => resolve()));
}
