# ATLAS Relay Connector for PC

Local Node 24 ESM service that accepts ATLAS remote sessions over Paseo Relay
v2, requires Noise NK `protected`, authenticates paired devices, and maps a
closed operation set to FastAPI on loopback.

## Local setup

The vendored relay runtime is a small npm tarball built from the pinned Paseo
commit; no `references/` checkout is needed. From this directory:

```sh
npm ci
npm run build
npm run test:unit
```

The full `npm test` suite also runs relay-dependent cases (the real Relay
integration test and the stale-client handshake test) that require a live
Relay at `ATLAS_TEST_RELAY_URL` (default `ws://127.0.0.1:4400`). Start the
test relay before running that full suite. For a reproducible pinned relay,
use `scripts/run-isolated-relay-tests.sh`. This is **not Android end-to-end
evidence**.

For a host installation with an external environment file and restricted
configuration checks, follow
[`docs/v6-3/r3a-plus/INSTALLATION_PC.md`](../../../docs/v6-3/r3a-plus/INSTALLATION_PC.md).
The Linux user unit and rollback procedure are in
[`docs/v6-3/r3a-plus/SUPERVISION_PC.md`](../../../docs/v6-3/r3a-plus/SUPERVISION_PC.md).

`npm test` includes a Node Noise client against the real local Relay at
`127.0.0.1:4400`; keep that local service running. It also exercises a Node
client and Node connector directly. This is **not Android end-to-end evidence**.

The connector accepts remote `wss://` Relay endpoints and, only with the
explicit `ATLAS_ALLOW_LOOPBACK_WS=1` development opt-in, `ws://` on exact
loopback. It defaults to `ws://127.0.0.1:4400` for local development, FastAPI at
`http://127.0.0.1:8100`, state under `~/.local/state/atlas-relay`, and metrics
on `127.0.0.1:4401/metrics`. FastAPI can be started separately with a synthetic
service token in the local environment. The connector never binds its metrics
listener beyond loopback, and it never forwards a generic HTTP route.

## Host support and registry lock

The qualified host is Linux (the deployment unit in
`docs/v6-3/r3a/DEPLOIEMENT_DISTANT.md` is a systemd user service). On Linux the
registry lock uses `/proc/<pid>/stat` start time to distinguish a recycled PID.
On other POSIX or Windows hosts the liveness check falls back to
`process.kill(pid, 0)`; a recycled PID can then keep a dead owner's ticket until
the 15-second timeout. That fallback is availability-only and never permits two
processes to enter the critical section. macOS and Windows are not qualified
for this campaign.

The lock directory fails closed: an unreadable directory, an unreadable record,
or an invalid record aborts the operation instead of treating the lock as
uncontended. Only an `ENOENT` for an individual record is tolerated, because it
is the expected race with a concurrent release.

## Environment

| Variable | Purpose |
|---|---|
| `ATLAS_RELAY_URL` | Relay WebSocket base URL. Remote relays must use `wss://`. `ws://` is accepted only on exact loopback with `ATLAS_ALLOW_LOOPBACK_WS=1`. Credentials, query, fragment and arbitrary paths are rejected. |
| `ATLAS_RELAY_SERVER_ID` | Optional stable Relay routing ID; defaults to a value derived from the public identity fingerprint. |
| `ATLAS_RELAY_STATE_DIR` | Directory for the private server identity and device registry. |
| `ATLAS_BACKEND_URL` | Loopback FastAPI base URL. Non-loopback hosts, URL paths, query strings, and credentials are rejected. |
| `ATLAS_SERVICE_TOKEN` | Bearer token used for authenticated FastAPI routes. It is read from the environment and never persisted or logged. |
| `ATLAS_V4_ENABLED` | Explicit V4 opt-in. Only the exact value `1` enables V4; unset or any other value keeps V4 disabled. |
| `ATLAS_V4_ANDROID_TOKEN_FILE` | Absolute path to the dedicated Core Android bearer file. Required for V4 requests; the file must be a regular, non-symlink file owned by the connector user with mode `0600`. |
| `ATLAS_ALLOW_LOOPBACK_WS` | Set to `1` only for explicit local loopback `ws://` development; default deny. |
| `ATLAS_METRICS_PORT` | Loopback metrics port; defaults to `4401`. |
| `ATLAS_TEST_RELAY_URL` | Optional loopback Relay URL used by the real-relay integration and stale-client tests. |

Do not place token values in command arguments, checked-in files, or logs.

### V4 Core access

V4 remains unavailable unless `ATLAS_V4_ENABLED=1` is explicitly set. Put the
Android bearer issued for Core in a separate file readable only by the connector
user (mode `0600`), then set `ATLAS_V4_ANDROID_TOKEN_FILE` to its absolute path.
The connector reads this file at startup. A missing file, wrong owner or mode,
symlink, unreadable file, or invalid token contents leaves V4 unavailable and
does not prevent V1/V2/V3 from using `ATLAS_SERVICE_TOKEN`.

The Android bearer is never taken from the service token, Relay pairing token,
or request payload. The relay session authenticates and supplies the paired
device id used for V4 authorization; routes and Core URL remain fixed in the
adapter. This ticket uses one private Android bearer for all authenticated,
paired devices because Core does not yet expose a per-device Android-token
mapping to the connector. The session still verifies each device against the
pairing registry. Remove `ATLAS_V4_ENABLED` (or set it to anything other than
`1`) and restart the connector to roll back: V4 requests then return
`OPERATION_UNAVAILABLE` and make no Core call.

For the systemd user service, set these two non-secret settings in the service
environment, either in the restricted external environment file or in a unit
drop-in using `Environment=`. The env-file allowlist accepts
`ATLAS_V4_ENABLED` and `ATLAS_V4_ANDROID_TOKEN_FILE`. Run
`check-config.mjs --env-file` to validate the configuration and
`check-secret.mjs --env-file` to also require a private Android token file with
mode `0600`; both tools hide token values and paths.

## Pairing and identity

```sh
node dist/main.js show-identity
node dist/main.js pair-device --label "synthetic-device" --token-file "$HOME/.local/state/atlas-relay/device.json" --capabilities sync.status,plan.revision
node dist/main.js revoke-device DEVICE_UUID
```

`show-identity` prints only the X25519 public key and SHA-256 fingerprint.
`pair-device` writes the new 32-byte base64url device token once to the requested
0600 token file; stdout contains only the device id, label, capabilities and
file path. The registry stores only the token SHA-256 hash. The identity and registry files are mode `0600`
inside a mode `0700` state directory. Revocation updates the registry; a
running connector checks it every second and closes the device session with
WebSocket code `4001`.

## Operation boundary

The connector accepts only `atlas.remote.v1` and the operations listed in
`docs/v6-3/r3a/TRANSPORT_PASEO.md`. Backend routes are constants in the
adapter: `GET /health`, `GET /v2/agent/status`, `GET /v1/plan/today`, and
`POST /v3/agent/plan-revision`. It has no URL, path, or command forwarding
field. `plan.revision.result` and `sync.status` are local operations.

`GET /v1/sport/plan` is implemented by FastAPI (`sport-plan-v1`) and is mapped
by the connector only to that fixed route. It does not accept a caller-chosen
URL or path.

Requests are limited to 32 KiB, responses to 256 KiB, the per-device quota is
60 requests per minute with at most four in flight, sessions expire after one
hour, and idempotency results are retained in memory for ten minutes. Logs do
not include tokens, payloads, or backend response bodies.

## Paseo dependency provenance

`@getpaseo/relay` is pinned to 0.13.1 and installed from
`vendor/relay-0.13.1.tgz`. The artifact is the exact npm publication whose
integrity matches the package built from Paseo commit
`902b0c7a1637cc7a6c2ee268dca8161965a35def`; its SHA-256 and regeneration
procedure are recorded in `vendor/relay-0.13.1.tgz.sha256` and
`scripts/build-vendored-relay.mjs`. Only the published package output (`dist/`
and package metadata) is vendored. Runtime imports use the public
`@getpaseo/relay/e2ee` API. The package is licensed Apache-2.0; see the root
`THIRD_PARTY_NOTICES.md`.
