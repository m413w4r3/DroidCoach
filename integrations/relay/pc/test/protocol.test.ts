import { describe, expect, it } from "vitest";
import { RemoteError } from "../src/errors.js";
import { PROTOCOL, parseJsonFrame, validateHello, validatePayload, validateRequest, type Hello } from "../src/protocol.js";
import { hello, request } from "./helpers.js";

const DEVICE_ID = "2d6f4701-21df-4f4f-a109-1028300572c8";
const TOKEN = Buffer.alloc(32, 7).toString("base64url");

describe("atlas.remote.v1 validation", () => {
  it("accepts a valid hello and rejects unknown fields", () => {
    const valid = hello(DEVICE_ID, TOKEN);
    expect(validateHello(valid).protocol).toBe(PROTOCOL);
    expect(() => validateHello({ ...valid, surprise: true })).toThrowError(RemoteError);
  });

  it("requires canonical nonce, token, timestamps, and exact envelope keys", () => {
    const valid = hello(DEVICE_ID, TOKEN);
    expect(() => validateHello({ ...valid, client_nonce: "not-base64" })).toThrowError(RemoteError);
    expect(() => validateHello({ ...valid, auth: { scheme: "device-token-v1", token: "short" } })).toThrowError(RemoteError);
    expect(() => validateHello({ ...valid, timestamp: "tomorrow" })).toThrowError(RemoteError);
  });

  it("rejects oversized and malformed JSON frames", () => {
    expect(() => parseJsonFrame("{".repeat(65_537))).toThrowError(RemoteError);
    expect(() => parseJsonFrame("not-json")).toThrowError(RemoteError);
  });

  it("rejects expired requests and unknown request keys", () => {
    const expired = request(DEVICE_ID, "sync.status", {}, {
      created_at: new Date(Date.now() - 60_000).toISOString(),
      expires_at: new Date(Date.now() - 1_000).toISOString(),
    });
    expect(() => validateRequest(expired, DEVICE_ID)).toThrowError("request expired");
    expect(() => validateRequest({ ...request(DEVICE_ID, "sync.status"), extra: 1 }, DEVICE_ID)).toThrowError(RemoteError);
  });

  it("accepts contract idempotency keys and rejects unsafe forms", () => {
    const valid = request(DEVICE_ID, "sync.status", {}, { idempotency_key: "device:retry-1.v1" });
    expect(validateRequest(valid, DEVICE_ID).idempotency_key).toBe("device:retry-1.v1");
    expect(() => validateRequest({ ...valid, idempotency_key: "contains spaces" }, DEVICE_ID)).toThrowError(RemoteError);
    expect(() => validateRequest({ ...valid, idempotency_key: "x".repeat(161) }, DEVICE_ID)).toThrowError(RemoteError);
  });

  it("enforces the closed operation payload shapes", () => {
    expect(() => validatePayload("coach.status", { path: "/anything" })).toThrowError(RemoteError);
    expect(() => validatePayload("sport.plan.read", { local_date: "2026-02-30" })).toThrowError(RemoteError);
    expect(() => validatePayload("plan.revision.result", { request_id: "../../etc/passwd" })).toThrowError(RemoteError);
  });
});
