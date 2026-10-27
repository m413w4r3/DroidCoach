import { createHash } from "node:crypto";
import { createServer, type Server } from "node:http";
import { spawn, type ChildProcess } from "node:child_process";
import type { AddressInfo } from "node:net";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { createConnector, type ConnectorRuntime } from "../src/connector.js";
import { hello, FakeSecureChannel, request } from "./helpers.js";

const stateDirectories: string[] = [];
const activeRuntimes: ConnectorRuntime[] = [];
const DEVICE_ID = "550e8400-e29b-41d4-a716-446655440003";
const TEST_TOKEN = Buffer.alloc(32, 7).toString("base64url");
const IDEMPOTENCY_KEY = "synthetic-crash-post-key";
const REQUEST_ID = "550e8400-e29b-41d4-a716-446655440004";
const PLAN_REVISION_PAYLOAD = {
  schema_version: 3,
  request_id: "550e8400-e29b-41d4-a716-446655440000",
  conversation_id: null,
  message: "Adjust the synthetic plan",
  plan_context_consented: false,
  mandate_summary: { id: "synthetic-mandate", version: 1, mode: "proposals_only" },
  plan: {
    plan_id: "550e8400-e29b-41d4-a716-446655440001",
    local_date: "2026-01-01",
    timezone: "Europe/Paris",
    version: 1,
    state: "DRAFT",
    activities: [{
      activity_id: "550e8400-e29b-41d4-a716-446655440002",
      kind: "FOCUS",
      title: "Synthetic focus",
      start_minute: 540,
      duration_minutes: 30,
      priority: "OPTIONAL",
      goal_id: null,
    }],
  },
};

afterEach(async () => {
  for (const runtime of activeRuntimes.splice(0).reverse()) await runtime.stop();
  for (const directory of stateDirectories.splice(0)) await rm(directory, { recursive: true, force: true });
});

describe("crash-safe effect idempotency", () => {
  it("keeps an uncertain backend POST locked after a real child process SIGKILL", async () => {
    const stateDir = await mkdtemp(join(tmpdir(), "atlas-sigkill-test-"));
    stateDirectories.push(stateDir);
    const registry = {
      schema_version: 1,
      devices: [{
        device_id: DEVICE_ID,
        label: "synthetic crash test device",
        token_sha256: createHash("sha256").update(Buffer.from(TEST_TOKEN, "base64url")).digest("hex"),
        capabilities: ["plan.revision"],
        created_at: new Date().toISOString(),
        revoked_at: null,
      }],
    };
    await writeFile(join(stateDir, "devices.json"), `${JSON.stringify(registry)}\n`, { mode: 0o600 });

    let backendRequests = 0;
    let notifyDispatch!: () => void;
    let finishDelayedResponse!: () => void;
    const dispatched = new Promise<void>((resolve) => { notifyDispatch = resolve; });
    const responseAttempted = new Promise<void>((resolve) => { finishDelayedResponse = resolve; });
    const backendServer = createServer((incoming, outgoing) => {
      incoming.resume();
      incoming.on("end", () => {
        backendRequests += 1;
        notifyDispatch();
        setTimeout(() => {
          if (!outgoing.destroyed) {
            outgoing.writeHead(200, { "content-type": "application/json" });
            outgoing.end(JSON.stringify({ proposal_id: "synthetic-crash-proposal" }));
          }
          finishDelayedResponse();
        }, 200);
      });
    });
    await listenLoopback(backendServer);
    const address = backendServer.address() as AddressInfo;
    const backendUrl = `http://127.0.0.1:${address.port}`;
    const childScript = fileURLToPath(new URL("./support/effectful-request-child.mjs", import.meta.url));
    let child: ChildProcess | null = null;
    const stderr: string[] = [];
    try {
      child = spawn(process.execPath, [childScript, stateDir, backendUrl], { stdio: ["ignore", "ignore", "pipe"] });
      child.stderr?.setEncoding("utf8");
      child.stderr?.on("data", (chunk: string) => stderr.push(chunk));
      await Promise.race([
        dispatched,
        new Promise<never>((_, reject) => setTimeout(() => reject(new Error(`child did not dispatch request: ${stderr.join("")}`)), 10_000)),
        new Promise<never>((_, reject) => child?.once("exit", (code, signal) => reject(new Error(`child exited before dispatch (${code}, ${signal}): ${stderr.join("")}`)))),
      ]);

      const exited = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
        child?.once("exit", (code, signal) => resolve({ code, signal }));
      });
      child.kill("SIGKILL");
      expect(await exited).toMatchObject({ code: null, signal: "SIGKILL" });
      await responseAttempted;

      const runtime = await createConnector({
        stateDir,
        relayUrl: "wss://relay.example.org",
        allowLoopbackWs: false,
        serverId: "atlas-synthetic-crash-test",
        backendUrl,
        serviceToken: "synthetic-service-token",
        metricsPort: 0,
      });
      activeRuntimes.push(runtime);
      const channel = new FakeSecureChannel();
      const peer = runtime.sessions.create(channel);
      await peer.receive(JSON.stringify(hello(DEVICE_ID, TEST_TOKEN, { capabilities: ["plan.revision"] })));
      const sessionId = String(channel.lastMessage().session_id);
      await peer.receive(JSON.stringify(request(sessionId, "plan.revision.request", PLAN_REVISION_PAYLOAD, {
        request_id: REQUEST_ID,
        idempotency_key: IDEMPOTENCY_KEY,
      })));

      expect(channel.lastMessage()).toMatchObject({
        kind: "error",
        error: { code: "BACKEND_UNAVAILABLE", retryable: false },
      });
      expect(runtime.metrics.uncertainOutcomes).toBe(1);
      expect(backendRequests).toBe(1);
    } finally {
      if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      backendServer.closeAllConnections();
      await new Promise<void>((resolve) => backendServer.close(() => resolve()));
    }
  }, 20_000);
});

async function listenLoopback(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
}
