# Local relay TLS/WSS harness

This harness terminates TLS on loopback and forwards the decrypted WebSocket
stream to the unmodified Paseo Relay at `ws://127.0.0.1:4400`. It does not
provide an HTTP proxy, edit `/etc/hosts`, or change system DNS.

## Generate local certificates

Run from the repository root:

```sh
./tools/relay-tls-test/generate-pki.sh
```

Private keys and leaf certificates are created under
`~/.local/share/atlas-v63-r3a-plus/pki/`. The only generated file copied into
the repository is the public CA certificate at
`android/app/src/debug/res/raw/atlas_test_ca.pem`. The script refuses to put
private keys under the repository.

The CA signs a server certificate with `DNS:relay.atlas.test` and a wrong-host
certificate with `DNS:wrong-host.atlas.test`. A self-signed server certificate
for `relay.atlas.test` provides the untrusted-chain case. The client always
performs normal certificate and hostname verification.

## Start the real local relay

In a separate terminal, start the already-built Paseo Relay image. This runs
the relay without changing its source or image:

```sh
docker run --rm --name atlas-r3a-plus-wss-relay \
  -p 127.0.0.1:4400:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 \
  -e PASEO_RELAY_PORT=4000 \
  atlas-paseo-relay:r3a
```

The harness requires `GET http://127.0.0.1:4400/health` to return 200 and
requires `127.0.0.1:8100` to be free so it can bind a temporary FastAPI reachability
sentinel. It does not start FastAPI or send requests to an existing API.

## Run the WSS checks

From the repository root:

```sh
(cd integrations/relay/pc && npm ci && npm run build)
node tools/relay-tls-test/verify-wss.mjs
```

The script starts three loopback-only TLS proxies on ephemeral ports. Each
proxy forwards only to `127.0.0.1:4400`. A test-only Node `lookup` callback maps
the three `.atlas.test` names to `127.0.0.1`; no hostname or system resolver
configuration changes. The check covers explicit test-CA trust, wrong-host
and untrusted certificate rejection, remote `ws://` refusal, interruption and
reconnection, and a plaintext HTTP request that cannot reach the temporary
listener on `127.0.0.1:8100`.

The proxy's `SIGUSR1` handler interrupts active TLS streams while leaving the
listener up, so the reconnect check uses the same WSS endpoint. The Node client
does not use `rejectUnauthorized: false` or any other TLS verification bypass.

## Android debug configuration

`src/debug/AndroidManifest.xml` selects the debug-only network security config.
That config keeps system trust and adds the public test CA; release resources
do not include it. `RelayDebugDns` provides explicit loopback answers for
`relay.atlas.test`, `wrong-host.atlas.test`, and `untrusted.atlas.test`. A debug
instrumentation test can inject it using
`OkHttpRelaySocketFactory(config, RelayDebugDns)`. The factory ignores injected
DNS in a release build, and release has no `RelayDebugDns` class to inject.

`PaseoRelayClientTest` also checks that a responder with a different pinned
Noise static key is rejected before a protected channel is established.
