import { randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { describe, expect, it } from "vitest";
import { createConnector } from "../src/connector.js";

const RELAY_URL = process.env.ATLAS_TEST_RELAY_URL ?? "ws://127.0.0.1:4400";

/**
 * A connector restart can leave a stale client socket attached to the relay.
 * That client never sends a fresh e2ee_hello, so the connector must bound the
 * responder handshake and close the data socket; the relay then releases the
 * client. Without this, the Android client would stay "connected" to a dead
 * Noise session until a network change or app restart.
 */
describe("stale relay client handshake", () => {
  it("closes a client that never completes the Noise hello", async () => {
    const dir = await mkdtemp(join(tmpdir(), "atlas-stale-client-"));
    const serverId = `atlas-stale-${randomUUID()}`;
    const runtime = await createConnector({
      stateDir: dir,
      relayUrl: RELAY_URL,
      allowLoopbackWs: true,
      serverId,
      backendUrl: "http://127.0.0.1:8100",
      metricsPort: 0,
      handshakeTimeoutMs: 500,
    });
    let client: WebSocket | null = null;
    try {
      await runtime.start();
      await runtime.relay.waitForControl(8_000);
      const url = new URL("/ws", RELAY_URL);
      url.searchParams.set("serverId", serverId);
      url.searchParams.set("role", "client");
      url.searchParams.set("v", "2");
      client = new WebSocket(url);
      client.on("error", () => {});
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("stale client did not open")), 5_000);
        client!.once("open", () => { clearTimeout(timeout); resolve(); });
        client!.once("error", (error) => { clearTimeout(timeout); reject(error); });
      });
      const closed = new Promise<number>((resolve, reject) => {
        const timeout = setTimeout(
          () => reject(new Error("connector did not release the stale relay client")),
          5_000,
        );
        client!.once("close", (code) => { clearTimeout(timeout); resolve(code); });
      });
      const code = await closed;
      expect(code).toBeGreaterThan(0);
    } finally {
      client?.terminate();
      await runtime.stop();
      await rm(dir, { recursive: true, force: true });
    }
  }, 15_000);
});
