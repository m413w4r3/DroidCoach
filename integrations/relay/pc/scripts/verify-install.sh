#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
package_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
node_major=$(node -p 'Number(process.versions.node.split(".")[0])')
if [ "$node_major" -lt 24 ]; then
  echo "FAIL Node 24 or newer is required" >&2
  exit 1
fi
cd "$package_dir"
node scripts/verify-artifact.mjs
npm ci
npm run build
node --test scripts/lib/config-policy.check.mjs
if node --input-type=module -e 'try { const response = await fetch("http://127.0.0.1:4400/health", { signal: AbortSignal.timeout(900) }); process.exit(response.ok ? 0 : 1); } catch { process.exit(1); }'; then
  echo "INFO local Relay health endpoint found; running full npm test suite"
  npm test
else
  echo "INFO local Relay health endpoint absent; running relay-independent test subset"
  npm run test:unit
fi
