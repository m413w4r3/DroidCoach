# ATLAS Relay Connector for PC

Local Node 24 ESM service that accepts ATLAS remote sessions over Paseo Relay
v2, requires Noise NK `protected`, authenticates paired devices, and maps a
closed operation set to FastAPI on loopback.

## Local setup

The package expects the repository's local Paseo reference clone and a Relay
instance on loopback. From this directory:

```sh
npm install
npm run build
npm test
npm start
```

`npm test` includes a Node Noise client against the real local Relay at
`127.0.0.1:4400`; keep that local service running. It also exercises a Node
client and Node connector directly. This is **not Android end-to-end evidence**.

The connector defaults to the Relay at `ws://127.0.0.1:4400`, FastAPI at
`http://127.0.0.1:8100`, state under `~/.local/state/atlas-relay`, and metrics
on `127.0.0.1:4401/metrics`. FastAPI can be started separately with a
synthetic service token in the local environment. The connector never binds
its metrics listener beyond loopback.

## Environment

| Variable | Purpose |
|---|---|
| `ATLAS_RELAY_URL` | Loopback WebSocket base URL for Paseo Relay. Non-loopback hosts are rejected. |
| `ATLAS_RELAY_SERVER_ID` | Optional stable Relay routing ID; defaults to a value derived from the public identity fingerprint. |
| `ATLAS_RELAY_STATE_DIR` | Directory for the private server identity and device registry. |
| `ATLAS_BACKEND_URL` | Loopback FastAPI base URL. Non-loopback hosts, URL paths, query strings, and credentials are rejected. |
| `ATLAS_SERVICE_TOKEN` | Bearer token used for authenticated FastAPI routes. It is read from the environment and never persisted or logged. |
| `ATLAS_METRICS_PORT` | Loopback metrics port; defaults to `4401`. |
| `ATLAS_TEST_RELAY_URL` | Optional loopback Relay URL used by the real-relay integration test. |

Do not place token values in command arguments, checked-in files, or logs.

## Pairing and identity

```sh
node dist/main.js show-identity
node dist/main.js pair-device --label "synthetic-device" --capabilities sync.status,plan.revision
node dist/main.js revoke-device DEVICE_UUID
```

`show-identity` prints only the X25519 public key and SHA-256 fingerprint.
`pair-device` prints a new 32-byte base64url device token once; the registry
stores only its SHA-256 hash. The identity and registry files are mode `0600`
inside a mode `0700` state directory. Revocation updates the registry; a
running connector checks it every second and closes the device session with
WebSocket code `4001`.

## Operation boundary

The connector accepts only `atlas.remote.v1` and the operations listed in
`docs/v6-3/r3a/TRANSPORT_PASEO.md`. Backend routes are constants in the
adapter: `GET /health`, `GET /v2/agent/status`, `GET /v1/plan/today`, and
`POST /v3/agent/plan-revision`. It has no URL, path, or command forwarding
field. `plan.revision.result` and `sync.status` are local operations.

The frozen contract also lists `GET /v1/sport/plan`; that route is absent from
the current `backend/atlas_backend/main.py`. The connector maps it only to
that fixed route and reports `OPERATION_UNAVAILABLE` when FastAPI returns 404.

Requests are limited to 32 KiB, responses to 256 KiB, the per-device quota is
60 requests per minute with at most four in flight, sessions expire after one
hour, and idempotency results are retained in memory for ten minutes. Logs do
not include tokens, payloads, or backend response bodies.

## Paseo dependency provenance

`@getpaseo/relay` is installed from
`file:../../../references/repos/paseo/packages/relay`. Its built package comes
from Paseo commit `902b0c7a1637cc7a6c2ee268dca8161965a35def` (0.13.1), under
Apache-2.0. Runtime imports use the public `@getpaseo/relay/e2ee` API. No
daemon or relay source is copied into this connector.
