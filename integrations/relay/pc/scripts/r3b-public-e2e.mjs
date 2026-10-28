/**
 * Parent-run public Funnel end-to-end check.
 *
 * It refuses to print tokens, message text, reply text, or raw frames. It
 * exercises the public WSS path, Noise NK protected handshake, device auth,
 * backend.health, sync.status, coach.chat.request, replay and error handling.
 *
 * Required environment:
 *   ATLAS_E2E_SERVER_ID, ATLAS_E2E_PUBLIC_KEY, ATLAS_E2E_DEVICE_ID,
 *   ATLAS_E2E_TOKEN_FILE
 * Optional:
 *   ATLAS_E2E_URL (default wss://all.taildc7886.ts.net:8443)
 *   ATLAS_E2E_PUBLIC_IP (DNS override for a public-ingress test)
 *   ATLAS_E2E_CAPABILITIES (comma-separated; default all client capabilities)
 *   ATLAS_E2E_EXPECT_CHAT_UNAVAILABLE=1 (capability-refusal check)
 */
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import WebSocket from "ws";
import { openClientSecureChannel } from "@getpaseo/relay/e2ee";

const PROTOCOL = "atlas.remote.v1";
const URL_BASE = process.env.ATLAS_E2E_URL ?? "wss://all.taildc7886.ts.net:8443";
const SERVER_ID = required("ATLAS_E2E_SERVER_ID");
const PUBLIC_KEY = required("ATLAS_E2E_PUBLIC_KEY");
const DEVICE_ID = required("ATLAS_E2E_DEVICE_ID");
const TOKEN_FILE = required("ATLAS_E2E_TOKEN_FILE");
const PUBLIC_IP = process.env.ATLAS_E2E_PUBLIC_IP || undefined;
const CAPABILITIES = (process.env.ATLAS_E2E_CAPABILITIES ??
  "coach.chat,coach.status,plan.revision,plan.today,sport.plan,sync.status")
  .split(",").map((value) => value.trim()).filter(Boolean);
const EXPECT_CHAT_UNAVAILABLE = process.env.ATLAS_E2E_EXPECT_CHAT_UNAVAILABLE === "1";
const ONLY = process.env.ATLAS_E2E_ONLY || "";
const CHAT_MESSAGE = process.env.ATLAS_E2E_CHAT_MESSAGE || "Propose une prochaine étape synthétique R3B.";
const EXPECT_HEALTH = process.env.ATLAS_E2E_EXPECT_HEALTH || "ok";

const results = [];
let failure = null;

class RelayClient {
  constructor(socket) {
    this.socket = socket;
    this.inbox = [];
    this.waiters = [];
    this.channel = null;
  }

  static async connect() {
    const url = new URL("/ws", URL_BASE);
    url.searchParams.set("serverId", SERVER_ID);
    url.searchParams.set("role", "client");
    url.searchParams.set("v", "2");
    const options = { followRedirects: false, handshakeTimeout: 10_000 };
    if (PUBLIC_IP) {
      options.lookup = (hostname, opts, callback) => {
        if (typeof opts === "function") { callback = opts; opts = {}; }
        if (hostname !== "all.taildc7886.ts.net") return callback(new Error("unexpected DNS host"));
        if (opts && opts.all) return callback(null, [{ address: PUBLIC_IP, family: 4 }]);
        callback(null, PUBLIC_IP, 4);
      };
    }
    const socket = new WebSocket(url, options);
    const client = new RelayClient(socket);
    socket.on("error", () => {});
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("relay socket timeout")), 10_000);
      socket.once("open", () => { clearTimeout(timer); resolve(); });
      socket.once("error", () => { clearTimeout(timer); reject(new Error("relay socket failed")); });
    });
    const transport = {
      onmessage: null,
      onclose: null,
      onerror: null,
      send(data) {
        if (socket.readyState !== WebSocket.OPEN) throw new Error("transport closed");
        if (typeof data === "string") socket.send(data, { binary: false });
        else socket.send(Buffer.from(data), { binary: true });
      },
      close(code = 1000, reason = "closed") {
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(code, reason);
      },
    };
    socket.on("message", (data, isBinary) => {
      if (isBinary) transport.onmessage?.({ data: Uint8Array.from(data).buffer, isBinary: true });
      else transport.onmessage?.({ data: data.toString("utf8"), isBinary: false });
    });
    socket.on("close", (code, reason) => transport.onclose?.(code, reason.toString("utf8")));
    socket.on("error", () => transport.onerror?.(new Error("socket error")));
    let resolveOpen;
    let rejectOpen;
    const opened = new Promise((resolve, reject) => { resolveOpen = resolve; rejectOpen = reject; });
    const channel = await openClientSecureChannel(transport, {
      daemonPublicKeyB64: PUBLIC_KEY,
      minimumMode: "protected",
      events: {
        onopen: () => resolveOpen(),
        onmessage: (data) => client.receive(data),
        onerror: () => rejectOpen(new Error("secure channel error")),
      },
    });
    await Promise.race([
      opened,
      new Promise((_, reject) => setTimeout(() => reject(new Error("secure channel open timeout")), 10_000)),
    ]);
    if (channel.securityMode() !== "protected") throw new Error("non-protected Noise mode");
    client.channel = channel;
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
      // The check does not retain unparseable records.
    }
  }

  send(value) {
    this.channel.send(JSON.stringify(value));
  }

  flush() {
    for (const waiter of [...this.waiters]) {
      const index = this.inbox.findIndex(waiter.predicate);
      if (index >= 0) {
        const value = this.inbox.splice(index, 1)[0];
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        clearTimeout(waiter.timer);
        waiter.resolve(value);
      }
    }
  }

  waitFor(predicate, label, timeoutMs = 20_000) {
    const index = this.inbox.findIndex(predicate);
    if (index >= 0) return Promise.resolve(this.inbox.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        reject(new Error(`${label} timeout`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  close() {
    try { this.channel?.close(1000, "client done"); } catch {}
    try { this.socket.close(); } catch {}
  }
}

function request(sessionId, operation, payload, idempotencyKey = randomUUID(), requestId = randomUUID(), ttlMs = 30_000) {
  const now = Date.now();
  return {
    protocol: PROTOCOL,
    kind: "request",
    request_id: requestId,
    session_id: sessionId,
    operation,
    idempotency_key: idempotencyKey,
    created_at: new Date(now).toISOString(),
    expires_at: new Date(now + ttlMs).toISOString(),
    payload,
  };
}

async function exchange(client, envelope, label, timeoutMs = 30_000) {
  client.send(envelope);
  const message = await client.waitFor(
    (value) => (value.kind === "response" || value.kind === "error") && value.request_id === envelope.request_id,
    label,
    timeoutMs,
  );
  return message;
}

function record(name, status, details = {}) {
  results.push({ name, status, ...details });
  if (status !== "PASS") failure ??= name;
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function chatPayload(message = CHAT_MESSAGE) {
  return {
    conversation_id: "r3b-public-e2e-conversation",
    message,
    shared_context: {
      consented_categories: ["projects"],
      categories: { projects: ["Projet synthétique R3B"] },
    },
    mandate_summary: {
      id: "r3b-public-e2e-mandate",
      version: 1,
      allowed_actions: ["home.set_directive", "focus.propose"],
      mode: "proposals_only",
      focus_window: null,
      app_policy_bounds: {},
    },
  };
}

async function main() {
  const deviceToken = JSON.parse(await readFile(TOKEN_FILE, "utf8")).token;
  if (typeof deviceToken !== "string" || deviceToken.length !== 43) throw new Error("invalid synthetic token file");
  const client = await RelayClient.connect();
  try {
    const nonce = Buffer.from(Array.from({ length: 16 }, () => Math.floor(Math.random() * 256))).toString("base64");
    const hello = {
      protocol: PROTOCOL,
      kind: "hello",
      device_id: DEVICE_ID,
      client_nonce: nonce,
      client_version: "0.1.0-r3b-public-e2e",
      capabilities: CAPABILITIES,
      auth: { scheme: "device-token-v1", token: deviceToken },
      timestamp: new Date().toISOString(),
    };
    client.send(hello);
    const helloOk = await client.waitFor((value) => value.kind === "hello_ok", "hello_ok", 15_000);
    const sessionId = helloOk.session_id;
    record("noise_protected_and_device_auth", "PASS", { session_id_present: typeof sessionId === "string", capabilities: Array.isArray(helloOk.capabilities) });

    const health = await exchange(client, request(sessionId, "backend.health", {}), "backend.health");
    const healthObserved = health.kind === "response" ? health.result?.status : health.error?.code;
    const healthOk = EXPECT_HEALTH === "ok"
      ? health.kind === "response" && health.result?.status === "ok"
      : health.kind === "error" && health.error?.code === EXPECT_HEALTH;
    record("backend_health_via_funnel", healthOk ? "PASS" : "FAIL",
      { kind: health.kind, health_status: healthObserved, expected: EXPECT_HEALTH });
    if (ONLY === "health") {
      console.log(JSON.stringify({
        url: URL_BASE,
        public_ip_override: Boolean(PUBLIC_IP),
        only: "health",
        expected: EXPECT_HEALTH,
        results,
      }, null, 2));
      if (failure) process.exitCode = 1;
      return;
    }

    const sync = await exchange(client, request(sessionId, "sync.status", {}), "sync.status");
    record("sync_status_via_funnel", sync.kind === "response" && sync.result?.relay_connected === true ? "PASS" : "FAIL",
      { kind: sync.kind, relay_connected: sync.result?.relay_connected, backend_configured: sync.result?.backend_configured });

    const chat = request(sessionId, "coach.chat.request", chatPayload(), randomUUID(), randomUUID(), 55_000);
    const first = await exchange(client, chat, "coach.chat.request", 58_000);
    if (EXPECT_CHAT_UNAVAILABLE) {
      record("chat_requires_capability", first.kind === "error" && first.error?.code === "OPERATION_UNAVAILABLE" ? "PASS" : "FAIL",
        { kind: first.kind, error: first.error?.code });
    } else {
      record("coach_chat_relayed", first.kind === "response" && (first.result?.agent_status === "mock" || first.result?.agent_status === "connected") ? "PASS" : "FAIL",
        { kind: first.kind, agent_status: first.result?.agent_status, reply_length: first.result?.reply?.length ?? null,
          proposals: first.result?.proposals?.length ?? null, replayed: first.replayed ?? null });
      if (first.kind === "response") {
        const replay = await exchange(client, chat, "coach.chat.request replay");
        record("coach_chat_replay_no_duplicate", replay.kind === "response" && replay.replayed === true ? "PASS" : "FAIL",
          { kind: replay.kind, replayed: replay.replayed ?? null, error: replay.error?.code });

        const conflict = request(sessionId, "coach.chat.request", chatPayload("Message synthétique différent."), chat.idempotency_key);
        const conflictResult = await exchange(client, conflict, "coach.chat.request conflict");
        record("coach_chat_idempotency_conflict", conflictResult.kind === "error" && conflictResult.error?.code === "IDEMPOTENCY_CONFLICT" ? "PASS" : "FAIL",
          { kind: conflictResult.kind, error: conflictResult.error?.code });

        const tooLong = request(sessionId, "coach.chat.request", chatPayload("x".repeat(2001)));
        const tooLongResult = await exchange(client, tooLong, "coach.chat.request oversized");
        record("coach_chat_payload_limit", tooLongResult.kind === "error" && tooLongResult.error?.code === "INVALID_REQUEST" ? "PASS" : "FAIL",
          { kind: tooLongResult.kind, error: tooLongResult.error?.code });
      }
    }
  } finally {
    client.close();
  }
  console.log(JSON.stringify({
    url: URL_BASE,
    public_ip_override: Boolean(PUBLIC_IP),
    capabilities: CAPABILITIES,
    expect_chat_unavailable: EXPECT_CHAT_UNAVAILABLE,
    results,
  }, null, 2));
  if (failure) process.exitCode = 1;
}

main().catch((error) => {
  console.log(JSON.stringify({ results, fatal: error.message }, null, 2));
  process.exitCode = 1;
});
