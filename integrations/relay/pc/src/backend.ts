import { RemoteError } from "./errors.js";
import { assertSmallResponse, type Operation } from "./protocol.js";

export type FetchLike = typeof fetch;

export interface BackendConfig {
  baseUrl: string;
  serviceToken?: string;
  timeoutMs?: number;
  fetcher?: FetchLike;
}

/** A backend error with an explicit statement about whether a POST may have taken effect. */
export class BackendRequestError extends RemoteError {
  constructor(
    code: ConstructorParameters<typeof RemoteError>[0],
    retryable: boolean,
    readonly outcomeMayHaveOccurred: boolean,
    message?: string,
  ) {
    super(code, retryable, message);
  }
}

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

export function assertLoopbackHttpUrl(value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("backend URL must be an absolute loopback HTTP URL");
  }
  if (url.protocol !== "http:" || !LOOPBACK_HOSTS.has(url.hostname.toLowerCase()) || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("backend URL must be an absolute loopback HTTP URL");
  }
  return url;
}

export class BackendClient {
  readonly baseUrl: URL;
  private readonly fetcher: FetchLike;
  private readonly timeoutMs: number;

  constructor(private readonly config: BackendConfig) {
    this.baseUrl = assertLoopbackHttpUrl(config.baseUrl);
    this.fetcher = config.fetcher ?? fetch;
    this.timeoutMs = config.timeoutMs ?? 5_000;
  }

  async execute(operation: Operation, payload: Record<string, unknown>, signal?: AbortSignal): Promise<Record<string, unknown>> {
    const route = routeFor(operation, payload);
    if (!route) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (route.method !== "GET" && !this.config.serviceToken) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (route.auth && !this.config.serviceToken) throw new RemoteError("OPERATION_UNAVAILABLE");
    const headers: Record<string, string> = { Accept: "application/json" };
    if (route.auth && this.config.serviceToken) headers.Authorization = `Bearer ${this.config.serviceToken}`;
    if (route.body) headers["Content-Type"] = "application/json";
    const combinedSignal = signal ? AbortSignal.any([signal, AbortSignal.timeout(this.timeoutMs)]) : AbortSignal.timeout(this.timeoutMs);
    const init: RequestInit = { method: route.method, headers, signal: combinedSignal, redirect: "error" };
    if (route.body) init.body = JSON.stringify(route.body);
    let response: Response;
    try {
      response = await this.fetcher(new URL(route.path, this.baseUrl), init);
    } catch (error) {
      if (signal?.aborted) {
        throw new BackendRequestError("CANCELLED", false, route.method === "POST");
      }
      throw new BackendRequestError("BACKEND_UNAVAILABLE", route.method === "GET", route.method === "POST");
    }
    if (response.status === 404) throw new RemoteError("OPERATION_UNAVAILABLE");
    if (!response.ok) {
      const mayHaveOccurred = route.method === "POST" && (response.status >= 500 || response.status === 408);
      throw new BackendRequestError("BACKEND_REJECTED", response.status >= 500, mayHaveOccurred);
    }
    try {
      const result = await readBoundedJson(response);
      if (operation === "backend.health") {
        return { status: result.status === "ok" ? "ok" : "unavailable" };
      }
      assertSmallResponse(result);
      return result;
    } catch (error) {
      if (route.method === "POST" && error instanceof RemoteError) {
        throw new BackendRequestError(error.code, error.retryable, true, error.message);
      }
      throw error;
    }
  }
}

interface Route {
  method: "GET" | "POST";
  path: string;
  auth: boolean;
  body?: Record<string, unknown>;
}

function routeFor(operation: Operation, payload: Record<string, unknown>): Route | null {
  switch (operation) {
    case "backend.health":
      return { method: "GET", path: "/health", auth: false };
    case "coach.status":
      return { method: "GET", path: "/v2/agent/status", auth: true };
    case "plan.today.read":
      return { method: "GET", path: "/v1/plan/today", auth: true };
    case "plan.revision.request":
      return { method: "POST", path: "/v3/agent/plan-revision", auth: true, body: payload };
    case "sport.plan.read": {
      const date = encodeURIComponent(String(payload.local_date));
      const timezone = encodeURIComponent(String(payload.timezone));
      return { method: "GET", path: `/v1/sport/plan?local_date=${date}&timezone=${timezone}`, auth: true };
    }
    case "plan.revision.result":
    case "sync.status":
      return null;
  }
}

async function readBoundedJson(response: Response): Promise<Record<string, unknown>> {
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > 262_144) throw new RemoteError("PAYLOAD_TOO_LARGE");
  if (!response.body) throw new RemoteError("BACKEND_REJECTED");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > 262_144) {
        await reader.cancel();
        throw new RemoteError("PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  let value: unknown;
  try {
    value = JSON.parse(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).toString("utf8")) as unknown;
  } catch {
    throw new RemoteError("BACKEND_REJECTED");
  }
  assertSmallResponse(value);
  return value;
}
