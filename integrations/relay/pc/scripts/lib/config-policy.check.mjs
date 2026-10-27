import test from "node:test";
import assert from "node:assert/strict";
import { inspectConfiguration, validateRelayUrl } from "./config.mjs";

function accepts(value, options) {
  assert.doesNotThrow(() => validateRelayUrl(value, options), value);
}

function rejects(value, options) {
  assert.throws(() => validateRelayUrl(value, options), value);
}

test("accepts remote WSS relay URLs", () => {
  accepts("wss://relay.example.org");
  accepts("wss://relay.example.org:8443");
  accepts("wss://relay.example.org/");
  accepts("wss://relay.example.org/ws");
  accepts("wss://relay.atlas.test:4443");
});

test("rejects remote cleartext ws even with the loopback opt-in", () => {
  rejects("ws://relay.example.org:4400");
  rejects("ws://relay.example.org:4400", { allowLoopbackWs: true });
  rejects("ws://10.0.2.2:4400", { allowLoopbackWs: true });
});

test("requires an explicit opt-in for loopback ws", () => {
  rejects("ws://127.0.0.1:4400");
  rejects("ws://localhost:4400");
  rejects("ws://[::1]:4400");
  accepts("ws://127.0.0.1:4400", { allowLoopbackWs: true });
  accepts("ws://localhost:4400", { allowLoopbackWs: true });
  accepts("ws://[::1]:4400", { allowLoopbackWs: true });
});

test("rejects credentials, query, fragment, arbitrary paths and invalid ports", () => {
  rejects("wss://user:pass@relay.example.org");
  rejects("wss://relay.example.org?token=x");
  rejects("wss://relay.example.org#fragment");
  rejects("wss://relay.example.org/private");
  rejects("wss://relay.example.org:0");
  rejects("wss://relay.example.org:65536");
  rejects("wss://999.1.1.1");
  rejects("wss://relay..example.org");
});

test("configuration accepts a remote WSS relay and loopback FastAPI", () => {
  const result = inspectConfiguration({
    ATLAS_RELAY_URL: "wss://relay.example.org",
    ATLAS_BACKEND_URL: "http://127.0.0.1:8100",
  }, { checkState: false });
  assert.deepEqual(result.errors, []);
  assert.match(result.checks.join("\n"), /WSS\/loopback policy/);
});

test("configuration requires the explicit loopback opt-in for ws", () => {
  const denied = inspectConfiguration({
    ATLAS_RELAY_URL: "ws://127.0.0.1:4400",
    ATLAS_BACKEND_URL: "http://127.0.0.1:8100",
  }, { checkState: false });
  assert.equal(denied.errors.length, 1);
  assert.match(denied.errors[0], /wss:\/\//);

  const allowed = inspectConfiguration({
    ATLAS_RELAY_URL: "ws://127.0.0.1:4400",
    ATLAS_ALLOW_LOOPBACK_WS: "1",
    ATLAS_BACKEND_URL: "http://127.0.0.1:8100",
  }, { checkState: false });
  assert.deepEqual(allowed.errors, []);
});

test("configuration rejects an invalid loopback opt-in value", () => {
  const result = inspectConfiguration({
    ATLAS_RELAY_URL: "wss://relay.example.org",
    ATLAS_ALLOW_LOOPBACK_WS: "yes",
    ATLAS_BACKEND_URL: "http://127.0.0.1:8100",
  }, { checkState: false });
  assert.match(result.errors.join("\n"), /ATLAS_ALLOW_LOOPBACK_WS must be 0 or 1/);
});
