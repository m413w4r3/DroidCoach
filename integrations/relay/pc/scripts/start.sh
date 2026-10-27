#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -ne 1 ]; then
  echo "usage: start.sh ENV_FILE" >&2
  exit 2
fi
exec node "$script_dir/run-cli.mjs" "$1" start
