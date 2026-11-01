#!/usr/bin/env node
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import WebSocket from "ws";
import { openClientSecureChannel } from "@getpaseo/relay/e2ee";

const PROTOCOL = "atlas.remote.v2";
const CAPABILITIES = [
  "plan.revision", "coach.chat", "coach.status", "plan.today", "sport.plan", "sync.status",
  "v4.day_plan.snapshot.write", "v4.activity_status.write", "v4.usage_summary.write",
  "v4.goal_progress.write", "v4.training_plan.write", "v4.proposal.ack", "v4.coach.chat",
  "v4.consent.state.write",
];

class SafeFailure extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

class RelayClient {
  constructor(socket) {
    this.socket = socket;
    this.inbox = [];
    this.waiters = [];
    this.channel = null;
    this.openCallback = null;
    this.errorCallback = null;
  }

  static async connect(serverId, daemonPublicKeyB64, relayUrl) {
    const url = new URL("/ws", relayUrl);
    url.searchParams.set("serverId", serverId);
    url.searchParams.set("role", "client");
    url.searchParams.set("v", "2");
    const socket = new WebSocket(url);
    const client = new RelayClient(socket);
    socket.on("error", () => {});
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new SafeFailure("RELAY_SOCKET_TIMEOUT")), 8000);
      socket.once("open", () => { clearTimeout(timer); resolve(); });
      socket.once("error", () => { clearTimeout(timer); reject(new SafeFailure("RELAY_SOCKET_FAILED")); });
    });
    const transport = {
      onmessage: null,
      onclose: null,
      onerror: null,
      send: (data) => {
        if (socket.readyState !== WebSocket.OPEN) throw new SafeFailure("RELAY_SOCKET_CLOSED");
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
    const channelReady = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new SafeFailure("NOISE_HANDSHAKE_TIMEOUT")), 8000);
      client.openCallback = () => { clearTimeout(timer); resolve(); };
      client.errorCallback = () => { clearTimeout(timer); reject(new SafeFailure("NOISE_HANDSHAKE_FAILED")); };
    });
    client.channel = await openClientSecureChannel(transport, {
      daemonPublicKeyB64,
      minimumMode: "protected",
      events: {
        onopen: () => client.openCallback?.(),
        onmessage: (data) => client.receiveApplication(data),
        onerror: () => client.errorCallback?.(),
      },
    });
    await channelReady;
    if (client.channel.securityMode() !== "protected") throw new SafeFailure("NOISE_CHANNEL_NOT_PROTECTED");
    return client;
  }

  receiveApplication(data) {
    try {
      const text = typeof data === "string" ? data : new TextDecoder("utf-8", { fatal: true }).decode(data);
      const value = JSON.parse(text);
      if (isRecord(value)) {
        this.inbox.push(value);
        this.flushWaiters();
      }
    } catch {
      // Malformed application frames are discarded without logging their contents.
    }
  }

  async send(value) {
    await this.channel.send(JSON.stringify(value));
  }

  async waitFor(predicate, timeoutMs = 12000, errorCode = "RELAY_RESPONSE_TIMEOUT") {
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
        reject(new SafeFailure(errorCode));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  async close() {
    this.channel?.close(1000, "v7-06c scenario complete");
    if (this.socket.readyState !== WebSocket.CLOSED) {
      await new Promise((resolve) => {
        this.socket.once("close", resolve);
        const timer = setTimeout(resolve, 1000);
        timer.unref?.();
      });
    }
  }

  flushWaiters() {
    for (const waiter of [...this.waiters]) {
      const index = this.inbox.findIndex(waiter.predicate);
      if (index < 0) continue;
      this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
      waiter.resolve(this.inbox.splice(index, 1)[0]);
    }
  }
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (!isRecord(value)) return JSON.stringify(value);
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
}

function hash(value) {
  return createHash("sha256").update(String(value), "utf8").digest("hex").slice(0, 16);
}

function traceCount(path) {
  return readFile(path, "utf8").then((raw) => raw.split("\n").filter(Boolean).length);
}

async function readJson(path, errorCode) {
  try {
    const parsed = JSON.parse(await readFile(path, "utf8"));
    if (!isRecord(parsed)) throw new Error();
    return parsed;
  } catch {
    throw new SafeFailure(errorCode);
  }
}

async function connectAndHello(config, pairing) {
  const client = await RelayClient.connect(config.server_id, config.server_public_key, config.relay_url_host);
  const hello = {
    protocol: PROTOCOL,
    kind: "hello",
    device_id: pairing.device_id,
    client_nonce: randomBytes(16).toString("base64"),
    client_version: "0.1.0-v7-06c",
    capabilities: CAPABILITIES,
    auth: { scheme: "device-token-v1", token: pairing.token },
    timestamp: new Date().toISOString(),
  };
  await client.send(hello);
  const accepted = await client.waitFor((value) => value.kind === "hello_ok", 10000, "RELAY_HELLO_TIMEOUT");
  if (accepted.protocol !== PROTOCOL || typeof accepted.session_id !== "string" ||
      accepted.expires_in_seconds !== 3600 || !Array.isArray(accepted.capabilities) ||
      accepted.capabilities.length !== 14) {
    throw new SafeFailure("RELAY_HELLO_INVALID");
  }
  return { client, sessionId: accepted.session_id };
}

function requestEnvelope(operation, payload, sessionId, overrides = {}) {
  const requestId = overrides.requestId ?? payload.request_id ?? randomUUID();
  const idempotencyKey = overrides.idempotencyKey ?? payload.idempotency_key ?? payload.ack_id ?? payload.request_id ?? requestId;
  const now = Date.now();
  return {
    protocol: PROTOCOL,
    kind: "request",
    request_id: requestId,
    session_id: sessionId,
    operation,
    idempotency_key: idempotencyKey,
    created_at: new Date(now).toISOString(),
    expires_at: new Date(now + 30000).toISOString(),
    payload,
  };
}

async function exchange(client, operation, payload, sessionId, options = {}) {
  const envelope = options.envelope ?? requestEnvelope(operation, payload, sessionId, options);
  await client.send(envelope);
  const answer = await client.waitFor(
    (value) => value.request_id === envelope.request_id && (value.kind === "response" || value.kind === "error"),
    options.timeoutMs ?? 90000,
    "RELAY_OPERATION_TIMEOUT",
  );
  return { envelope, answer };
}

function requireResponse(response) {
  if (response.answer.kind !== "response" || response.answer.status !== "OK" || !isRecord(response.answer.result)) {
    const code = getRemoteError(response);
    throw new SafeFailure(code === "UNKNOWN_ERROR" ? "RELAY_OPERATION_FAILED" : code);
  }
  return response.answer.result;
}

function getRemoteError(response) {
  const error = isRecord(response.answer.error) ? response.answer.error : {};
  const candidate = typeof error.remote_code === "string" ? error.remote_code :
    (typeof error.code === "string" ? error.code : "UNKNOWN_ERROR");
  return /^[A-Z0-9_]{1,64}$/.test(candidate) ? candidate : "UNKNOWN_ERROR";
}

function consentPayload(category, version, state, requestId = randomUUID()) {
  return {
    contract_version: 4,
    schema_version: 4,
    request_id: requestId,
    capability: "consent.state.write",
    sent_at: new Date().toISOString(),
    category,
    consent_version: version,
    state,
    idempotency_key: randomUUID(),
  };
}

async function sendConsent(client, sessionId, category, version, state) {
  const payload = consentPayload(category, version, state);
  const result = await exchange(client, "v4.consent.state.upsert", payload, sessionId);
  return requireResponse(result);
}

async function sendPlanSnapshot(client, sessionId, plan, version, consentVersion) {
  const requestId = randomUUID();
  const payload = {
    contract_version: 4,
    schema_version: 4,
    request_id: requestId,
    capability: "day_plan.snapshot.write",
    sent_at: new Date().toISOString(),
    consent_version: consentVersion,
    idempotency_key: randomUUID(),
    data: { ...plan, version, observed_at: new Date().toISOString() },
  };
  const response = await exchange(client, "v4.plan.snapshot.upsert", payload, sessionId);
  return requireResponse(response);
}

function proposalList(result) {
  return Array.isArray(result.proposals) ? result.proposals : [];
}

async function pull(client, sessionId, consentVersion) {
  const payload = {
    contract_version: 4,
    schema_version: 4,
    request_id: randomUUID(),
    capability: "proposal.ack",
    consent_version: consentVersion,
    page_size: 10,
    cursor: null,
  };
  const response = await exchange(client, "v4.proposals.pull", payload, sessionId);
  return { result: requireResponse(response), response };
}

async function directCoreReplay(config, coreToken, chatPayload, relayResult) {
  const before = await traceCount(config.proxy_trace);
  let response;
  try {
    response = await fetch(`${config.core_url}/v4/agent/chat`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${coreToken}`,
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Capability": "coach.chat",
        "X-Atlas-Request-Id": chatPayload.request_id,
      },
      body: JSON.stringify(chatPayload),
      signal: AbortSignal.timeout(15000),
    });
  } catch {
    throw new SafeFailure("CORE_CACHE_REPLAY_UNAVAILABLE");
  }
  const replay = await response.json().catch(() => null);
  const after = await traceCount(config.proxy_trace);
  if (response.status !== 200 || !isRecord(replay) || stableJson(replay) !== stableJson(relayResult) || before !== after) {
    throw new SafeFailure("CORE_CHAT_CACHE_REPLAY_MISMATCH");
  }
  return { status: response.status, traceCountBefore: before, traceCountAfter: after };
}

async function internalPlanReadback(config, expectedVersion = 3, expectedStartMinute = 610) {
  const toolToken = (await readFile(config.tool_token_file, "utf8")).trim();
  const requestId = randomUUID();
  let response;
  try {
    const query = new URLSearchParams({ local_date: config.plan.local_date });
    response = await fetch(`${config.core_url}/internal/v4/day-plan?${query}`, {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${toolToken}`,
        "Accept": "application/json",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Capability": "day_plan.read",
        "X-Atlas-Request-Id": requestId,
      },
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    throw new SafeFailure("INTERNAL_PLAN_READBACK_UNAVAILABLE");
  }
  const body = await response.json().catch(() => null);
  const plan = isRecord(body) && isRecord(body.data) && isRecord(body.data.plan) ? body.data.plan : null;
  const activity = plan && Array.isArray(plan.activities) ? plan.activities[0] : null;
  if (response.status !== 200 || !isRecord(body) || body.freshness !== "FRESH" ||
      plan?.version !== expectedVersion || !isRecord(activity) ||
      activity.scheduled_local?.start_minute !== expectedStartMinute) {
    throw new SafeFailure("INTERNAL_PLAN_READBACK_MISMATCH");
  }
  return { version: plan.version, start_minute: activity.scheduled_local.start_minute,
    freshness: body.freshness };
}

async function positive(config, pairing, coreToken) {
  const { client, sessionId } = await connectAndHello(config, pairing);
  try {
    await sendConsent(client, sessionId, "DAY_PLAN", 1, "GRANTED");
    await sendConsent(client, sessionId, "ACTIVITY_STATUS", 1, "GRANTED");
    await sendPlanSnapshot(client, sessionId, config.plan, 2, 1);

    const chatSent = await exchange(client, "v4.coach.chat.request", config.chat_payload, sessionId, {
      requestId: config.chat_payload.request_id,
      idempotencyKey: config.chat_payload.request_id,
      timeoutMs: 90000,
    });
    const chatResult = requireResponse(chatSent);
    if (chatResult.agent_status !== "ok" || !Array.isArray(chatResult.proposal_ids) ||
        chatResult.proposal_ids.length !== 1 || chatResult.tool_call_count !== 2) {
      throw new SafeFailure("REAL_CHAT_DID_NOT_CREATE_ONE_PROPOSAL");
    }
    const cacheReplay = await directCoreReplay(config, coreToken, config.chat_payload, chatResult);
    const traceBeforeRelayReplay = await traceCount(config.proxy_trace);
    const relayReplay = await exchange(client, "v4.coach.chat.request", config.chat_payload, sessionId, {
      envelope: chatSent.envelope,
      timeoutMs: 30000,
    });
    const relayReplayResult = requireResponse(relayReplay);
    const traceAfterRelayReplay = await traceCount(config.proxy_trace);
    if (stableJson(relayReplayResult) !== stableJson(chatResult) ||
        relayReplay.answer.replayed !== true || traceBeforeRelayReplay !== traceAfterRelayReplay) {
      throw new SafeFailure("RELAY_CHAT_REPLAY_MISMATCH");
    }

    const proposalId = chatResult.proposal_ids[0];
    const pulled = await pull(client, sessionId, 1);
    const proposal = proposalList(pulled.result).find((item) => item.proposal_id === proposalId);
    if (!proposal || proposal.base_version !== 2) throw new SafeFailure("PROPOSAL_NOT_AVAILABLE_IN_PULL");
    const beforeAckReadback = await internalPlanReadback(config, 2, 600);
    const ackId = randomUUID();
    const revisionId = randomUUID();
    const effectId = `PLAN_APPLY:${randomUUID()}`;
    const scopeHash = `sha256:${createHash("sha256").update(`${proposalId}:${revisionId}`).digest("hex")}`;
    const ackPayload = {
      contract_version: 4,
      schema_version: 4,
      ack_id: ackId,
      capability: "proposal.ack",
      proposal_id: proposalId,
      sequence: 1,
      state: "APPLIED",
      result_code: "APPLIED",
      occurred_at: new Date().toISOString(),
      plan_id: config.plan.plan_id,
      applied_plan_version: 3,
      revision_id: revisionId,
      scope_hash: scopeHash,
      effect_ids: [effectId],
      consent_version: 1,
    };
    const ack = await exchange(client, "v4.proposals.ack", ackPayload, sessionId, {
      idempotencyKey: ackId,
      timeoutMs: 30000,
    });
    const ackResult = requireResponse(ack);
    const ackReplay = await exchange(client, "v4.proposals.ack", ackPayload, sessionId, {
      envelope: ack.envelope,
      timeoutMs: 30000,
    });
    const ackReplayResult = requireResponse(ackReplay);
    if (stableJson(ackReplayResult) !== stableJson(ackResult) || ackReplay.answer.replayed !== true) {
      throw new SafeFailure("ACK_REPLAY_MISMATCH");
    }
    await sendPlanSnapshot(client, sessionId, { ...config.plan, current_revision_id: revisionId,
      activities: config.plan.activities.map((activity) => ({
        ...activity,
        scheduled_local: { ...activity.scheduled_local, start_minute: 610 },
      })) }, 3, 1);
    const afterAck = await pull(client, sessionId, 1);
    if (proposalList(afterAck.result).length !== 0) throw new SafeFailure("ACKED_PROPOSAL_REMAINED_IN_PULL");
    const readback = await internalPlanReadback(config);
    await sendConsent(client, sessionId, "DAY_PLAN", 2, "REVOKED");
    await sendConsent(client, sessionId, "ACTIVITY_STATUS", 2, "REVOKED");
    return {
      status: "PASS",
      scenario: "positive",
      device_id_hash: hash(pairing.device_id),
      plan_id: config.plan.plan_id,
      activity_id: config.plan.activities[0].activity_id,
      chat_request_id_hash: hash(config.chat_payload.request_id),
      conversation_id_hash: hash(config.chat_payload.conversation_id),
      chat_agent_status: chatResult.agent_status,
      tool_call_count: chatResult.tool_call_count,
      reply_sha256: createHash("sha256").update(chatResult.reply ?? "", "utf8").digest("hex"),
      proposal_id: proposalId,
      proposal_base_version: proposal.base_version,
      confirmation_mode: proposal.confirmation_mode,
      auto_application: proposal.auto_application,
      pull_count_before_ack: proposalList(pulled.result).length,
      plan_before_ack: beforeAckReadback,
      ack_state: ackPayload.state,
      ack_result_code: ackPayload.result_code,
      applied_plan_version: ackPayload.applied_plan_version,
      ack_response_hash: hash(stableJson(ackResult)),
      ack_replay: { identical: true, replayed: ackReplay.answer.replayed === true },
      snapshot_after_ack: { version: 3, start_minute: 610 },
      internal_plan_readback: readback,
      pull_count_after_ack: proposalList(afterAck.result).length,
      core_cache_replay: cacheReplay,
      relay_cache_replay: { identical: true, replayed: relayReplay.answer.replayed === true,
        proxy_count_before: traceBeforeRelayReplay, proxy_count_after: traceAfterRelayReplay },
      revoked_consent_version: 2,
    };
  } finally {
    await client.close();
  }
}

async function seed(config, pairing) {
  const { client, sessionId } = await connectAndHello(config, pairing);
  try {
    await sendConsent(client, sessionId, "DAY_PLAN", 1, "GRANTED");
    await sendConsent(client, sessionId, "ACTIVITY_STATUS", 1, "GRANTED");
    await sendPlanSnapshot(client, sessionId, config.plan, 2, 1);
    return { status: "PASS", scenario: "negative_seed", plan_id: config.plan.plan_id, version: 2 };
  } finally {
    await client.close();
  }
}

async function revokedChat(config, pairing) {
  const { client, sessionId } = await connectAndHello(config, pairing);
  try {
    await sendConsent(client, sessionId, "DAY_PLAN", 2, "REVOKED");
    await sendConsent(client, sessionId, "ACTIVITY_STATUS", 2, "REVOKED");
    const before = await traceCount(config.proxy_trace);
    const response = await exchange(client, "v4.coach.chat.request", config.revoked_chat_payload, sessionId, {
      requestId: config.revoked_chat_payload.request_id,
      idempotencyKey: config.revoked_chat_payload.request_id,
      timeoutMs: 20000,
    });
    const remoteCode = getRemoteError(response);
    const after = await traceCount(config.proxy_trace);
    if (response.answer.kind !== "error" || remoteCode !== "CONSENT_REQUIRED" || before !== after) {
      throw new SafeFailure("REVOKED_CONSENT_GATE_FAILED");
    }
    return {
      status: "PASS",
      scenario: "revoked_chat",
      chat_request_id_hash: hash(config.revoked_chat_payload.request_id),
      remote_code: remoteCode,
      proxy_count_before: before,
      proxy_count_after: after,
    };
  } finally {
    await client.close();
  }
}

async function injection(config, pairing) {
  const { client, sessionId } = await connectAndHello(config, pairing);
  try {
    const before = await traceCount(config.proxy_trace);
    const response = await exchange(client, "v4.coach.chat.request", config.injection_chat_payload, sessionId, {
      requestId: config.injection_chat_payload.request_id,
      idempotencyKey: config.injection_chat_payload.request_id,
      timeoutMs: 90000,
    });
    let agentStatus = null;
    let toolCallCount = 0;
    if (response.answer.kind === "response" && isRecord(response.answer.result)) {
      agentStatus = response.answer.result.agent_status ?? null;
      toolCallCount = Number(response.answer.result.tool_call_count ?? 0);
    }
    const after = await traceCount(config.proxy_trace);
    if (after <= before) throw new SafeFailure("INJECTION_DID_NOT_REACH_REAL_MODEL");
    return {
      status: "PASS",
      scenario: "tool_injection",
      chat_request_id_hash: hash(config.injection_chat_payload.request_id),
      agent_status: agentStatus,
      reported_tool_call_count: toolCallCount,
      proxy_count_before: before,
      proxy_count_after: after,
    };
  } finally {
    await client.close();
  }
}

async function main() {
  const configPath = process.argv[2];
  if (!configPath) throw new SafeFailure("CONFIG_PATH_REQUIRED");
  const config = await readJson(configPath, "CONFIG_INVALID");
  const pairing = await readJson(config.pairing_file, "PAIRING_FILE_INVALID");
  const coreTokenRaw = await readFile(config.core_token_file, "utf8").catch(() => "");
  const coreToken = coreTokenRaw.trim();
  if (!pairing.device_id || !pairing.token || !config.server_id || !config.server_public_key || !coreToken ||
      !config.tool_token_file) {
    throw new SafeFailure("PRIVATE_CREDENTIAL_INPUT_INVALID");
  }
  let output;
  if (config.phase === "positive") output = await positive(config, pairing, coreToken);
  else if (config.phase === "seed") output = await seed(config, pairing);
  else if (config.phase === "revoked_chat") output = await revokedChat(config, pairing);
  else if (config.phase === "injection") output = await injection(config, pairing);
  else throw new SafeFailure("SCENARIO_INVALID");
  process.stdout.write(`${JSON.stringify(output)}\n`);
}

main().catch((error) => {
  const code = error instanceof SafeFailure ? error.code : "RELAY_CLIENT_FAILED";
  process.stdout.write(`${JSON.stringify({ status: "BLOCKED", error_code: code })}\n`);
  process.exitCode = 1;
});
