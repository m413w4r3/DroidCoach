#!/usr/bin/env bash
set -euo pipefail
umask 077

ATLAS_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ATLAS_PKI_DIR="${HOME}/.local/share/atlas-v63-r3a-plus/pki"
ATLAS_CA_CERT_TARGET="${ATLAS_REPO_ROOT}/android/app/src/debug/res/raw/atlas_test_ca.pem"

mkdir -p "${ATLAS_PKI_DIR}" "$(dirname "${ATLAS_CA_CERT_TARGET}")"
ATLAS_PKI_REAL="$(realpath -m "${ATLAS_PKI_DIR}")"
case "${ATLAS_PKI_REAL}/" in
  "${ATLAS_REPO_ROOT}/"*)
    echo "Refusing to place private test keys inside the repository." >&2
    exit 1
    ;;
esac
if compgen -G "${ATLAS_PKI_DIR}/*" >/dev/null; then
  echo "Refusing to overwrite an existing PKI directory; preserve its keys and choose a fresh test directory." >&2
  exit 1
fi

openssl genrsa -out "${ATLAS_PKI_DIR}/ca-key.pem" 3072 >/dev/null
openssl req -x509 -new -sha256 -days 825 \
  -key "${ATLAS_PKI_DIR}/ca-key.pem" \
  -out "${ATLAS_PKI_DIR}/ca-cert.pem" \
  -subj "/CN=ATLAS-V6.3-R3Aplus-Local-Test-CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" >/dev/null

cat >"${ATLAS_PKI_DIR}/server.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:relay.atlas.test
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF
cat >"${ATLAS_PKI_DIR}/wrong-host.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:wrong-host.atlas.test
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF
cat >"${ATLAS_PKI_DIR}/untrusted.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:relay.atlas.test
subjectKeyIdentifier=hash
EOF

openssl genrsa -out "${ATLAS_PKI_DIR}/relay-key.pem" 2048 >/dev/null
openssl req -new -sha256 -key "${ATLAS_PKI_DIR}/relay-key.pem" \
  -out "${ATLAS_PKI_DIR}/relay.csr" -subj "/CN=relay.atlas.test" >/dev/null
openssl x509 -req -sha256 -days 30 -in "${ATLAS_PKI_DIR}/relay.csr" \
  -CA "${ATLAS_PKI_DIR}/ca-cert.pem" -CAkey "${ATLAS_PKI_DIR}/ca-key.pem" \
  -CAcreateserial -out "${ATLAS_PKI_DIR}/relay-cert.pem" \
  -extfile "${ATLAS_PKI_DIR}/server.ext" >/dev/null

openssl genrsa -out "${ATLAS_PKI_DIR}/wrong-host-key.pem" 2048 >/dev/null
openssl req -new -sha256 -key "${ATLAS_PKI_DIR}/wrong-host-key.pem" \
  -out "${ATLAS_PKI_DIR}/wrong-host.csr" -subj "/CN=wrong-host.atlas.test" >/dev/null
openssl x509 -req -sha256 -days 30 -in "${ATLAS_PKI_DIR}/wrong-host.csr" \
  -CA "${ATLAS_PKI_DIR}/ca-cert.pem" -CAkey "${ATLAS_PKI_DIR}/ca-key.pem" \
  -CAserial "${ATLAS_PKI_DIR}/ca-cert.srl" -out "${ATLAS_PKI_DIR}/wrong-host-cert.pem" \
  -extfile "${ATLAS_PKI_DIR}/wrong-host.ext" >/dev/null

openssl genrsa -out "${ATLAS_PKI_DIR}/untrusted-key.pem" 2048 >/dev/null
openssl req -new -sha256 -key "${ATLAS_PKI_DIR}/untrusted-key.pem" \
  -out "${ATLAS_PKI_DIR}/untrusted.csr" -subj "/CN=relay.atlas.test" >/dev/null
openssl x509 -req -sha256 -days 30 -in "${ATLAS_PKI_DIR}/untrusted.csr" \
  -signkey "${ATLAS_PKI_DIR}/untrusted-key.pem" \
  -out "${ATLAS_PKI_DIR}/untrusted-cert.pem" \
  -extfile "${ATLAS_PKI_DIR}/untrusted.ext" >/dev/null

chmod 600 "${ATLAS_PKI_DIR}"/*-key.pem
install -m 0644 "${ATLAS_PKI_DIR}/ca-cert.pem" "${ATLAS_CA_CERT_TARGET}"
echo "Generated local TLS test certificates; private keys remain under ~/.local/share/atlas-v63-r3a-plus/pki/."
