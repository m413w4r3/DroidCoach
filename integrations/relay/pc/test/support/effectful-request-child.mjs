import { randomBytes } from "node:crypto";
import { createConnector } from "../../dist/connector.js";
import { PROTOCOL } from "../../dist/protocol.js";

const [stateDir, backendUrl] = process.argv.slice(2);
if (!stateDir || !backendUrl) process.exit(2);

const runtime = await createConnector({
  stateDir,
  relayUrl: "wss://relay.example.org",
  allowLoopbackWs: false,
  serverId: "atlas-synthetic-crash-test",
  backendUrl,
  serviceToken: "synthetic-service-token",
  metricsPort: 0,
});

class TestChannel {
  sent = [];
  closeCallbacks = [];
  open = true;

  async send(data) {
    this.sent.push(JSON.parse(data));
  }

  close() {
    this.open = false;
    for (const callback of this.closeCallbacks) callback();
  }

  isOpen() { return this.open; }
  securityMode() { return "protected"; }
  outboundWireByteLength(data) { return Buffer.byteLength(data); }
  onTransitionToOpen() {}
  onClose(callback) { this.closeCallbacks.push(callback); }
}

const deviceId = "550e8400-e29b-41d4-a716-446655440003";
const token = Buffer.alloc(32, 7).toString("base64url");
const channel = new TestChannel();
const peer = runtime.sessions.create(channel);
await peer.receive(JSON.stringify({
  protocol: PROTOCOL,
  kind: "hello",
  device_id: deviceId,
  client_nonce: randomBytes(16).toString("base64"),
  client_version: "synthetic-crash-test",
  capabilities: ["plan.revision"],
  auth: { scheme: "device-token-v1", token },
  timestamp: new Date().toISOString(),
}));

const sessionId = channel.sent.at(-1)?.session_id;
const now = Date.now();
await peer.receive(JSON.stringify({
  protocol: PROTOCOL,
  kind: "request",
  request_id: "550e8400-e29b-41d4-a716-446655440004",
  session_id: sessionId,
  operation: "plan.revision.request",
  idempotency_key: "synthetic-crash-post-key",
  created_at: new Date(now).toISOString(),
  expires_at: new Date(now + 30_000).toISOString(),
  payload: {
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
  },
}));
