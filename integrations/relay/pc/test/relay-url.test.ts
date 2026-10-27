import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { assertLoopbackRelayUrl, parseRelayUrl } from "../src/relay-link.js";

describe("production relay URL policy", () => {
  beforeEach(() => vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", ""));
  afterEach(() => vi.unstubAllEnvs());

  it("accepts WSS with syntactically valid DNS names and IP addresses", () => {
    expect(parseRelayUrl("wss://relay.example.org").hostname).toBe("relay.example.org");
    expect(parseRelayUrl("wss://relay.example.org:8443/ws").port).toBe("8443");
    expect(parseRelayUrl("wss://192.0.2.8:443").hostname).toBe("192.0.2.8");
    expect(parseRelayUrl("wss://[2001:db8::8]:443/ws").hostname).toBe("[2001:db8::8]");
  });

  it("denies cleartext WebSockets unless explicitly enabled for a loopback host", () => {
    for (const host of ["127.0.0.1", "localhost", "[::1]"]) {
      expect(() => parseRelayUrl(`ws://${host}:4400`)).toThrow();
      expect(parseRelayUrl(`ws://${host}:4400`, { allowLoopbackWs: true }).protocol).toBe("ws:");
    }
    expect(() => parseRelayUrl("ws://192.0.2.8:4400", { allowLoopbackWs: true })).toThrow();
    expect(() => parseRelayUrl("ws://10.0.2.2:4400", { allowLoopbackWs: true })).toThrow();
    expect(() => parseRelayUrl("ws://127.1:4400", { allowLoopbackWs: true })).toThrow();
  });

  it("accepts the loopback environment opt-in only when its value is exactly 1", () => {
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "true");
    expect(() => parseRelayUrl("ws://localhost:4400")).toThrow();
    vi.stubEnv("ATLAS_ALLOW_LOOPBACK_WS", "1");
    expect(parseRelayUrl("ws://localhost:4400").hostname).toBe("localhost");
    expect(() => parseRelayUrl("ws://relay.example.org:4400")).toThrow();
  });

  it("rejects userinfo, queries, fragments, unsupported paths, and non-WebSocket schemes", () => {
    for (const value of [
      "wss://user:secret@relay.example.org",
      "wss://@relay.example.org",
      "wss://relay.example.org?token=hidden",
      "wss://relay.example.org?",
      "wss://relay.example.org#fragment",
      "wss://relay.example.org#",
      "wss://relay.example.org/",
      "wss://relay.example.org/other",
      "https://relay.example.org",
    ]) {
      expect(() => parseRelayUrl(value)).toThrow();
    }
    expect(parseRelayUrl("wss://relay.example.org/ws").pathname).toBe("/ws");
    expect(assertLoopbackRelayUrl("ws://127.0.0.1:4400").hostname).toBe("127.0.0.1");
  });

  it("rejects empty or malformed hosts and invalid explicit ports", () => {
    for (const value of [
      "wss:///ws",
      "wss://",
      "wss://127.1:443",
      "wss://0x7f.1:443",
      "wss://999.1.1.1:443",
      "wss://relay..example.org",
      "wss://-relay.example.org",
      "wss://relay_.example.org",
      "wss://relay.example.org:",
      "wss://relay.example.org:abc",
      "wss://relay.example.org:0",
      "wss://relay.example.org:65536",
    ]) {
      expect(() => parseRelayUrl(value)).toThrow();
    }
    expect(parseRelayUrl("wss://relay.example.org:65535").port).toBe("65535");
  });
});
