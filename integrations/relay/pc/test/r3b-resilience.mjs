import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import { chmod, lstat, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import net from "node:net";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import WebSocket from "ws";
import { openClientSecureChannel } from "@getpaseo/relay/e2ee";
import { test } from "node:test";

const HERE = dirname(fileURLToPath(import.meta.url));
const PACKAGE_DIR = resolve(HERE, "..");
const MAIN_FILE = join(PACKAGE_DIR, "dist", "main.js");
const SYSTEMD_UNIT = join(PACKAGE_DIR, "systemd", "atlas-relay.service");
const RELAY_URL = process.env.ATLAS_TEST_RELAY_URL ?? "ws://127.0.0.1:4402";
const CAPABILITIES = ["coach.status", "plan.revision", "sync.status"];
const TEST_TIMEOUT_MS = 60_000;

test("external backend command accepts a JSON argv array without a shell", () => {
  const command = ["/opt/atlas-test/.venv/bin/python", "-m", "uvicorn", "backend.app:app", "--host", "127.0.0.1", "--port", "8100"];
  assert.deepEqual(parseBackendCommand(JSON.stringify(command), "/tmp/atlas-r3b-backend.env"), {
    command,
    envFile: "/tmp/atlas-r3b-backend.env",
  });
  assert.throws(() => parseBackendCommand("uvicorn backend.app:app --host 127.0.0.1", null), /JSON argv array/);
});

test("external backend command starts a synthetic loopback process", async () => {
  const port = await findFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const program = "import{createServer}from'node:http';createServer((req,res)=>{res.writeHead(200,{'content-type':'application/json'});res.end('{\"status\":\"ok\"}')}).listen(Number(process.env.PORT),'127.0.0.1');";
  const parsed = parseBackendCommand(JSON.stringify([process.execPath, "--input-type=module", "-e", program]), null);
  const backend = await startExternalBackend(parsed.command, isolatedEnvironment({ PORT: String(port) }), baseUrl);
  try {
    const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(1000) });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).status, "ok");
    summary.external_backend_command_runner_smoke = "PASS";
  } finally {
    await backend.stop();
  }
});

const summary = {
  schema_version: 1,
  base_sha: "5795d378d38b3e86ce99f1551c41d5044cd4a81c",
  source_sha: readGitRevision(),
  source_tree_dirty: isGitTreeDirty(),
  scenarios: [],
};
const capturedOutput = [];
const secretMarkers = new Set();
const payloadMarkers = new Set();

test("R3B connector lifecycle, backend resilience, replay and rollback", { timeout: TEST_TIMEOUT_MS }, async (t) => {
  const lingerBefore = await readLingerState();
  const relayHealth = await probeRelay(RELAY_URL);
  summary.relay = { url: RELAY_URL, health_status: relayHealth.status, available: relayHealth.available };

  if (!relayHealth.available) {
    for (const name of REQUIRED_DYNAMIC_SCENARIOS) addScenario(name, "NOT_RUN", 0, { reason: "pinned loopback relay unavailable" });
    await verifySystemd(t);
    await inspectLinger(t, lingerBefore);
    emitSummary();
    t.skip("real relay unavailable; dynamic scenarios recorded as NOT_RUN");
    return;
  }

  const root = await mkdtemp(join(tmpdir(), "atlas-r3b-pc-"));
  await chmod(root, 0o700);
  const stateDir = join(root, "state");
  await mkdir(stateDir, { mode: 0o700 });

  const serviceToken = getServiceToken();
  const payloadMarker = `r3b-payload-${randomUUID()}`;
  secretMarkers.add(serviceToken);
  secretMarkers.add(payloadMarker);

  let backend;
  let externalBackend;
  let connector;
  let client;
  let connectorEnv;
  let metricsPort;
  let identityFingerprint;
  let daemonPublicKeyB64;
  let initialRegistryHash;
  let deviceToken;
  let deviceId;
  let logScanRecorded = false;

  try {
    const relay = validateLocalRelayUrl(RELAY_URL);
    const backendSpec = readExternalBackendSpec();
    const serviceId = `atlas-r3b-${randomUUID()}`;
    metricsPort = await findFreePort();

    connectorEnv = isolatedEnvironment({
      ATLAS_RELAY_STATE_DIR: stateDir,
      ATLAS_RELAY_URL: RELAY_URL,
      ATLAS_ALLOW_LOOPBACK_WS: "1",
      ATLAS_RELAY_SERVER_ID: serviceId,
      ATLAS_METRICS_PORT: String(metricsPort),
      ATLAS_HANDSHAKE_TIMEOUT_MS: "5000",
      ATLAS_SERVICE_TOKEN: serviceToken,
    });

    if (backendSpec) {
      const backendEnv = await loadBackendEnvironment(backendSpec.envFile);
      const backendToken = process.env.ATLAS_R3B_SERVICE_TOKEN;
      assert.ok(backendToken, "ATLAS_R3B_SERVICE_TOKEN is required with an external backend command");
      secretMarkers.add(backendToken);
      const url = validateLoopbackBackendUrl(process.env.ATLAS_R3B_BACKEND_URL ?? "http://127.0.0.1:8100");
      connectorEnv.ATLAS_BACKEND_URL = url;
      connectorEnv.ATLAS_SERVICE_TOKEN = backendToken;
      externalBackend = await startExternalBackend(backendSpec.command, isolatedEnvironment({
        ...backendEnv,
        ATLAS_SERVICE_TOKEN: backendToken,
      }), url);
      summary.backend_mode = "external-command";
      summary.external_backend_command_provided = true;
    } else {
      backend = await ControlledBackend.create(serviceToken);
      connectorEnv.ATLAS_BACKEND_URL = backend.url;
      summary.backend_mode = "controlled-loopback";
      summary.external_backend_command_provided = false;
      summary.backend_command_option = "ATLAS_R3B_BACKEND_COMMAND_JSON (JSON argv array), with optional ATLAS_R3B_BACKEND_URL, ATLAS_R3B_BACKEND_ENV_FILE and synthetic ATLAS_R3B_SERVICE_TOKEN";
    }

    const initialIdentity = await runConnectorCommand("show-identity", connectorEnv);
    ({ fingerprint: identityFingerprint, publicKey: daemonPublicKeyB64 } = parseIdentityOutput(initialIdentity.stdout));
    const tokenFile = join(root, "device-token.json");
    const paired = await runConnectorCommand("pair-device", connectorEnv, [
      "--label", "synthetic-r3b-device",
      "--capabilities", CAPABILITIES.join(","),
      "--token-file", tokenFile,
    ]);
    deviceId = parsePairedDeviceId(paired.stdout);
    const tokenStat = await lstat(tokenFile);
    assert.equal(tokenStat.mode & 0o777, 0o600);
    const tokenDocument = JSON.parse(await readFile(tokenFile, "utf8"));
    assert.equal(tokenDocument.device_id, deviceId);
    deviceToken = tokenDocument.token;
    secretMarkers.add(deviceToken);

    initialRegistryHash = await hashFile(join(stateDir, "devices.json"));
    await scenario("connector_startup_clean", async () => {
      const state = await inspectPrivateState(stateDir, tokenFile);
      connector = await startConnector(connectorEnv, metricsPort);
      return {
        process_started: true,
        pid_present: Number.isInteger(connector.pid),
        metrics_http_status: 200,
        identity_fingerprint: identityFingerprint,
        registry_sha256: initialRegistryHash,
        state_modes: state,
      };
    });

    client = await RelayTestClient.connect(relay, serviceId, daemonPublicKeyB64, deviceId, deviceToken);
    await scenario("relay_initial_connection", async () => {
      const result = await client.request("sync.status", {}, `initial-${randomUUID()}`);
      assert.equal(result.kind, "response");
      assert.equal(result.result?.relay_connected, true);
      return { response: "OK", protected_channel: true };
    });
    await client.close();
    client = null;

    await scenario("sigterm_exit_code_and_port_release", async () => {
      const child = connector;
      const exit = await stopConnector(child, "SIGTERM");
      connector = null;
      const released = await isPortFree(metricsPort);
      assert.equal(exit.code, 0);
      assert.equal(exit.signal, null);
      assert.equal(released, true);
      return { exit_code: exit.code, signal: exit.signal, metrics_port_released: released };
    });

    connector = await startConnector(connectorEnv, metricsPort);
    client = await RelayTestClient.connect(relay, serviceId, daemonPublicKeyB64, deviceId, deviceToken);
    await scenario("sigkill_restart_identity_registry_and_relay_reconnect", async () => {
      const beforeIdentity = await runShowIdentityFingerprint(connectorEnv);
      const beforeRegistry = await hashFile(join(stateDir, "devices.json"));
      const killed = await stopConnector(connector, "SIGKILL");
      connector = null;
      const released = await isPortFree(metricsPort);
      assert.equal(killed.code, null);
      assert.equal(killed.signal, "SIGKILL");
      assert.equal(released, true);

      connector = await startConnector(connectorEnv, metricsPort);
      const afterIdentity = await runShowIdentityFingerprint(connectorEnv);
      const afterRegistry = await hashFile(join(stateDir, "devices.json"));
      assert.equal(afterIdentity, beforeIdentity);
      assert.equal(afterRegistry, beforeRegistry);
      assert.equal(await registryDeviceCount(stateDir), 1);

      await client.close();
      client = await RelayTestClient.connect(relay, serviceId, daemonPublicKeyB64, deviceId, deviceToken);
      const reconnect = await client.request("sync.status", {}, `reconnect-${randomUUID()}`);
      assert.equal(reconnect.kind, "response");
      assert.equal(reconnect.result?.relay_connected, true);
      return {
        sigkill_exit_code: killed.code,
        sigkill_signal: killed.signal,
        metrics_port_released: released,
        identity_fingerprint: afterIdentity,
        identity_preserved: afterIdentity === beforeIdentity,
        registry_sha256: afterRegistry,
        registry_preserved: afterRegistry === beforeRegistry,
        registered_device_count: 1,
        relay_reconnected: true,
      };
    });

    if (backend) {
      await scenario("backend_unavailable_explicit_error", async () => {
        const result = await client.request("coach.status", {}, `backend-down-${randomUUID()}`);
        assert.equal(result.kind, "error");
        assert.equal(result.error?.code, "BACKEND_UNAVAILABLE");
        assert.equal(result.error?.retryable, true);
        return { error_code: result.error.code, retryable: result.error.retryable, no_response_invented: true };
      });

      await scenario("backend_restored_success", async () => {
        await backend.start();
        backend.mode = "success";
        const result = await client.request("coach.status", {}, `backend-up-${randomUUID()}`);
        assert.equal(result.kind, "response");
        assert.equal(result.result?.connected, true);
        assert.equal(backend.statusCalls, 1);
        return { response: "OK", backend_calls: backend.statusCalls };
      });

      await scenario("backend_http_500_explicit_error", async () => {
        backend.mode = "http-500";
        const result = await client.request("coach.status", {}, `backend-500-${randomUUID()}`);
        assert.equal(result.kind, "error");
        assert.equal(result.error?.code, "BACKEND_REJECTED");
        return { error_code: result.error.code, backend_http_status: 500 };
      });
      backend.mode = "success";
    } else {
      addScenario("backend_unavailable_explicit_error", "NOT_RUN", 0, { reason: "external backend command mode does not expose fault controls" });
      addScenario("backend_restored_success", "NOT_RUN", 0, { reason: "external backend command mode does not expose fault controls" });
      addScenario("backend_http_500_explicit_error", "NOT_RUN", 0, { reason: "external backend command mode does not expose fault controls" });
    }

    let completedPost;
    await scenario("completed_post_replay_without_second_backend_call", async () => {
      const plan = makePlanRevisionPayload(payloadMarker);
      trackPayload(plan);
      const first = await client.sendRequest("plan.revision.request", plan, `completed-${randomUUID()}`);
      assert.equal(first.response.kind, "response");
      assert.equal(first.response.replayed, false);
      if (backend) assert.equal(backend.planRevisionCalls, 1);
      const replay = await client.sendEnvelope(first.request);
      assert.equal(replay.kind, "response");
      assert.equal(replay.replayed, true);
      if (backend) assert.equal(backend.planRevisionCalls, 1);
      completedPost = { plan, idempotencyKey: first.request.idempotency_key };
      return {
        first_response: "OK",
        replayed: replay.replayed,
        backend_post_calls: backend?.planRevisionCalls ?? null,
        same_request_envelope: true,
      };
    });

    await scenario("completed_post_replay_after_restart_without_second_call", async () => {
      await client.close();
      client = null;
      const exit = await stopConnector(connector, "SIGTERM");
      connector = null;
      assert.equal(exit.code, 0);
      connector = await startConnector(connectorEnv, metricsPort);
      client = await RelayTestClient.connect(relay, serviceId, daemonPublicKeyB64, deviceId, deviceToken);
      const replay = await client.sendRequest("plan.revision.request", completedPost.plan, completedPost.idempotencyKey);
      assert.equal(replay.response.kind, "response");
      assert.equal(replay.response.replayed, true);
      if (backend) assert.equal(backend.planRevisionCalls, 1);
      return {
        replayed: replay.response.replayed,
        backend_post_calls: backend?.planRevisionCalls ?? null,
        durable_journal_replay: true,
        graceful_restart_exit_code: exit.code,
      };
    });

    if (backend) {
      await scenario("uncertain_post_is_not_retried_and_is_durable", async () => {
        backend.mode = "drop-after-accept";
        const plan = makePlanRevisionPayload(payloadMarker);
        trackPayload(plan);
        const callsBeforeUncertain = backend.planRevisionCalls;
        const first = await client.sendRequest("plan.revision.request", plan, `uncertain-${randomUUID()}`);
        assert.equal(first.response.kind, "error");
        assert.equal(first.response.error?.code, "BACKEND_UNAVAILABLE");
        assert.equal(first.response.error?.retryable, false);
        assert.match(first.response.error?.message ?? "", /outcome is uncertain/);
        const callsAfterAccept = backend.planRevisionCalls;
        assert.equal(callsAfterAccept - callsBeforeUncertain, 1);

        const replay = await client.sendEnvelope(first.request);
        assert.equal(replay.kind, "error");
        assert.equal(replay.error?.code, "BACKEND_UNAVAILABLE");
        assert.equal(replay.error?.retryable, false);
        await delay(1200);
        assert.equal(backend.planRevisionCalls, callsAfterAccept);

        await client.close();
        client = null;
        const exit = await stopConnector(connector, "SIGTERM");
        connector = null;
        assert.equal(exit.code, 0);
        connector = await startConnector(connectorEnv, metricsPort);
        client = await RelayTestClient.connect(relay, serviceId, daemonPublicKeyB64, deviceId, deviceToken);
        const afterRestart = await client.request("plan.revision.request", plan, first.request.idempotency_key);
        assert.equal(afterRestart.kind, "error");
        assert.equal(afterRestart.error?.code, "BACKEND_UNAVAILABLE");
        assert.equal(afterRestart.error?.retryable, false);
        assert.equal(backend.planRevisionCalls, callsAfterAccept);
        return {
          first_error: first.response.error.code,
          retryable: first.response.error.retryable,
          uncertain_request_backend_calls: callsAfterAccept - callsBeforeUncertain,
          total_post_calls: callsAfterAccept,
          total_calls_after_immediate_replay: backend.planRevisionCalls,
          total_calls_after_restart_replay: backend.planRevisionCalls,
          no_automatic_or_manual_duplicate: true,
          graceful_restart_exit_code: exit.code,
        };
      });
    } else {
      addScenario("uncertain_post_is_not_retried_and_is_durable", "NOT_RUN", 0, { reason: "external backend command mode does not expose accept-then-drop control" });
    }

    await client?.close();
    client = null;
    if (connector) {
      const exit = await stopConnector(connector, "SIGTERM");
      connector = null;
      assert.equal(exit.code, 0);
    }

    await scenario("confirmed_rollback_and_state_integrity", async () => {
      const backupDir = join(root, "state-backup");
      const preRestoreBackup = join(root, "state-before-restore");
      const envFile = join(root, "connector-test.env");
      await writeFile(envFile, `ATLAS_RELAY_STATE_DIR=${stateDir}\n`, { mode: 0o600, flag: "wx" });
      await chmod(envFile, 0o600);

      const beforeIdentity = await runShowIdentityFingerprint(connectorEnv);
      const beforeRegistry = await hashFile(join(stateDir, "devices.json"));
      const operationsPath = join(stateDir, "operations.json");
      const operationsHash = await optionalHashFile(operationsPath);
      await runNodeScript(join(PACKAGE_DIR, "scripts", "backup-state.mjs"), [envFile, backupDir], connectorEnv);
      const backupManifest = JSON.parse(await readFile(join(backupDir, "manifest.json"), "utf8"));
      assert.equal(backupManifest.files["identity.json"], await hashFile(join(backupDir, "identity.json")));
      assert.equal(backupManifest.files["devices.json"], beforeRegistry);

      const extraTokenFile = join(root, "rollback-device-token.json");
      const extraPair = await runConnectorCommand("pair-device", connectorEnv, [
        "--label", "synthetic-rollback-device",
        "--capabilities", "sync.status",
        "--token-file", extraTokenFile,
      ]);
      secretMarkers.add(JSON.parse(await readFile(extraTokenFile, "utf8")).token);
      assert.ok(parsePairedDeviceId(extraPair.stdout));
      assert.equal(await registryDeviceCount(stateDir), 2);

      await runNodeScript(join(PACKAGE_DIR, "scripts", "restore-state.mjs"), [
        envFile,
        backupDir,
        "--confirm-fingerprint", beforeIdentity,
        "--pre-restore-backup", preRestoreBackup,
        "--service-stopped",
      ], connectorEnv);
      const afterIdentity = await runShowIdentityFingerprint(connectorEnv);
      const afterRegistry = await hashFile(join(stateDir, "devices.json"));
      const afterOperations = await optionalHashFile(operationsPath);
      assert.equal(afterIdentity, beforeIdentity);
      assert.equal(afterRegistry, beforeRegistry);
      assert.equal(await registryDeviceCount(stateDir), 1);
      assert.equal(afterOperations, operationsHash);
      assert.equal(await stat(preRestoreBackup).then((item) => item.mode & 0o777), 0o700);
      return {
        confirmed_identity_fingerprint: afterIdentity,
        identity_restored: afterIdentity === beforeIdentity,
        registry_sha256: afterRegistry,
        registry_restored: afterRegistry === beforeRegistry,
        registered_device_count: 1,
        operations_journal_preserved: afterOperations === operationsHash,
        pre_restore_backup_retained: true,
        backup_manifest_verified: true,
      };
    });

    await scenario("token_payload_absent_from_captured_logs", async () => {
      const text = capturedOutput.join("");
      const leaked = [...secretMarkers].some((marker) => marker && text.includes(marker));
      assert.equal(leaked, false);
      logScanRecorded = true;
      return {
        stdout_stderr_captured: true,
        token_absent: !deviceToken || !text.includes(deviceToken),
        service_token_absent: !text.includes(serviceToken),
        payload_absent: [...payloadMarkers].every((marker) => !text.includes(marker)),
        payload_markers_scanned: payloadMarkers.size,
        captured_characters: text.length,
        connector_journal_available: false,
      };
    });

    await verifySystemd(t);
    await inspectLinger(t, lingerBefore);
    emitSummary();
  } catch (error) {
    if (!logScanRecorded) {
      const text = capturedOutput.join("");
      const leaked = [...secretMarkers].some((marker) => marker && text.includes(marker));
      addScenario("token_payload_absent_from_captured_logs", leaked ? "FAIL" : "PASS", 0, {
        stdout_stderr_captured: true,
        token_payload_scan: leaked ? "match found" : "no match",
      });
    }
    emitSummary();
    throw error;
  } finally {
    await client?.close().catch(() => {});
    if (connector && connector.exitCode === null && connector.signalCode === null) {
      await stopConnector(connector, "SIGTERM").catch(() => {});
    }
    await backend?.stop().catch(() => {});
    if (externalBackend) await externalBackend.stop().catch(() => {});
    await rm(root, { recursive: true, force: true });
  }
});

const REQUIRED_DYNAMIC_SCENARIOS = [
  "connector_startup_clean",
  "sigterm_exit_code_and_port_release",
  "sigkill_restart_identity_registry_and_relay_reconnect",
  "backend_unavailable_explicit_error",
  "backend_restored_success",
  "backend_http_500_explicit_error",
  "completed_post_replay_without_second_backend_call",
  "completed_post_replay_after_restart_without_second_call",
  "uncertain_post_is_not_retried_and_is_durable",
  "confirmed_rollback_and_state_integrity",
  "token_payload_absent_from_captured_logs",
];

class ControlledBackend {
  constructor(port, token) {
    this.port = port;
    this.token = token;
    this.server = null;
    this.mode = "success";
    this.statusCalls = 0;
    this.planRevisionCalls = 0;
  }

  static async create(token) {
    return new ControlledBackend(await findFreePort(), token);
  }

  get url() { return `http://127.0.0.1:${this.port}`; }

  async start() {
    assert.equal(this.server, null);
    const server = createServer(async (request, response) => {
      const path = new URL(request.url ?? "/", this.url).pathname;
      if (request.method === "GET" && path === "/health") {
        return sendJson(response, 200, { status: "ok" });
      }
      if (request.method === "GET" && path === "/v2/agent/status") {
        this.statusCalls += 1;
        if (this.mode === "http-500") return sendJson(response, 500, { detail: "synthetic backend failure" });
        if (request.headers.authorization !== `Bearer ${this.token}`) return sendJson(response, 401, { detail: "unauthorized" });
        return sendJson(response, 200, { connected: true, adapter: "mock" });
      }
      if (request.method === "POST" && path === "/v3/agent/plan-revision") {
        const chunks = [];
        for await (const chunk of request) chunks.push(Buffer.from(chunk));
        const body = Buffer.concat(chunks).toString("utf8");
        if (request.headers.authorization !== `Bearer ${this.token}`) return sendJson(response, 401, { detail: "unauthorized" });
        this.planRevisionCalls += 1;
        if (this.mode === "drop-after-accept") {
          response.writeHead(200, { "content-type": "application/json" });
          response.write('{"accepted":true');
          setImmediate(() => response.destroy());
          return;
        }
        if (this.mode === "http-500") return sendJson(response, 500, { detail: "synthetic backend failure" });
        if (!body.includes("r3b-payload-")) return sendJson(response, 400, { detail: "synthetic payload missing" });
        return sendJson(response, 200, { proposal_id: "synthetic-r3b-proposal" });
      }
      return sendJson(response, 404, { detail: "not found" });
    });
    this.server = server;
    await new Promise((resolveListen, reject) => {
      server.once("error", reject);
      server.listen(this.port, "127.0.0.1", resolveListen);
    });
    await waitUntil(async () => (await fetch(`${this.url}/health`, { signal: AbortSignal.timeout(500) }).then((r) => r.ok).catch(() => false)), 5000);
  }

  async stop() {
    if (!this.server) return;
    const server = this.server;
    this.server = null;
    await new Promise((resolveClose) => server.close(() => resolveClose()));
  }
}

class RelayTestClient {
  constructor(socket) {
    this.socket = socket;
    this.inbox = [];
    this.waiters = [];
    this.channel = null;
    this.sessionId = null;
  }

  static async connect(relay, serverId, daemonPublicKeyB64, deviceId, deviceToken) {
    const url = new URL("/ws", relay.href);
    url.searchParams.set("serverId", serverId);
    url.searchParams.set("role", "client");
    url.searchParams.set("v", "2");
    const socket = new WebSocket(url);
    const client = new RelayTestClient(socket);
    socket.on("error", () => {});
    await new Promise((resolveOpen, reject) => {
      const timer = setTimeout(() => reject(new Error("relay client socket open timeout")), 8000);
      socket.once("open", () => { clearTimeout(timer); resolveOpen(); });
      socket.once("error", () => { clearTimeout(timer); reject(new Error("relay client socket failed")); });
    });

    const transport = createTransport(socket, client);
    const opened = new Promise((resolveOpen, reject) => {
      const timer = setTimeout(() => reject(new Error("protected channel open timeout")), 8000);
      client.onOpen = () => { clearTimeout(timer); resolveOpen(); };
      client.onChannelError = () => { clearTimeout(timer); reject(new Error("protected channel handshake failed")); };
    });
    client.channel = await openClientSecureChannel(transport, {
      daemonPublicKeyB64,
      minimumMode: "protected",
      events: {
        onopen: () => client.onOpen?.(),
        onmessage: (data) => client.receiveApplication(data),
        onerror: () => client.onChannelError?.(),
      },
    });
    await opened;
    assert.equal(client.channel.securityMode(), "protected");

    const hello = {
      protocol: "atlas.remote.v1",
      kind: "hello",
      device_id: deviceId,
      client_nonce: randomBytes(16).toString("base64"),
      client_version: "0.1.0-r3b-resilience",
      capabilities: CAPABILITIES,
      auth: { scheme: "device-token-v1", token: deviceToken },
      timestamp: new Date().toISOString(),
    };
    if (typeof hello.device_id !== "string" || typeof hello.auth.token !== "string") throw new Error("synthetic device credentials unavailable");
    await client.send(hello);
    const helloOk = await client.waitFor((message) => message.kind === "hello_ok", 8000);
    client.sessionId = helloOk.session_id;
    if (typeof client.sessionId !== "string") throw new Error("relay session id missing");
    return client;
  }

  onOpen = null;
  onChannelError = null;

  receiveApplication(data) {
    try {
      const text = typeof data === "string" ? data : new TextDecoder("utf-8", { fatal: true }).decode(data);
      const value = JSON.parse(text);
      if (!value || typeof value !== "object" || Array.isArray(value)) return;
      this.inbox.push(value);
      this.flushWaiters();
    } catch {
      // The test client intentionally keeps no raw frame text on parse failure.
    }
  }

  send(value) { return this.channel.send(JSON.stringify(value)); }

  async sendRequest(operation, payload, idempotencyKey, requestId = randomUUID()) {
    const now = Date.now();
    const request = {
      protocol: "atlas.remote.v1",
      kind: "request",
      request_id: requestId,
      session_id: this.sessionId,
      operation,
      idempotency_key: idempotencyKey,
      created_at: new Date(now).toISOString(),
      expires_at: new Date(now + 30_000).toISOString(),
      payload,
    };
    const response = await this.sendEnvelope(request);
    return { request, response };
  }

  async sendEnvelope(request) {
    await this.send(request);
    return this.waitFor((message) =>
      (message.kind === "response" || message.kind === "error") && message.request_id === request.request_id,
    10_000);
  }

  async request(operation, payload, idempotencyKey) {
    return (await this.sendRequest(operation, payload, idempotencyKey)).response;
  }

  async waitFor(predicate, timeoutMs) {
    const index = this.inbox.findIndex(predicate);
    if (index >= 0) return this.inbox.splice(index, 1)[0];
    return new Promise((resolveWait, reject) => {
      let timer;
      const waiter = {
        predicate,
        resolve: (value) => { clearTimeout(timer); resolveWait(value); },
        reject: (error) => { clearTimeout(timer); reject(error); },
      };
      timer = setTimeout(() => {
        this.waiters = this.waiters.filter((item) => item !== waiter);
        reject(new Error("relay application response timeout"));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  flushWaiters() {
    for (const waiter of [...this.waiters]) {
      const index = this.inbox.findIndex(waiter.predicate);
      if (index < 0) continue;
      this.waiters = this.waiters.filter((item) => item !== waiter);
      waiter.resolve(this.inbox.splice(index, 1)[0]);
    }
  }

  async close() {
    this.channel?.close(1000, "r3b test complete");
    if (this.socket.readyState === WebSocket.CLOSED) return;
    await new Promise((resolveClose) => {
      const timer = setTimeout(resolveClose, 1000);
      this.socket.once("close", () => { clearTimeout(timer); resolveClose(); });
    });
  }
}

function createTransport(socket, client) {
  const transport = {
    onmessage: null,
    onclose: null,
    onerror: null,
    send(data) {
      if (socket.readyState !== WebSocket.OPEN) throw new Error("relay test socket closed");
      if (typeof data === "string") socket.send(data, { binary: false });
      else socket.send(Buffer.from(data), { binary: true });
    },
    close(code = 1000, reason = "closed") {
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
  socket.on("close", () => transport.onclose?.());
  socket.on("error", () => transport.onerror?.());
  return transport;
}

async function startConnector(environment, port) {
  const child = spawn(process.execPath, [MAIN_FILE, "start"], {
    cwd: PACKAGE_DIR,
    env: { ...environment, ATLAS_METRICS_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (chunk) => capturedOutput.push(chunk.toString("utf8")));
  child.stderr.on("data", (chunk) => capturedOutput.push(chunk.toString("utf8")));
  try {
    await waitUntil(async () => {
      if (child.exitCode !== null || child.signalCode !== null) throw new Error("connector exited before metrics became ready");
      return await fetch(`http://127.0.0.1:${port}/metrics`, { signal: AbortSignal.timeout(400) })
        .then((response) => response.status === 200)
        .catch(() => false);
    }, 10_000);
    return child;
  } catch {
    child.kill("SIGKILL");
    await waitForExit(child).catch(() => {});
    throw new Error("connector startup failed before readiness");
  }
}

async function stopConnector(child, signal) {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return { code: child?.exitCode ?? null, signal: child?.signalCode ?? null };
  }
  const exited = waitForExit(child);
  child.kill(signal);
  return exited;
}

function waitForExit(child, timeoutMs = 10_000) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve({ code: child.exitCode, signal: child.signalCode });
  }
  return new Promise((resolveExit, reject) => {
    const timer = setTimeout(() => reject(new Error("connector process exit timeout")), timeoutMs);
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      resolveExit({ code, signal });
    });
  });
}

async function runConnectorCommand(command, environment, args = []) {
  const result = await runChild(process.execPath, [MAIN_FILE, command, ...args], environment);
  capturedOutput.push(result.stdout, result.stderr);
  assert.equal(result.code, 0);
  return result;
}

async function runNodeScript(script, args, environment) {
  const result = await runChild(process.execPath, [script, ...args], environment);
  capturedOutput.push(result.stdout, result.stderr);
  assert.equal(result.code, 0);
  return result;
}

function runChild(command, args, environment, timeoutMs = 10_000) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, { cwd: PACKAGE_DIR, env: environment, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let outputRecorded = false;
    const recordOutput = () => {
      if (outputRecorded) return;
      outputRecorded = true;
      capturedOutput.push(stdout, stderr);
    };
    const timer = setTimeout(() => {
      recordOutput();
      child.kill("SIGKILL");
      rejectRun(new Error("child command timeout"));
    }, timeoutMs);
    child.stdout.on("data", (chunk) => { stdout += chunk.toString("utf8"); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString("utf8"); });
    child.once("error", () => {
      clearTimeout(timer);
      recordOutput();
      rejectRun(new Error("child command could not start"));
    });
    child.once("close", (code, signal) => {
      clearTimeout(timer);
      recordOutput();
      resolveRun({ code, signal, stdout, stderr });
    });
  });
}

async function runShowIdentityFingerprint(environment) {
  const result = await runConnectorCommand("show-identity", environment);
  return parseIdentityOutput(result.stdout).fingerprint;
}

async function inspectPrivateState(stateDir, tokenFile) {
  const state = await lstat(stateDir);
  const identity = await lstat(join(stateDir, "identity.json"));
  const registry = await lstat(join(stateDir, "devices.json"));
  const token = await lstat(tokenFile);
  const modes = {
    state_dir: state.mode & 0o777,
    identity_file: identity.mode & 0o777,
    registry_file: registry.mode & 0o777,
    device_token_file: token.mode & 0o777,
  };
  assert.deepEqual(Object.values(modes), [0o700, 0o600, 0o600, 0o600]);
  return modes;
}

async function registryDeviceCount(stateDir) {
  const document = JSON.parse(await readFile(join(stateDir, "devices.json"), "utf8"));
  return Array.isArray(document.devices) ? document.devices.length : -1;
}

async function hashFile(path) {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

async function optionalHashFile(path) {
  try { return await hashFile(path); } catch (error) { if (error?.code === "ENOENT") return null; throw error; }
}

function parseIdentityOutput(stdout) {
  const identity = JSON.parse(stdout);
  assert.match(identity.fingerprint, /^[0-9a-f]{64}$/);
  assert.match(identity.public_key, /^[A-Za-z0-9+/]{43}=$/);
  return { fingerprint: identity.fingerprint, publicKey: identity.public_key };
}

function parsePairedDeviceId(stdout) {
  const receipt = JSON.parse(stdout);
  assert.match(receipt.device_id, /^[0-9a-f-]{36}$/);
  return receipt.device_id;
}

function makePlanRevisionPayload(marker) {
  return {
    schema_version: 3,
    request_id: randomUUID(),
    conversation_id: null,
    message: `Synthetic plan request ${marker}`,
    plan_context_consented: false,
    mandate_summary: { id: "synthetic-r3b-mandate", version: 1, mode: "proposals_only" },
    plan: {
      plan_id: randomUUID(),
      local_date: "2026-10-28",
      timezone: "UTC",
      version: 1,
      state: "DRAFT",
      activities: [{
        activity_id: randomUUID(),
        kind: "FOCUS",
        title: `Synthetic focus ${marker}`,
        start_minute: 540,
        duration_minutes: 30,
        priority: "OPTIONAL",
        goal_id: null,
      }],
    },
  };
}

async function scenario(name, action) {
  const start = performance.now();
  try {
    const details = await action();
    addScenario(name, "PASS", performance.now() - start, details);
    return details;
  } catch (error) {
    addScenario(name, "FAIL", performance.now() - start, { error_code: safeErrorCode(error) });
    throw error;
  }
}

function trackPayload(payload) {
  const encoded = JSON.stringify(payload);
  payloadMarkers.add(encoded);
  payloadMarkers.add(payload.message);
  for (const activity of payload.plan.activities) payloadMarkers.add(activity.title);
  for (const marker of payloadMarkers) secretMarkers.add(marker);
}

function addScenario(name, status, durationMs, details = {}) {
  summary.scenarios.push({ name, status, duration_ms: Math.max(0, Math.round(durationMs)), ...details });
}

async function verifySystemd(t) {
  const started = performance.now();
  const result = spawnSync("systemd-analyze", ["--user", "verify", SYSTEMD_UNIT], {
    encoding: "utf8",
    timeout: 8000,
    maxBuffer: 128 * 1024,
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  capturedOutput.push(output);
  const busUnavailable = /Failed to (?:bind private socket|connect to system bus)|Operation not permitted/i.test(output);
  const status = result.error || busUnavailable ? "NOT_RUN" : result.status === 0 ? "PASS" : "FAIL";
  addScenario("systemd_analyze_user_verify", status, performance.now() - started, {
    command: "systemd-analyze --user verify integrations/relay/pc/systemd/atlas-relay.service",
    exit_code: result.status ?? null,
    user_manager_available: !busUnavailable,
    service_installed_or_started: false,
    ...(status === "NOT_RUN" ? { reason: "user systemd manager is unavailable in this sandbox" } : {}),
  });
  if (status === "FAIL") assert.fail("systemd unit verification failed");
  if (status === "NOT_RUN") t.diagnostic("systemd --user verify could not reach a user manager; no unit was installed or started");
}

async function inspectLinger(t, before) {
  const started = performance.now();
  const after = await readLingerState();
  const unchanged = before.available && after.available && before.value === after.value;
  const busUnavailable = !before.available || !after.available;
  const value = after.value;
  const status = busUnavailable ? "NOT_RUN" : unchanged ? "PASS" : "FAIL";
  addScenario("linger_not_enabled_by_test", status, performance.now() - started, {
    command_invoked: "loginctl show-user <uid> -p Linger --value",
    read_only: true,
    enable_linger_command_invoked: false,
    observed_linger_before: before.value === "yes" ? "enabled" : before.value === "no" ? "disabled" : "unknown",
    observed_linger_after: value === "yes" ? "enabled" : value === "no" ? "disabled" : "unknown",
    unchanged,
    ...(busUnavailable ? { reason: "login manager unavailable; test never invokes enable-linger" } : {}),
  });
  if (status === "FAIL") assert.fail("linger state changed during test");
  if (status === "NOT_RUN") t.diagnostic("login manager unavailable; enable-linger was not invoked by this test");
}

async function readLingerState() {
  const result = spawnSync("loginctl", ["show-user", String(process.getuid()), "-p", "Linger", "--value"], {
    encoding: "utf8",
    timeout: 5000,
    maxBuffer: 16 * 1024,
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  const busUnavailable = /Failed to (?:bind private socket|connect to system bus)|Operation not permitted|Could not connect/i.test(output);
  const value = (result.stdout ?? "").trim().toLowerCase();
  return { available: !result.error && !busUnavailable && ["yes", "no"].includes(value), value };
}

async function probeRelay(value) {
  try {
    const relay = validateLocalRelayUrl(value);
    const health = new URL("/health", relay.href);
    health.protocol = "http:";
    const response = await fetch(health, { signal: AbortSignal.timeout(2000) });
    return { available: response.ok, status: response.status };
  } catch {
    return { available: false, status: null };
  }
}

function validateLocalRelayUrl(value) {
  const relay = new URL(value);
  if (relay.protocol !== "ws:" || relay.hostname !== "127.0.0.1" || relay.port !== "4402" || relay.pathname !== "/" || relay.username || relay.password || relay.search || relay.hash) {
    throw new Error("R3B harness only accepts the pinned ws://127.0.0.1:4402 relay");
  }
  return relay;
}

async function findFreePort() {
  const server = net.createServer();
  await new Promise((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("could not allocate a loopback port");
  await new Promise((resolveClose) => server.close(() => resolveClose()));
  return address.port;
}

async function isPortFree(port) {
  const server = net.createServer();
  try {
    await new Promise((resolveListen, reject) => {
      server.once("error", reject);
      server.listen(port, "127.0.0.1", resolveListen);
    });
    return await new Promise((resolveClose) => server.close(() => resolveClose(true)));
  } catch (error) {
    if (error?.code === "EADDRINUSE") return false;
    throw error;
  }
}

async function waitUntil(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await delay(50);
  }
  throw new Error("readiness timeout");
}

function sendJson(response, status, value) {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(value));
}

function getServiceToken() {
  return `r3b-synthetic-${randomBytes(24).toString("hex")}`;
}

function readExternalBackendSpec() {
  const raw = process.env.ATLAS_R3B_BACKEND_COMMAND_JSON;
  if (!raw) return null;
  return parseBackendCommand(raw, process.env.ATLAS_R3B_BACKEND_ENV_FILE ?? null);
}

function parseBackendCommand(raw, envFile) {
  let command;
  try { command = JSON.parse(raw); } catch { throw new Error("ATLAS_R3B_BACKEND_COMMAND_JSON must be a JSON argv array"); }
  if (!Array.isArray(command) || command.length === 0 || command.some((part) => typeof part !== "string" || !part || part.includes("\0"))) {
    throw new Error("ATLAS_R3B_BACKEND_COMMAND_JSON must be a nonempty JSON argv array");
  }
  return { command, envFile };
}

async function loadBackendEnvironment(path) {
  if (!path) return {};
  const info = await lstat(path).catch(() => null);
  if (!info || !info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) {
    throw new Error("external backend environment file must be a private regular file");
  }
  const result = {};
  for (const rawLine of (await readFile(path, "utf8")).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
    if (!match || Object.hasOwn(result, match[1])) throw new Error("invalid external backend environment file");
    const value = match[2];
    if (value.includes("\0") || value.includes("\n") || value.includes("\r")) throw new Error("invalid external backend environment value");
    result[match[1]] = value;
  }
  return result;
}

function validateLoopbackBackendUrl(value) {
  const url = new URL(value);
  const loopback = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]).has(url.hostname.toLowerCase());
  if (url.protocol !== "http:" || !loopback || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("external backend URL must be loopback HTTP");
  }
  return url.href.replace(/\/$/, "");
}

async function startExternalBackend(command, environment, baseUrl) {
  const child = spawn(command[0], command.slice(1), { cwd: PACKAGE_DIR, env: environment, stdio: ["ignore", "pipe", "pipe"] });
  child.stdout.on("data", (chunk) => capturedOutput.push(chunk.toString("utf8")));
  child.stderr.on("data", (chunk) => capturedOutput.push(chunk.toString("utf8")));
  try {
    await waitUntil(async () => {
      if (child.exitCode !== null || child.signalCode !== null) throw new Error("external backend exited during startup");
      return fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(500) }).then((response) => response.ok).catch(() => false);
    }, 15_000);
  } catch {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = waitForExit(child, 5000);
      child.kill("SIGTERM");
      try { await exited; } catch { child.kill("SIGKILL"); await waitForExit(child).catch(() => {}); }
    }
    throw new Error("external backend failed to become ready");
  }
  return {
    child,
    async stop() {
      if (child.exitCode !== null || child.signalCode !== null) return;
      const exited = waitForExit(child, 5000);
      child.kill("SIGTERM");
      try { await exited; } catch { child.kill("SIGKILL"); await waitForExit(child).catch(() => {}); }
    },
  };
}

function isolatedEnvironment(overrides = {}) {
  const environment = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TMPDIR", "PYTHONPATH", "VIRTUAL_ENV"]) {
    if (process.env[key] !== undefined) environment[key] = process.env[key];
  }
  return { ...environment, ...overrides };
}

function safeErrorCode(error) {
  if (error && typeof error.code === "string" && /^[A-Z0-9_]+$/.test(error.code)) return error.code;
  if (error && typeof error.name === "string" && /^[A-Za-z]+Error$/.test(error.name)) return error.name;
  return "ASSERTION_FAILED";
}

function emitSummary() {
  process.stdout.write(`R3B_RESULT_JSON=${JSON.stringify(summary)}\n`);
}

function readGitRevision() {
  const result = spawnSync("git", ["rev-parse", "HEAD"], { cwd: PACKAGE_DIR, encoding: "utf8", timeout: 3000 });
  return result.status === 0 ? result.stdout.trim() : null;
}

function isGitTreeDirty() {
  const result = spawnSync("git", ["status", "--porcelain"], { cwd: PACKAGE_DIR, encoding: "utf8", timeout: 3000 });
  return result.status === 0 ? result.stdout.length > 0 : null;
}
