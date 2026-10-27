export const ERROR_CODES = [
  "INVALID_REQUEST",
  "UNKNOWN_OPERATION",
  "PAYLOAD_TOO_LARGE",
  "EXPIRED",
  "AUTH_REQUIRED",
  "AUTH_FAILED",
  "DEVICE_REVOKED",
  "RATE_LIMITED",
  "IDEMPOTENCY_CONFLICT",
  "BACKEND_UNAVAILABLE",
  "BACKEND_REJECTED",
  "OPERATION_UNAVAILABLE",
  "CANCELLED",
  "INTERNAL",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

const ERROR_MESSAGES: Record<ErrorCode, string> = {
  INVALID_REQUEST: "invalid request",
  UNKNOWN_OPERATION: "unknown operation",
  PAYLOAD_TOO_LARGE: "payload too large",
  EXPIRED: "request expired",
  AUTH_REQUIRED: "authentication required",
  AUTH_FAILED: "authentication failed",
  DEVICE_REVOKED: "device revoked",
  RATE_LIMITED: "request limit exceeded",
  IDEMPOTENCY_CONFLICT: "idempotency conflict",
  BACKEND_UNAVAILABLE: "backend unavailable",
  BACKEND_REJECTED: "backend rejected request",
  OPERATION_UNAVAILABLE: "operation unavailable",
  CANCELLED: "request cancelled",
  INTERNAL: "internal error",
};

export class RemoteError extends Error {
  constructor(
    readonly code: ErrorCode,
    readonly retryable = false,
    message = ERROR_MESSAGES[code],
  ) {
    super(message.slice(0, 120));
    this.name = "RemoteError";
  }
}

export function asRemoteError(error: unknown): RemoteError {
  if (error instanceof RemoteError) return error;
  if (error instanceof DOMException && error.name === "AbortError") {
    return new RemoteError("CANCELLED");
  }
  return new RemoteError("INTERNAL");
}
