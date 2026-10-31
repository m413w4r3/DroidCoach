import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { BackendClient } from "../src/backend.js";
import { ConnectorDiagnostics, DIAGNOSTIC_BUFFER_LIMIT } from "../src/diagnostics.js";
import { DeviceRegistry } from "../src/device-registry.js";
import { ConnectorMetrics } from "../src/metrics.js";
import { OperationExecutor } from "../src/operations.js";
import { PROTOCOL_V2, type Capability, type Operation } from "../src/protocol.js";
import { SessionManager } from "../src/session.js";
import { FakeSecureChannel, hello, request } from "./helpers.js";

const DEVICE_CAPABILITIES: Capability[] = ["sync.status", "v4.consent.state.write"];
const REQUEST_ID = "00000000-0000-4000-8000-000000000701";
const IDEMPOTENCY_ID = "00000000-0000-4000-8000-000000000702";
const PRIVATE_PAYLOAD = "SYNTHETIC_PRIVATE_PAYLOAD_MARKER";
const CONSENT_PAYLOAD = {
  contract_version: 4,
  schema_version: 4,
  request_id: REQUEST_ID,
  capability: "consent.state.write",
  sent_at: "2026-10-31T10:00:00Z",
  category: "DAY_PLAN",
  consent_version: 1,
  state: "GRANTED",
  idempotency_key: IDEMPOTENCY_ID,
};

interface Harness {
  dir: string;
  deviceId: string;
  token: string;
  diagnostics: ConnectorDiagnostics;
  manager: SessionManager;
  dispose(): Promise<void>;
}

class RejectResponseChannel extends FakeSecureChannel {
  override async send(data: string | ArrayBuffer): Promise<void> {
    if (typeof data === "string" && (JSON.parse(data) as { kind?: string }).kind === "response") {
      throw new Error("synthetic channel failure");
    }
    await super.send(data);
  }
}

async function makeHarness(diagnostics: ConnectorDiagnostics): Promise<Harness> {
  const dir = await mkdtemp(join(tmpdir(), "atlas-relay-diagnostics-test-"));
  const registry = new DeviceRegistry(join(dir, "devices.json"));
  const paired = await registry.pair("synthetic diagnostics device", DEVICE_CAPABILITIES);
  const metrics = new ConnectorMetrics();
  const backend = {
    async execute(_operation: Operation, _payload: Record<string, unknown>): Promise<Record<string, unknown>> {
      diagnostics.observeHttpStatus(200);
      return { result_code: "ACCEPTED", echoed_value: PRIVATE_PAYLOAD };
    },
  } as unknown as BackendClient;
  const operations = new OperationExecutor(
    backend,
    { relayConnected: () => true, backendConfigured: () => true },
    Date.now,
    join(dir, "operations.json"),
    diagnostics,
  );
  const manager = new SessionManager(registry, operations, metrics, {
    relayConnected: () => true,
    backendConfigured: () => true,
  });
  return {
    dir,
    deviceId: paired.device.device_id,
    token: paired.token,
    diagnostics,
    manager,
    dispose: () => rm(dir, { recursive: true, force: true }),
  };
}

function newTrace(enabled: boolean): ConnectorDiagnostics {
  const original = process.env.ATLAS_DIAG_TRACE;
  if (enabled) process.env.ATLAS_DIAG_TRACE = "1";
  else delete process.env.ATLAS_DIAG_TRACE;
  try {
    return new ConnectorDiagnostics();
  } finally {
    if (original === undefined) delete process.env.ATLAS_DIAG_TRACE;
    else process.env.ATLAS_DIAG_TRACE = original;
  }
}

async function runConsent(harness: Harness, channel = new FakeSecureChannel()): Promise<{ channel: FakeSecureChannel; requestId: string }> {
  const peer = harness.manager.create(channel);
  await peer.receive(JSON.stringify(hello(harness.deviceId, harness.token, {
    protocol: PROTOCOL_V2,
    capabilities: DEVICE_CAPABILITIES,
  })));
  const sessionId = String(channel.lastMessage().session_id);
  const envelope = request(sessionId, "v4.consent.state.upsert", CONSENT_PAYLOAD, {
    protocol: PROTOCOL_V2,
    request_id: REQUEST_ID,
    idempotency_key: IDEMPOTENCY_ID,
  });
  await peer.receive(JSON.stringify(envelope));
  return { channel, requestId: envelope.request_id };
}

describe("bounded connector diagnostics", () => {
  it("traces a synthetic V4 consent operation through an accepted WebSocket send", async () => {
    const diagnostics = newTrace(true);
    const harness = await makeHarness(diagnostics);
    try {
      const { channel } = await runConsent(harness);
      const events = diagnostics.snapshot();
      const operationEvents = events.filter((event) => event.operation === "v4.consent.state.upsert");

      expect(operationEvents.map((event) => event.stage)).toEqual([
        "operation_received",
        "journal_persist_start",
        "journal_persist_end",
        "core_post_start",
        "core_post_end",
        "journal_persist_start",
        "journal_persist_end",
        "response_ready",
        "ws_send_attempt",
        "ws_send_result",
      ]);
      expect(operationEvents[0]).toMatchObject({
        sessionOrdinal: expect.any(Number),
        correlationTag: createHash("sha256").update(REQUEST_ID).digest("hex").slice(0, 8),
      });
      expect(operationEvents.find((event) => event.stage === "core_post_end")).toMatchObject({
        result: "success",
        httpStatus: 200,
        resultCode: "ACCEPTED",
        durationMs: expect.any(Number),
      });
      expect(operationEvents.filter((event) => event.stage === "journal_persist_end").map((event) => [event.result, event.resultCode]))
        .toEqual([["in_flight", "PERSISTED"], ["completed", "PERSISTED"]]);
      expect(operationEvents.find((event) => event.stage === "response_ready")?.durationMs).toEqual(expect.any(Number));
      expect(operationEvents.at(-1)).toMatchObject({ stage: "ws_send_result", result: "accepted" });
      expect(channel.lastMessage()).toMatchObject({ kind: "response", operation: "v4.consent.state.upsert", status: "OK" });
      expect(Object.isFrozen(events)).toBe(true);
      expect(Object.isFrozen(events[0])).toBe(true);
      expect(() => (events as unknown as unknown[]).push({})).toThrow();
      assertTraceContainsNoPrivateData(events, harness.token);
    } finally {
      await harness.dispose();
    }
  });

  it("records a rejected response send while retaining completed_redacted in the journal", async () => {
    const diagnostics = newTrace(true);
    const harness = await makeHarness(diagnostics);
    try {
      const { channel } = await runConsent(harness, new RejectResponseChannel());
      const events = diagnostics.snapshot().filter((event) => event.operation === "v4.consent.state.upsert");
      const journal = JSON.parse(await readFile(join(harness.dir, "operations.json"), "utf8")) as {
        entries: Array<{ operation: string; outcome: string }>;
      };

      expect(events.some((event) => event.stage === "ws_send_result" && event.result === "rejected" && event.resultCode === "SEND_FAILED")).toBe(true);
      expect(events.at(-1)).toMatchObject({ stage: "ws_send_result", result: "rejected", resultCode: "SEND_FAILED" });
      expect(journal.entries).toEqual([expect.objectContaining({ operation: "v4.consent.state.upsert", outcome: "completed_redacted" })]);
      expect(channel.sent.some((message) => (message as { kind?: string }).kind === "error")).toBe(true);
      assertTraceContainsNoPrivateData(diagnostics.snapshot(), harness.token);
    } finally {
      await harness.dispose();
    }
  });

  it("is disabled unless ATLAS_DIAG_TRACE is exactly 1", () => {
    const original = process.env.ATLAS_DIAG_TRACE;
    delete process.env.ATLAS_DIAG_TRACE;
    try {
      const diagnostics = new ConnectorDiagnostics();
      const context = diagnostics.sessionContext(1);
      diagnostics.record(context, "session_active", "active");
      expect(diagnostics.enabled).toBe(false);
      expect(diagnostics.snapshot()).toEqual([]);
    } finally {
      if (original !== undefined) process.env.ATLAS_DIAG_TRACE = original;
    }
  });

  it("keeps the trace buffer bounded and returns a detached snapshot", () => {
    const diagnostics = newTrace(true);
    const context = diagnostics.sessionContext(1);
    for (let index = 0; index < DIAGNOSTIC_BUFFER_LIMIT + 12; index += 1) {
      diagnostics.record(context, "session_active", "active");
    }
    const snapshot = diagnostics.snapshot();
    diagnostics.record(context, "session_changed", "changed");
    expect(snapshot).toHaveLength(DIAGNOSTIC_BUFFER_LIMIT);
    expect(diagnostics.snapshot()).toHaveLength(DIAGNOSTIC_BUFFER_LIMIT);
    expect(snapshot).not.toEqual(diagnostics.snapshot());
  });
});

function assertTraceContainsNoPrivateData(events: readonly unknown[], token: string): void {
  const serialized = JSON.stringify(events);
  expect(serialized).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i);
  expect(serialized.toLowerCase()).not.toContain("bearer");
  expect(serialized).not.toContain(token);
  expect(serialized).not.toContain(PRIVATE_PAYLOAD);
  expect(serialized.toLowerCase()).not.toContain("payload");
}
