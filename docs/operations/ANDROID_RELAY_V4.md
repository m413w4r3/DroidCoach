# Android V4 sync through the authenticated relay

`CoreV4SyncWorker` selects one transport from the persisted relay mode:

- `DIRECT` uses the existing fixed-route Core HTTP transport and Android Core bearer.
- `RELAY` uses the live authenticated relay session. A disconnected session or a missing negotiated V4 capability fails with a typed error; the worker never falls back to the Core URL.
- `OFFLINE` sends nothing.

The relay adapter accepts only these fixed routes: plan snapshot, activity status, usage summary, goal snapshot, training snapshot, consent state, proposal pull, and `/v4/agent/proposals/{proposal_id}/ack`. It forwards the existing JSON envelope as the relay payload. ACK path and payload IDs must match. The Core bearer is stripped before dispatch; relay requests contain no caller supplied URL, method, header, or bearer.

The Android V2 codec now accepts the V4 operations and operation capabilities already defined by the PC connector. V1 remains unchanged. V2 hello capability count is bounded at the connector's existing 14-entry ceiling; request/response byte limits and the existing 4-second connect plus 4-second read timeout budget are unchanged. Relay failures retain a typed kind and sanitized remote code. Relay failures do not trigger an immediate worker retry; the existing scheduled sync remains the next opportunity. Plan and consent UI actions continue to enqueue the existing bounded WorkManager pass; this change adds no polling loop.

The focused JVM coverage is in `RelayCoreV4HttpTransportTest`; the full Android JVM suite also covers the existing relay, worker, and inbox behavior. Runtime validation still needs a paired, consented relay session; the follow-up ticket is pairing and consent runtime qualification.
