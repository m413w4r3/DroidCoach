import { describe, expect, it } from "vitest";
import {
  generateKeyPair,
  openClientSecureChannel,
  openDaemonSecureChannel,
  exportPublicKey,
  SecureChannelRejectedError,
  type Transport,
} from "@getpaseo/relay/e2ee";

class MemoryTransport implements Transport {
  onmessage: Transport["onmessage"] = null;
  onclose: Transport["onclose"] = null;
  onerror: Transport["onerror"] = null;
  peer: MemoryTransport | null = null;
  intercept: ((data: string | ArrayBuffer) => string | ArrayBuffer) | null = null;
  closeCode = 1000;

  send(data: string | ArrayBuffer): void {
    if (!this.peer) throw new Error("unpaired transport");
    const outgoing = this.intercept?.(data) ?? data;
    queueMicrotask(() => this.peer?.onmessage?.({ data: outgoing, isBinary: typeof outgoing !== "string" }));
  }

  close(code = 1000, reason = "closed"): void {
    this.closeCode = code;
    this.onclose?.(code, reason);
    this.peer?.onclose?.(code, reason);
  }

  pair(peer: MemoryTransport): void {
    this.peer = peer;
    peer.peer = this;
  }
}

function transports(): [MemoryTransport, MemoryTransport] {
  const client = new MemoryTransport();
  const daemon = new MemoryTransport();
  client.pair(daemon);
  return [client, daemon];
}

describe("Noise NK protected-only policy", () => {
  it("refuses a legacy e2ee_hello without fallback", async () => {
    const keyPair = generateKeyPair();
    const [, daemon] = transports();
    const rejected = openDaemonSecureChannel(daemon, { daemonKeyPair: keyPair, minimumMode: "protected" });
    daemon.onmessage?.({
      isBinary: false,
      data: JSON.stringify({ type: "e2ee_hello", key: Buffer.alloc(32, 3).toString("base64"), capabilities: { binaryCiphertext: true } }),
    });
    await expect(rejected).rejects.toBeInstanceOf(SecureChannelRejectedError);
  });

  it("rejects a tampered protected handshake", async () => {
    const keyPair = generateKeyPair();
    const [clientTransport, daemonTransport] = transports();
    clientTransport.intercept = (data) => {
      if (typeof data !== "string") return data;
      const message = JSON.parse(data) as Record<string, any>;
      if (message.type === "e2ee_hello" && message.protected?.message) {
        message.protected.message = `${message.protected.message.slice(0, -4)}AAAA`;
      }
      return JSON.stringify(message);
    };
    const daemonAttempt = openDaemonSecureChannel(daemonTransport, { daemonKeyPair: keyPair, minimumMode: "protected" });
    const clientAttempt = openClientSecureChannel(clientTransport, {
      daemonPublicKeyB64: exportPublicKey(keyPair.publicKey),
      minimumMode: "protected",
    });
    await expect(daemonAttempt).rejects.toThrow();
    daemonTransport.close(4002, "tampered handshake");
    const client = await clientAttempt;
    client.close(1000, "test complete");
  });
});
