import WebSocket, { type ClientOptions, type RawData } from "ws";
import { isIP } from "node:net";
import type { ClientRequestArgs } from "node:http";
import {
  openDaemonSecureChannel,
  SecureChannelRejectedError,
  type KeyPair,
  type SecureChannelEvents,
  type Transport,
  type TransportMessage,
} from "@getpaseo/relay/e2ee";
import { RemoteError } from "./errors.js";
import type { ConnectorMetrics } from "./metrics.js";
import { SessionManager, type PeerSession } from "./session.js";

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);
const LOOPBACK_WS_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

export interface RelayUrlPolicyOptions {
  /** Explicit opt-in for an unencrypted local relay; otherwise the environment must equal "1". */
  allowLoopbackWs?: boolean;
}

type TestOnlyTlsOptions = Pick<ClientOptions, "ca"> & Pick<ClientRequestArgs, "lookup">;

export interface RelayLinkOptions {
  relayUrl: string;
  serverId: string;
  daemonKeyPair: KeyPair;
  sessions: SessionManager;
  metrics: ConnectorMetrics;
  WebSocketClass?: typeof WebSocket;
  allowLoopbackWs?: boolean;
  /** Test harness only. Production configuration does not read TLS overrides from the environment. */
  testOnlyTlsOptions?: TestOnlyTlsOptions;
  reconnectMinMs?: number;
  reconnectMaxMs?: number;
}

interface DataConnection {
  socket: WebSocket;
  peer: PeerSession | null;
  pending: Array<string | ArrayBuffer>;
}

export class RelayLink {
  private readonly baseUrl: URL;
  private readonly WebSocketClass: typeof WebSocket;
  private readonly webSocketOptions: ClientOptions;
  private control: WebSocket | null = null;
  private readonly data = new Map<string, DataConnection>();
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectDelay: number;
  private stopping = true;
  private controlWaiters = new Set<() => void>();
  private readonly stateWaiters = new Set<{ connected: boolean; resolve: () => void }>();

  constructor(private readonly options: RelayLinkOptions) {
    this.baseUrl = parseRelayUrl(
      options.relayUrl,
      options.allowLoopbackWs === undefined ? {} : { allowLoopbackWs: options.allowLoopbackWs },
    );
    if (!options.serverId || Buffer.byteLength(options.serverId, "utf8") > 200) throw new Error("invalid relay server id");
    this.WebSocketClass = options.WebSocketClass ?? WebSocket;
    this.webSocketOptions = {
      followRedirects: false,
      ...(options.testOnlyTlsOptions ?? {}),
    };
    this.reconnectDelay = options.reconnectMinMs ?? 500;
  }

  get connected(): boolean {
    return this.control?.readyState === WebSocket.OPEN;
  }

  async start(): Promise<void> {
    if (!this.stopping) return;
    this.stopping = false;
    this.openControl();
  }

  async waitForControl(timeoutMs = 5_000): Promise<void> {
    if (this.connected) return;
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.controlWaiters.delete(onReady);
        reject(new Error("relay control connection timeout"));
      }, timeoutMs);
      const onReady = () => {
        clearTimeout(timeout);
        resolve();
      };
      this.controlWaiters.add(onReady);
    });
  }

  async waitForState(connected: boolean, timeoutMs = 5_000): Promise<void> {
    if (this.connected === connected) return;
    await new Promise<void>((resolve, reject) => {
      const waiter = { connected, resolve: () => { clearTimeout(timeout); this.stateWaiters.delete(waiter); resolve(); } };
      const timeout = setTimeout(() => {
        this.stateWaiters.delete(waiter);
        reject(new Error("relay state transition timeout"));
      }, timeoutMs);
      this.stateWaiters.add(waiter);
    });
  }

  async stop(): Promise<void> {
    this.stopping = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.reconnectTimer = null;
    this.pingTimer = null;
    const control = this.control;
    this.control = null;
    for (const connection of this.data.values()) connection.socket.close(1000, "connector stopping");
    this.data.clear();
    this.options.metrics.relayConnected = false;
    this.notifyState(false);
    if (control && control.readyState !== WebSocket.CLOSED) control.close(1000, "connector stopping");
  }

  private openControl(): void {
    if (this.stopping) return;
    const url = this.buildUrl("server");
    const socket = new this.WebSocketClass(url, this.webSocketOptions);
    this.control = socket;
    socket.on("open", () => {
      if (this.control !== socket || this.stopping) return;
      this.reconnectDelay = this.options.reconnectMinMs ?? 500;
      this.options.metrics.relayConnected = true;
      this.notifyState(true);
      this.sendControlPing(socket);
      this.pingTimer = setInterval(() => this.sendControlPing(socket), 10_000);
      this.pingTimer.unref?.();
      for (const waiter of this.controlWaiters) waiter();
      this.controlWaiters.clear();
    });
    socket.on("message", (data, isBinary) => {
      if (isBinary || this.control !== socket) return;
      this.handleControl(data.toString("utf8"));
    });
    socket.on("error", () => {
      // WebSocket close drives the bounded reconnect path. Payloads and error details stay private.
    });
    socket.on("close", () => {
      if (this.control !== socket) return;
      this.control = null;
      if (this.pingTimer) clearInterval(this.pingTimer);
      this.pingTimer = null;
      this.options.metrics.relayConnected = false;
      this.notifyState(false);
      for (const connection of this.data.values()) connection.socket.close(1012, "relay control disconnected");
      this.data.clear();
      this.scheduleReconnect();
    });
  }

  private sendControlPing(socket: WebSocket): void {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "ping" }));
  }

  private handleControl(text: string): void {
    let message: unknown;
    try {
      message = JSON.parse(text) as unknown;
    } catch {
      return;
    }
    if (!isRecord(message) || typeof message.type !== "string") return;
    if (message.type === "sync" && Array.isArray(message.connectionIds)) {
      for (const id of message.connectionIds) if (isConnectionId(id)) this.openData(id);
    } else if (message.type === "connected" && isConnectionId(message.connectionId)) {
      this.openData(message.connectionId);
    } else if (message.type === "disconnected" && isConnectionId(message.connectionId)) {
      const connection = this.data.get(message.connectionId);
      if (connection) connection.socket.close(1000, "client disconnected");
      this.data.delete(message.connectionId);
    }
  }

  private openData(connectionId: string): void {
    if (this.stopping || this.data.has(connectionId)) return;
    const socket = new this.WebSocketClass(this.buildUrl("server", connectionId), this.webSocketOptions);
    const connection: DataConnection = { socket, peer: null, pending: [] };
    this.data.set(connectionId, connection);
    socket.on("open", () => {
      if (this.data.get(connectionId) !== connection) return;
      this.options.metrics.connectionOpened();
    });
    socket.on("error", () => {
      // Close handling drops this data connection without logging any frame contents.
    });
    socket.on("close", () => {
      if (this.data.get(connectionId) === connection) this.data.delete(connectionId);
    });
    socket.once("open", () => void this.attachSecureChannel(connection));
  }

  private async attachSecureChannel(connection: DataConnection): Promise<void> {
    let peer: PeerSession | null = null;
    const events: SecureChannelEvents = {
      onmessage: (data) => {
        if (peer) void peer.receive(data);
        else if (connection.pending.length < 4) connection.pending.push(data);
        else connection.socket.close(4002, "application queue full");
      },
    };
    const transport = createTransport(connection.socket);
    try {
      const channel = await openDaemonSecureChannel(transport, {
        daemonKeyPair: this.options.daemonKeyPair,
        minimumMode: "protected",
        events,
      });
      if (channel.securityMode() !== "protected") {
        channel.close(1008, "protected endpoint security required");
        throw new RemoteError("AUTH_FAILED");
      }
      peer = this.options.sessions.create(channel);
      connection.peer = peer;
      const pending = connection.pending;
      connection.pending = [];
      for (const item of pending) void peer.receive(item);
    } catch (error) {
      const code = error instanceof SecureChannelRejectedError ? error.closeCode : 4002;
      if (connection.socket.readyState === WebSocket.OPEN) connection.socket.close(code, "secure handshake failed");
    }
  }

  private buildUrl(role: "server", connectionId?: string): string {
    const url = new URL("/ws", this.baseUrl);
    url.searchParams.set("serverId", this.options.serverId);
    url.searchParams.set("role", role);
    url.searchParams.set("v", "2");
    if (connectionId) url.searchParams.set("connectionId", connectionId);
    return url.toString();
  }

  private scheduleReconnect(): void {
    if (this.stopping || this.reconnectTimer) return;
    const max = this.options.reconnectMaxMs ?? 10_000;
    const delay = this.reconnectDelay;
    this.reconnectDelay = Math.min(max, Math.max(delay + 1, delay * 2));
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.openControl();
    }, delay);
    this.reconnectTimer.unref?.();
  }

  private notifyState(connected: boolean): void {
    for (const waiter of [...this.stateWaiters]) if (waiter.connected === connected) waiter.resolve();
  }
}

export function assertLoopbackRelayUrl(value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("relay URL must be a loopback WebSocket URL");
  }
  if (!["ws:", "wss:"].includes(url.protocol) || !LOOPBACK_HOSTS.has(url.hostname.toLowerCase()) || url.username || url.password || (url.pathname !== "/" && url.pathname !== "/ws") || url.search || url.hash) {
    throw new Error("relay URL must be a loopback WebSocket URL");
  }
  return url;
}

/** Validates a production relay endpoint before Node's WebSocket client is created. */
export function parseRelayUrl(value: string, options: RelayUrlPolicyOptions = {}): URL {
  const parts = /^(?:[A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(?:\?[^#]*)?(?:#.*)?$/.exec(value);
  if (!parts) throw new Error("relay URL is invalid");

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("relay URL is invalid");
  }

  const authority = parts[1] ?? "";
  const path = parts[2] ?? "";
  const scheme = url.protocol;
  if (scheme !== "ws:" && scheme !== "wss:") throw new Error("relay URL must use ws:// or wss://");
  if (authority.length === 0 || authority.includes("@") || url.username || url.password) {
    throw new Error("relay URL must contain a host without userinfo");
  }
  if (value.includes("?") || value.includes("#") || url.search || url.hash) {
    throw new Error("relay URL must not contain a query or fragment");
  }
  if (path !== "" && path !== "/ws") throw new Error("relay URL path must be empty or /ws");

  const { host: rawHost, port: rawPort } = splitAuthority(authority);
  if (rawHost.length === 0 || rawHost.includes("%")) throw new Error("relay URL host is invalid");
  if (rawPort !== undefined && (!/^\d+$/.test(rawPort) || Number(rawPort) < 1 || Number(rawPort) > 65_535)) {
    throw new Error("relay URL port is invalid");
  }

  const hostname = url.hostname.toLowerCase();
  const addressHost = rawHost.startsWith("[") && rawHost.endsWith("]")
    ? rawHost.slice(1, -1)
    : rawHost;
  const addressType = isIP(addressHost);
  const normalizedHost = hostname.startsWith("[") && hostname.endsWith("]")
    ? hostname.slice(1, -1)
    : hostname;
  if (rawHost.startsWith("[") && addressType !== 6) throw new Error("relay URL host is invalid");
  if (addressType === 0) {
    if (/^[0-9.]+$/.test(addressHost) || isIP(normalizedHost) !== 0 || !isValidDnsHostname(hostname)) {
      throw new Error("relay URL host is invalid");
    }
  }

  if (scheme === "ws:") {
    const loopbackEnabled = options.allowLoopbackWs ?? process.env.ATLAS_ALLOW_LOOPBACK_WS === "1";
    if (!loopbackEnabled || !LOOPBACK_WS_HOSTS.has(rawHost.toLowerCase())) {
      throw new Error("ws:// is allowed only for an explicitly enabled loopback relay");
    }
  }
  return url;
}

function splitAuthority(authority: string): { host: string; port?: string } {
  if (authority.startsWith("[")) {
    const closeBracket = authority.indexOf("]");
    if (closeBracket < 0) throw new Error("relay URL host is invalid");
    const host = authority.slice(0, closeBracket + 1);
    const suffix = authority.slice(closeBracket + 1);
    if (suffix.length === 0) return { host };
    if (!suffix.startsWith(":")) throw new Error("relay URL port is invalid");
    return { host, port: suffix.slice(1) };
  }

  const firstColon = authority.indexOf(":");
  const lastColon = authority.lastIndexOf(":");
  if (firstColon < 0) return { host: authority };
  if (firstColon !== lastColon) throw new Error("IPv6 relay hosts must be bracketed");
  return { host: authority.slice(0, firstColon), port: authority.slice(firstColon + 1) };
}

function isValidDnsHostname(hostname: string): boolean {
  if (hostname.length === 0 || hostname.length > 254 || !/^[A-Za-z0-9.-]+$/.test(hostname)) return false;
  const withoutTrailingDot = hostname.endsWith(".") ? hostname.slice(0, -1) : hostname;
  if (withoutTrailingDot.length === 0 || withoutTrailingDot.length > 253) return false;
  return withoutTrailingDot.split(".").every((label) =>
    label.length >= 1 && label.length <= 63 && /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/.test(label),
  );
}

function createTransport(socket: WebSocket): Transport {
  const transport: Transport = {
    onmessage: null,
    onclose: null,
    onerror: null,
    send(data: string | ArrayBuffer): void {
      if (socket.readyState !== WebSocket.OPEN) throw new Error("transport is closed");
      if (typeof data === "string") socket.send(data, { binary: false });
      else socket.send(Buffer.from(data), { binary: true });
    },
    close(code = 1000, reason = "closed"): void {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(code, reason);
    },
  };
  socket.on("message", (data, isBinary) => transport.onmessage?.(transportMessage(data, isBinary)));
  socket.on("close", (code, reason) => transport.onclose?.(code, reason.toString("utf8")));
  socket.on("error", () => transport.onerror?.(new Error("transport error")));
  return transport;
}

function transportMessage(raw: RawData, isBinary: boolean): TransportMessage {
  if (!isBinary) return { data: raw.toString(), isBinary: false };
  const buffer = Buffer.isBuffer(raw) ? raw : Array.isArray(raw) ? Buffer.concat(raw) : Buffer.from(raw as ArrayBuffer);
  const copy = Uint8Array.from(buffer);
  return { data: copy.buffer, isBinary: true };
}

function isConnectionId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 256;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
