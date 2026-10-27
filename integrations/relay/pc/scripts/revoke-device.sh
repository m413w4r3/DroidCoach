#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -ne 2 ]; then
  echo "usage: revoke-device.sh ENV_FILE DEVICE_UUID" >&2
  exit 2
fi
exec node "$script_dir/run-cli.mjs" "$1" revoke-device "$2"
