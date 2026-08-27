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
keep_output=$("$ROOT_DIR/scripts/start.sh" --dry-run --keep-existing backend)
[[ "$keep_output" == 'backend' ]]

if "$ROOT_DIR/scripts/start.sh" --dry-run unknown >/dev/null 2>&1; then
  echo "unknown mode should fail" >&2
  exit 1
fi

fake_bin=$(mktemp -d)
trap 'rm -rf "$fake_bin"' EXIT
export FAKE_STATE_DIR="$fake_bin/state"
mkdir -p "$FAKE_STATE_DIR"
sleep 60 &
fake_port_pid=$!
export FAKE_PORT_PID="$fake_port_pid"

cat >"$fake_bin/lsof" <<'EOF'
#!/usr/bin/env bash
if [[ ! -f "$FAKE_STATE_DIR/seen" ]]; then
  touch "$FAKE_STATE_DIR/seen"
  echo "$FAKE_PORT_PID"
  exit 0
fi
exit 1
EOF
cat >"$fake_bin/mvn" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$fake_bin/lsof" "$fake_bin/mvn"

PATH="$fake_bin:$PATH" "$ROOT_DIR/scripts/start.sh" backend >/dev/null
if kill -0 "$fake_port_pid" >/dev/null 2>&1; then
  echo "occupied-port process should be stopped" >&2
  exit 1
fi
wait "$fake_port_pid" 2>/dev/null || true

echo "start script tests passed"
