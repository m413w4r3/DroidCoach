# Android V4 sync through the authenticated relay

`CoreV4SyncWorker` selects one transport from the persisted relay mode:

- `DIRECT` uses the existing fixed-route Core HTTP transport and Android Core bearer.
- `RELAY` uses the live authenticated relay session. A disconnected session or a missing negotiated V4 capability fails with a typed error; the worker never falls back to the Core URL.
- `OFFLINE` sends nothing.

The relay adapter accepts only these fixed routes: plan snapshot, activity status, usage summary, goal snapshot, training snapshot, consent state, proposal pull, and `/v4/agent/proposals/{proposal_id}/ack`. It forwards the existing JSON payload unchanged. For V4 operations with a payload `request_id`, the relay envelope reuses that UUID. Snapshot, consent, and pull idempotency uses the payload `idempotency_key`, or its `request_id` when that field is absent; V4 chat uses its `request_id` for both envelope request ID and idempotency key. ACK uses `ack_id` as its idempotency key and a valid envelope request ID; the path and payload proposal IDs must match. Invalid IDs fail closed. The Core bearer is stripped before dispatch; relay requests contain no caller supplied URL, method, header, or bearer.

The Android V2 codec accepts the V4 operations and operation capabilities already defined by the PC connector. V2 errors may carry bounded `http_status` and `remote_code` metadata; unknown fields remain rejected, V1 keeps its original error shape, and upstream message text is not surfaced. HTTP status and V4 error codes retain their typed classification. V1 request behavior remains unchanged. V2 hello capability count is bounded at the connector's existing 14-entry ceiling; request/response byte limits and the existing 4-second connect plus 4-second read timeout budget are unchanged. Relay failures do not trigger an immediate worker retry; the existing scheduled sync remains the next opportunity. Plan and consent UI actions continue to enqueue the existing bounded WorkManager pass; this change adds no polling loop.

The focused JVM coverage is in `RelayCoreV4HttpTransportTest`; the full Android JVM suite also covers the existing relay, worker, and inbox behavior. Runtime validation still needs a paired, consented relay session; the follow-up ticket is pairing and consent runtime qualification.
