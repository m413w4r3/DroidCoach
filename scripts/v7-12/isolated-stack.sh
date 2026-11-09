#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
COMPOSE_FILE="$ROOT/compose.isolated.yaml"
PROJECT=atlas-core-isolated
VOLUME=atlas-core-isolated-data
STACK_TMP=""
STACK_STARTED=0
VOLUME_CREATED=0
CORE_IMAGE="atlas-core-v7-12:local"

compose() {
  docker compose --env-file "$STACK_TMP/stack.env" -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

say() {
  printf '%s\n' "$*"
}

fail() {
  printf 'isolated-stack: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  local labels
  if (( STACK_STARTED == 1 )); then
    compose down --remove-orphans >/dev/null 2>&1 || true
  fi
  if (( VOLUME_CREATED == 1 )) && docker volume inspect "$VOLUME" >/dev/null 2>&1; then
    labels="$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.volume"}}' "$VOLUME" 2>/dev/null || true)"
    if [[ "$labels" == "$PROJECT|$VOLUME" ]]; then
      docker volume rm "$VOLUME" >/dev/null 2>&1 || true
    fi
  fi
  if [[ -n "$STACK_TMP" && -d "$STACK_TMP" ]]; then
    if docker image inspect "$CORE_IMAGE" >/dev/null 2>&1; then
      docker run --rm --user 0 --entrypoint python -v "$STACK_TMP:/cleanup" \
        -e CLEANUP_UID="$(id -u)" -e CLEANUP_GID="$(id -g)" "$CORE_IMAGE" \
        -c 'import os; root="/cleanup"; uid=int(os.environ["CLEANUP_UID"]); gid=int(os.environ["CLEANUP_GID"]); [(os.chown(os.path.join(p,n),uid,gid),os.chmod(os.path.join(p,n),0o700 if os.path.isdir(os.path.join(p,n)) else 0o600)) for p,ds,fs in os.walk(root) for n in ds+fs if not os.path.islink(os.path.join(p,n))]; os.chown(root,uid,gid); os.chmod(root,0o700)' \
        >/dev/null 2>&1 || true
    fi
    rm -rf -- "$STACK_TMP"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for required in \
  "$ROOT/deploy/relay/Dockerfile" \
  "$ROOT/integrations/relay/proxy/Dockerfile" \
  "$ROOT/integrations/relay/pc/Dockerfile"; do
  [[ -f "$required" ]] || fail "BLOCKED: full isolated verification awaits A2/A3 build inputs; no live stack was touched"
done

command -v docker >/dev/null 2>&1 || fail "Docker is unavailable"
docker version >/dev/null 2>&1 || fail "Docker daemon is unavailable"
docker compose version >/dev/null 2>&1 || fail "Docker Compose is unavailable"

if compose ps -aq 2>/dev/null | grep -q .; then
  fail "isolated Compose project already has containers; it was left untouched"
fi
if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
  fail "reserved isolated volume already exists; it was left untouched"
fi

STACK_TMP="$(mktemp -d /tmp/atlas-isolated.XXXXXX)"
chmod 0700 "$STACK_TMP"
mkdir -m 0700 "$STACK_TMP/secrets-core" "$STACK_TMP/connector-state" \
  "$STACK_TMP/openclaw-state" "$STACK_TMP/openclaw-update-captures" "$STACK_TMP/backups"

python3 - "$STACK_TMP" <<'PY'
import base64
import json
import os
from pathlib import Path
import secrets
import sys

root = Path(sys.argv[1])
core = root / "secrets-core"
state = root / "connector-state"
state_v712 = state / "v7-12"
openclaw = root / "openclaw-state"
captures = root / "openclaw-update-captures"
state_v712.mkdir(mode=0o700)

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

def token() -> str:
    return "isolated_" + secrets.token_urlsafe(40)

def write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())

key_id = "isolated-key"
relay_secret = secrets.token_bytes(32)
cursor_secret = b64(secrets.token_bytes(32))
relay_credential = token()
gateway_token = token()
principal_id = "isolated-principal"
device_ref = "isolated-device"
relay_device_id = "isolated-relay-device"
credential_id = "isolated-credential"

for name in ("atlas-token", "atlas-tool-token"):
    write_private(core / name, (token() + "\n").encode("ascii"))
write_private(core / "openclaw-gateway-token", (gateway_token + "\n").encode("ascii"))
write_private(core / "relay-assertion-keys.json",
              (json.dumps({key_id: b64(relay_secret)}, separators=(",", ":")) + "\n").encode("ascii"))
write_private(core / "cursor-secret", cursor_secret.encode("ascii"))

assertion_secret = (b64(relay_secret) + "\n").encode("ascii")
service_token = secrets.token_hex(32).encode("ascii")
principal_map = (json.dumps({
    relay_device_id: {
        "principal_id": principal_id,
        "credential_file": "/state/v7-12/relay-credential",
    }
}, separators=(",", ":")) + "\n").encode("ascii")
write_private(state_v712 / "relay-assertion-secret", assertion_secret)
write_private(state_v712 / "service-token", service_token)
write_private(state_v712 / "principal-map.json", principal_map)
write_private(state_v712 / "relay-credential", (relay_credential + "\n").encode("ascii"))
write_private(state / "android-token", (token() + "\n").encode("ascii"))
write_private(state / "pixel-v7.token", (token() + "\n").encode("ascii"))
write_private(state / "pixel-v7-pairing.json", b'{"synthetic":true}\n')

for directory in ("state", "agents", "workspaces", "workspace", "secrets", "extensions", "plugin-sources",
                  "cache", "tmp", "npm", "completions", "backups"):
    (openclaw / directory).mkdir(mode=0o700)
openclaw_config = {
    "gateway": {
        "mode": "local",
        "bind": "lan",
        "auth": {"mode": "token", "token": gateway_token},
    },
}
write_private(openclaw / "openclaw.json", (json.dumps(openclaw_config, separators=(",", ":")) + "\n").encode("utf-8"))
for name in ("cache", "tmp", "npm", "completions"):
    write_private(openclaw / name / "isolated-marker", b"synthetic\n")

seed = {
    "principal_id": principal_id,
    "device_ref": device_ref,
    "relay_device_id": relay_device_id,
    "credential_id": credential_id,
    "relay_credential": relay_credential,
    "relay_secret": b64(relay_secret),
    "key_id": key_id,
}
write_private(root / "seed.json", (json.dumps(seed, separators=(",", ":")) + "\n").encode("ascii"))

values = {
    "ATLAS_IMAGE_TAG": "local",
    "ATLAS_TOKEN_FILE": str(core / "atlas-token"),
    "ATLAS_TOOL_TOKEN_FILE": str(core / "atlas-tool-token"),
    "OPENCLAW_GATEWAY_TOKEN_FILE": str(core / "openclaw-gateway-token"),
    "ATLAS_RELAY_ASSERTION_KEYS_FILE": str(core / "relay-assertion-keys.json"),
    "CORE_CURSOR_SECRET_FILE": str(core / "cursor-secret"),
    "CONNECTOR_ASSERTION_SECRET_FILE": str(state_v712 / "relay-assertion-secret"),
    "CONNECTOR_PRINCIPAL_MAP_FILE": str(state_v712 / "principal-map.json"),
    "CONNECTOR_SERVICE_TOKEN_FILE": str(state_v712 / "service-token"),
    "ATLAS_V4_RELAY_ASSERTION_KEY_ID": key_id,
    "ATLAS_CONNECTOR_STATE_DIR": str(state),
    "OPENCLAW_STATE_DIR": str(openclaw),
    "OPENCLAW_UPDATE_CAPTURES_DIR": str(captures),
    "ATLAS_BACKUP_DIR": str(root / "backups"),
    "ATLAS_BACKUP_REQUIRE_ZFS_ENCRYPTION": "0",
    "ATLAS_V4_AUTH_MODE": "dual",
    "ATLAS_V4_CONNECTOR_AUTH_MODE": "dual",
    "ATLAS_V4_TOOL_PRINCIPAL_ID": "",
}
env_file = root / "stack.env"
with env_file.open("x", encoding="utf-8") as output:
    for key, value in values.items():
        output.write(f"{key}={value}\n")
os.chmod(env_file, 0o600)
PY

say "isolated test materials generated outside the repository"
compose build atlas-core atlas-relay atlas-proxy atlas-connector

for image in \
  atlas-relay-v7-12:local \
  atlas-proxy-v7-12:local \
  atlas-connector-v7-12:local \
  ghcr.io/openclaw/openclaw@sha256:f40d1e66fd14d8c5b49c832f4100f83b53fdc01f5108f7db11de38fd0ee6dbe5; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    fail "required isolated test image is unavailable: ${image%%@*}"
  fi
done

# Local file-backed Compose secrets need the same consumer UID as the container.
docker run --rm --user 0 --entrypoint python \
  -v "$STACK_TMP/secrets-core:/secret-core" \
  -v "$STACK_TMP/connector-state:/connector-state" \
  -v "$STACK_TMP/openclaw-state:/openclaw-state" \
  -v "$STACK_TMP/openclaw-update-captures:/update-captures" \
  "$CORE_IMAGE" -c 'import os; paths=[("/secret-core",1000),("/connector-state",1000),("/openclaw-state",1000),("/update-captures",1000)]; [(os.chown(p,u,u),os.chmod(p,0o700)) for p,u in paths]; owners=[("/secret-core",10001),("/connector-state",1000),("/openclaw-state",1000),("/update-captures",1000)]; [(os.chown(os.path.join(r,n),u,u),os.chmod(os.path.join(r,n),0o700 if os.path.isdir(os.path.join(r,n)) else 0o600)) for p,u in owners for r,ds,fs in os.walk(p) for n in ds+fs if not os.path.islink(os.path.join(r,n))]'

export ATLAS_STACK_ENV_FILE="$STACK_TMP/stack.env"
export ATLAS_STACK_COMPOSE_FILE="$COMPOSE_FILE"
export ATLAS_STACK_PROJECT="$PROJECT"
export ATLAS_STACK_BUILD_IMAGES=0
STACK_STARTED=1
VOLUME_CREATED=1
"$ROOT/scripts/atlas-stack" up
"$ROOT/scripts/atlas-stack" doctor
"$ROOT/scripts/atlas-stack" status

python3 - <<'PY'
import json
from urllib.request import urlopen
with urlopen("http://127.0.0.1:18100/health", timeout=5) as response:
    if response.status != 200:
        raise SystemExit("isolated Core health failed")
    payload = json.load(response)
if payload.get("status") != "ok":
    raise SystemExit("isolated Core health payload failed")
print("core_health=PASS mock_adapter=true")
PY

CORE_ID="$(compose ps -q atlas-core)"
[[ -n "$CORE_ID" ]] || fail "isolated Core container is missing"
SEED_CODE='import json,sys; from atlas_backend.store import SQLiteStore; from atlas_backend.v4_auth import V4PrincipalRegistry; d=json.load(sys.stdin); r=V4PrincipalRegistry(SQLiteStore("/data/atlas.sqlite3")); r.create_principal(principal_id=d["principal_id"],device_ref=d["device_ref"]); r.add_credential(d["principal_id"],d["relay_credential"],audience="RELAY",credential_id=d["credential_id"]); r.set_capability(d["principal_id"],"consent.state.write",True); r.add_binding(d["relay_device_id"],d["principal_id"]); print("synthetic_registry_seeded")'
docker exec -i --user 0 -w /app/backend "$CORE_ID" python -c "$SEED_CODE" < "$STACK_TMP/seed.json"

python3 - "$STACK_TMP/seed.json" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, urlopen

data = json.loads(open(sys.argv[1], encoding="ascii").read())
secret = base64.urlsafe_b64decode(data["relay_secret"] + "=" * (-len(data["relay_secret"]) % 4))
path = "/v4/sync/consent-state"

def request(version: int, nonce: str, *, body_bytes: bytes | None = None, bad_signature: bool = False,
            fixed_headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], bytes]:
    now = str(int(time.time() * 1000))
    payload = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": str(uuid.uuid4()),
        "capability": "consent.state.write",
        "sent_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "category": "DAY_PLAN",
        "consent_version": version,
        "state": "GRANTED",
        "idempotency_key": str(uuid.uuid4()),
    }
    body = body_bytes if body_bytes is not None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    canonical = f"v1\nPOST\n{path}\n{now}\n{nonce}\n{data['relay_device_id']}\n{hashlib.sha256(body).hexdigest()}".encode("utf-8")
    signature = "v1=" + base64.urlsafe_b64encode(hmac.new(secret, canonical, hashlib.sha256).digest()).decode("ascii").rstrip("=")
    headers = {
        "Authorization": "Bearer " + data["relay_credential"],
        "Content-Type": "application/json",
        "X-Atlas-Relay-Device-Id": data["relay_device_id"],
        "X-Atlas-Relay-Key-Id": data["key_id"],
        "X-Atlas-Relay-Time": now,
        "X-Atlas-Relay-Nonce": nonce,
        "X-Atlas-Relay-Signature": "v1=invalid" if bad_signature else signature,
    }
    if fixed_headers is not None:
        headers = fixed_headers
    try:
        with urlopen(Request("http://127.0.0.1:18100" + path, data=body, headers=headers, method="POST"), timeout=8) as response:
            return response.status, headers, body
    except HTTPError as error:
        return error.code, headers, body

code, headers, body = request(1, "isolated-valid-nonce-1")
if code != 200:
    raise SystemExit(f"HMAC acceptance failed (HTTP {code})")
print("hmac_acceptance=PASS")

replay_code, _, _ = request(1, "isolated-valid-nonce-1", body_bytes=body, fixed_headers=headers)
if replay_code != 401:
    raise SystemExit(f"HMAC replay rejection failed (HTTP {replay_code})")
print("hmac_replay_rejection=PASS")

bad_code, _, _ = request(1, "isolated-invalid-nonce", bad_signature=True)
if bad_code != 401:
    raise SystemExit(f"invalid HMAC rejection failed (HTTP {bad_code})")
print("hmac_invalid_signature=PASS")
PY

compose restart atlas-core >/dev/null
deadline=$((SECONDS + 120))
until [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$CORE_ID" 2>/dev/null || true)" == healthy ]]; do
  (( SECONDS < deadline )) || fail "isolated Core did not recover after restart"
  sleep 2
done
say "core_restart=PASS"

python3 - "$STACK_TMP/seed.json" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, urlopen

data = json.loads(open(sys.argv[1], encoding="ascii").read())
secret = base64.urlsafe_b64decode(data["relay_secret"] + "=" * (-len(data["relay_secret"]) % 4))
path = "/v4/sync/consent-state"

def send(version, nonce):
    body = json.dumps({
        "contract_version": 4, "schema_version": 4, "request_id": str(uuid.uuid4()),
        "capability": "consent.state.write",
        "sent_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "category": "DAY_PLAN", "consent_version": version, "state": "GRANTED",
        "idempotency_key": str(uuid.uuid4()),
    }, separators=(",", ":")).encode("utf-8")
    timestamp = str(int(time.time() * 1000))
    canonical = f"v1\nPOST\n{path}\n{timestamp}\n{nonce}\n{data['relay_device_id']}\n{hashlib.sha256(body).hexdigest()}".encode("utf-8")
    signature = "v1=" + base64.urlsafe_b64encode(hmac.new(secret, canonical, hashlib.sha256).digest()).decode("ascii").rstrip("=")
    headers = {
        "Authorization": "Bearer " + data["relay_credential"], "Content-Type": "application/json",
        "X-Atlas-Relay-Device-Id": data["relay_device_id"], "X-Atlas-Relay-Key-Id": data["key_id"],
        "X-Atlas-Relay-Time": timestamp, "X-Atlas-Relay-Nonce": nonce,
        "X-Atlas-Relay-Signature": signature,
    }
    try:
        with urlopen(Request("http://127.0.0.1:18100" + path, data=body, headers=headers, method="POST"), timeout=8) as response:
            return response.status
    except HTTPError as error:
        return error.code

if send(2, "isolated-after-restart-nonce") != 200:
    raise SystemExit("Core registry or volume persistence failed")
print("core_volume_persistence=PASS")
PY

REVOKE_CODE='import json,sys; from atlas_backend.store import SQLiteStore; from atlas_backend.v4_auth import V4PrincipalRegistry; d=json.load(sys.stdin); assert V4PrincipalRegistry(SQLiteStore("/data/atlas.sqlite3")).revoke_credential(d["credential_id"]); print("synthetic_credential_revoked")'
docker exec -i --user 0 -w /app/backend "$CORE_ID" python -c "$REVOKE_CODE" < "$STACK_TMP/seed.json"

python3 - "$STACK_TMP/seed.json" <<'PY'
import base64,hashlib,hmac,json,sys,time,uuid
from datetime import datetime,timezone
from urllib.error import HTTPError
from urllib.request import Request,urlopen
d=json.loads(open(sys.argv[1],encoding="ascii").read())
s=base64.urlsafe_b64decode(d["relay_secret"]+"="*(-len(d["relay_secret"])%4))
p="/v4/sync/consent-state"
body=json.dumps({"contract_version":4,"schema_version":4,"request_id":str(uuid.uuid4()),"capability":"consent.state.write","sent_at":datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00","Z"),"category":"DAY_PLAN","consent_version":3,"state":"GRANTED","idempotency_key":str(uuid.uuid4())},separators=(",",":")).encode()
t=str(int(time.time()*1000)); n="isolated-after-revocation-nonce"
c=f"v1\nPOST\n{p}\n{t}\n{n}\n{d['relay_device_id']}\n{hashlib.sha256(body).hexdigest()}".encode()
sig="v1="+base64.urlsafe_b64encode(hmac.new(s,c,hashlib.sha256).digest()).decode().rstrip("=")
h={"Authorization":"Bearer "+d["relay_credential"],"Content-Type":"application/json","X-Atlas-Relay-Device-Id":d["relay_device_id"],"X-Atlas-Relay-Key-Id":d["key_id"],"X-Atlas-Relay-Time":t,"X-Atlas-Relay-Nonce":n,"X-Atlas-Relay-Signature":sig}
try:
    with urlopen(Request("http://127.0.0.1:18100"+p,data=body,headers=h,method="POST"),timeout=8) as r: code=r.status
except HTTPError as e: code=e.code
if code!=401: raise SystemExit(f"revoked credential rejection failed (HTTP {code})")
print("credential_revocation=PASS")
PY

identity_hash() {
  docker run --rm --user 0 --entrypoint python \
    -v "$STACK_TMP/connector-state:/state:ro" "$CORE_IMAGE" \
    -c 'import hashlib,pathlib,sys; p=pathlib.Path("/state/identity.json"); assert p.is_file(); assert all((p.parent/n).is_file() for n in ("devices.json","operations.json")); print(hashlib.sha256(p.read_bytes()).hexdigest())'
}
IDENTITY_BEFORE="$(identity_hash)"
compose restart atlas-connector >/dev/null
deadline=$((SECONDS + 120))
until [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$(compose ps -q atlas-connector)" 2>/dev/null || true)" == healthy ]]; do
  (( SECONDS < deadline )) || fail "isolated connector did not recover after restart"
  sleep 2
done
IDENTITY_AFTER="$(identity_hash)"
[[ "$IDENTITY_BEFORE" == "$IDENTITY_AFTER" ]] || fail "connector identity changed after restart"
say "connector_state_persistence=PASS identity_devices_operations=present"

mkdir -m 0700 "$STACK_TMP/backups/atlas-backup-20200101T000000Z-deadbeef" \
  "$STACK_TMP/backups/operator-note"
printf 'synthetic\n' > "$STACK_TMP/backups/atlas-backup-20200101T000000Z-deadbeef/old"
chmod 0600 "$STACK_TMP/backups/atlas-backup-20200101T000000Z-deadbeef/old"
touch -d '30 days ago' "$STACK_TMP/backups/atlas-backup-20200101T000000Z-deadbeef"
"$ROOT/scripts/atlas-stack" backup
[[ ! -e "$STACK_TMP/backups/atlas-backup-20200101T000000Z-deadbeef" ]] || fail "seven-day backup rotation did not remove the expired synthetic backup"
[[ -d "$STACK_TMP/backups/operator-note" ]] || fail "backup rotation removed an unrelated directory"
BACKUP_DIR="$(find "$STACK_TMP/backups" -mindepth 1 -maxdepth 1 -type d -name 'atlas-backup-*' -print -quit)"
[[ -n "$BACKUP_DIR" ]] || fail "new isolated backup was not created"
"$ROOT/scripts/v7-12/restore-check.py" "$BACKUP_DIR" --restore-to "$STACK_TMP/restored"
say "backup_restore=PASS integrity=ok foreign_key_check=empty manifest=verified rotation=7d"

compose down --remove-orphans
STACK_STARTED=0
say "isolated_stack=PASS stopped project=$PROJECT"
