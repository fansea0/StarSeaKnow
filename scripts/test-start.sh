#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

assert_plan() {
  local mode=$1
  local expected=$2
  local output
  output=$("$ROOT_DIR/scripts/start.sh" --dry-run "$mode")
  [[ "$output" == "$expected" ]]
}

assert_plan all $'backend\nfrontend'
assert_plan backend 'backend'
assert_plan frontend 'frontend'

if "$ROOT_DIR/scripts/start.sh" --dry-run unknown >/dev/null 2>&1; then
  echo "unknown mode should fail" >&2
  exit 1
fi

echo "start script tests passed"
