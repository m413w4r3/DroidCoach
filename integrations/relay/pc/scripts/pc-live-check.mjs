/**
 * Parent-owned live check: real Paseo relay + real connector + real FastAPI.
 *
 * This is Node-to-Node evidence only. Android E2E is a separate proof.
 * Secrets are read from the environment and never printed.
 */
import { randomBytes, randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { openClientSecureChannel } from "@getpaseo/relay/e2ee";
import { createConnector } from "../dist/connector.js";
import { loadOrCreateIdentity } from "../dist/identity.js";

const RELAY_URL = process.env.ATLAS_TEST_RELAY_URL ?? "ws://127.0.0.1:4400";
const BACKEND_URL = process.env.ATLAS_BACKEND_URL ?? "http://127.0.0.1:8100";
const SERVICE_TOKEN = process.env.ATLAS_SERVICE_TOKEN;
const SERVER_ID = `atlas-r3a-live-${randomUUID().slice(0, 8)}`;

if (!SERVICE_TOKEN) {
  console.error("ATLAS_SERVICE_TOKEN is required");
  process.exit(2);
}

class RelayClient {
  constructor(socket) {
    this.socket = socket;
    this.inbox = [];
    this.waiters = [];
    this.channel = null;
  }

  static async connect(serverId, daemonPublicKeyB64) {
    const url = new URL("/ws", RELAY_URL);
    url.searchParams.set("serverId", serverId);
    url.searchParams.set("role", "client");
    url.searchParams.set("v", "2");
    const socket = new WebSocket(url);
    socket.on("error", () => {});
    const client = new RelayClient(socket);
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("relay socket timeout")), 8_000);
      socket.once("open", () => { clearTimeout(timeout); resolve(); });
      socket.once("error", () => { clearTimeout(timeout); reject(new Error("relay socket failed")); });
    });
    const transport = {
      onmessage: null,
      onclose: null,
      onerror: null,
      send: (data) => {
        if (socket.readyState !== WebSocket.OPEN) throw new Error("client closed");
        if (typeof data === "string") socket.send(data, { binary: false });
        else socket.send(Buffer.from(data), { binary: true });
      },
      close: (code = 1000, reason = "closed") => {
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(code, reason);
      },
    };
    socket.on("message", (data, isBinary) => {
      if (isBinary) {
        const copy = Uint8Array.from(data);
        transport.onmessage?.({ data: copy.buffer, isBinary: true });
      } else {
        transport.onmessage?.({ data: data.toString("utf8"), isBinary: false });
      }
    });
    const opened = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("protected channel timeout")), 8_000);
      client.onOpen = () => { clearTimeout(timeout); resolve(); };
      client.onError = () => { clearTimeout(timeout); reject(new Error("protected handshake failed")); };
    });
    client.channel = await openClientSecureChannel(transport, {
      daemonPublicKeyB64,
      minimumMode: "protected",
      events: {
        onopen: () => client.onOpen?.(),
        onmessage: (data) => client.receive(data),
        onerror: () => client.onError?.(),
      },
    });
    await opened;
    if (client.channel.securityMode() !== "protected") throw new Error("non-protected mode negotiated");
    return client;
  }

  receive(data) {
    try {
      const text = typeof data === "string" ? data : new TextDecoder("utf-8", { fatal: true }).decode(data);
      const value = JSON.parse(text);
      if (value && typeof value === "object" && !Array.isArray(value)) {
        this.inbox.push(value);
        this.flush();
      }
    } catch {
      // Ignore unparseable application records in this diagnostic client.
    }
  }

  async send(value) {
    await this.channel.send(JSON.stringify(value));
  }

  async waitFor(predicate, label, timeoutMs = 10_000) {
    const index = this.inbox.findIndex(predicate);
    if (index >= 0) return this.inbox.splice(index, 1)[0];
    return new Promise((resolve, reject) => {
      let timer;
      const waiter = {
        predicate,
        resolve: (value) => { clearTimeout(timer); resolve(value); },
        reject: (error) => { clearTimeout(timer); reject(error); },
      };
      timer = setTimeout(() => {
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        const kinds = this.inbox.map((message) => `${message.kind}${message.error ? `:${message.error.code}` : ""}`).join(",");
        reject(new Error(`${label} timeout; observed=${kinds || "none"}`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  flush() {
    for (const waiter of [...this.waiters]) {
      const index = this.inbox.findIndex(waiter.predicate);
      if (index < 0) continue;
      this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
      waiter.resolve(this.inbox.splice(index, 1)[0]);
    }
  }

  async close() {
    this.channel.close(1000, "live check complete");
    if (this.socket.readyState !== WebSocket.CLOSED) {
      await new Promise((resolve) => {
        this.socket.once("close", resolve);
        setTimeout(resolve, 1_000).unref?.();
      });
    }
  }
}

function hello(deviceId, token) {
  return {
    protocol: "atlas.remote.v1",
    kind: "hello",
    device_id: deviceId,
    client_nonce: randomBytes(16).toString("base64"),
    client_version: "0.1.0-r3a-live-check",
    capabilities: ["plan.revision", "coach.status", "sync.status"],
    auth: { scheme: "device-token-v1", token },
    timestamp: new Date().toISOString(),
  };
}

function request(sessionId, operation, payload, requestId = randomUUID(), idempotencyKey = randomUUID()) {
  const now = Date.now();
  return {
    protocol: "atlas.remote.v1",
    kind: "request",
    request_id: requestId,
    session_id: sessionId,
    operation,
    idempotency_key: idempotencyKey,
    created_at: new Date(now).toISOString(),
    expires_at: new Date(now + 30_000).toISOString(),
    payload,
  };
}

function planPayload() {
  return {
    schema_version: 3,
    request_id: randomUUID(),
    conversation_id: "conversation-r3a-live-check",
    message: "Propose un décalage du bloc de révision.",
    plan_context_consented: true,
    mandate_summary: { id: "mandate-r3a", version: 2, allowed_actions: ["focus.propose"] },
    plan: {
      plan_id: "11111111-1111-4111-8111-111111111111",
      local_date: "2026-10-26",
      timezone: "Europe/Paris",
      version: 4,
      state: "DRAFT",
      activities: [{
        activity_id: "22222222-2222-4222-8222-222222222222",
        kind: "FOCUS",
        title: "Réviser les notes",
        start_minute: 480,
        duration_minutes: 50,
        priority: "IMPORTANT",
        goal_id: null,
      }],
    },
  };
}

async function main() {
  const stateDir = await mkdtemp(join(tmpdir(), "atlas-r3a-live-"));
  const steps = [];
  let runtime;
  let client;
  try {
    runtime = await createConnector({
      stateDir,
      relayUrl: RELAY_URL,
      serverId: SERVER_ID,
      backendUrl: BACKEND_URL,
      serviceToken: SERVICE_TOKEN,
      metricsPort: 0,
    });
    const identity = await loadOrCreateIdentity(runtime.identityPath);
    const device = await runtime.registry.pair("synthetic live-check device", [
      "plan.revision", "coach.status", "sync.status",
    ]);
    await runtime.start();
    await runtime.relay.waitForControl(8_000);
    steps.push("relay_control");

    client = await RelayClient.connect(SERVER_ID, identity.identity.public_key);
    steps.push("noise_protected");

    await client.send(hello(device.device.device_id, device.token));
    const helloOk = await client.waitFor((message) => message.kind === "hello_ok", "hello_ok");
    steps.push("device_auth");
    const sessionId = helloOk.session_id;

    const health = request(sessionId, "backend.health", {});
    await client.send(health);
    const healthResponse = await client.waitFor(
      (message) => message.kind === "response" && message.request_id === health.request_id,
      "backend.health response",
    );
    if (healthResponse.result?.status !== "ok") throw new Error("backend.health was not ok");
    steps.push("fastapi_health");

    const revision = request(sessionId, "plan.revision.request", planPayload());
    await client.send(revision);
    const revisionResponse = await client.waitFor(
      (message) => message.kind === "response" && message.request_id === revision.request_id,
      "plan.revision.response",
    );
    const proposal = revisionResponse.result?.proposal;
    if (revisionResponse.result?.agent_status !== "ok" || !proposal?.proposal?.parameters) {
      throw new Error("plan revision was not a valid proposal");
    }
    steps.push("fastapi_plan_revision");

    await client.send({ ...revision });
    const replay = await client.waitFor(
      (message) => message.kind === "response" && message.request_id === revision.request_id && message.replayed === true,
      "idempotent replay",
    );
    if (JSON.stringify(replay.result) !== JSON.stringify(revisionResponse.result)) {
      throw new Error("idempotent replay changed the result");
    }
    steps.push("idempotent_replay");

    const resultLookup = request(sessionId, "plan.revision.result", { request_id: revision.request_id });
    await client.send(resultLookup);
    const lookupResponse = await client.waitFor(
      (message) => message.kind === "response" && message.request_id === resultLookup.request_id,
      "plan.revision.result",
    );
    if (lookupResponse.result?.request_id !== revision.request_id) {
      throw new Error("cached result lookup returned another request");
    }
    steps.push("cached_result");

    console.log(JSON.stringify({
      status: "PASS",
      scope: "Node client -> real relay -> protected connector -> real FastAPI",
      server_id: SERVER_ID,
      steps,
      handshake: "protected",
      proposal_plan_id: proposal.proposal.parameters.plan_id,
      proposal_base_version: proposal.proposal.parameters.base_version,
      android_e2e: "NOT_RUN",
    }, null, 2));
  } finally {
    await client?.close().catch(() => {});
    await runtime?.stop().catch(() => {});
    await rm(stateDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(JSON.stringify({ status: "FAIL", message: error instanceof Error ? error.message : "unknown" }));
  process.exitCode = 1;
});
