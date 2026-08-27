#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BACKEND_DIR="$ROOT_DIR/xiaoda-backend-intelligence/Spring-AI"
FRONTEND_DIR="$ROOT_DIR/smart-agent-frontend"
DRY_RUN=false
KEEP_EXISTING=false
MODE=all
PIDS=()
CLEANED=false

usage() {
  cat <<'EOF'
Usage: ./scripts/start.sh [--keep-existing] [all|backend|frontend]

Starts the Spring AI backend on port 8090 and/or the Vite frontend on port 5174.
If a required port is occupied, the listening process is stopped before startup.
Use --keep-existing to fail instead of stopping an existing process.
Use Ctrl+C to stop every service started by this script.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true ;;
    --keep-existing) KEEP_EXISTING=true ;;
    --help|-h)
      usage
      exit 0
      ;;
    all|backend|frontend)
      MODE=$1
      ;;
    *)
      echo "Unknown service mode: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

case "$MODE" in
  all) SERVICES=(backend frontend) ;;
  backend|frontend) SERVICES=("$MODE") ;;
  *)
    echo "Unknown service mode: $MODE" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ "$DRY_RUN" == true ]]; then
  printf '%s\n' "${SERVICES[@]}"
  exit 0
fi

port_pids() {
  lsof -t -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null || true
}

release_port() {
  local port=$1
  local service=$2
  local pids
  pids=$(port_pids "$port")
  [[ -z "$pids" ]] && return

  if [[ "$KEEP_EXISTING" == true ]]; then
    echo "$service cannot start: port $port is already in use by PID(s): $pids" >&2
    exit 1
  fi

  echo "Stopping process(es) on port $port: $pids"
  for pid in $pids; do
    kill "$pid"
  done

  for _ in {1..20}; do
    [[ -z "$(port_pids "$port")" ]] && return
    sleep 0.25
  done

  echo "$service could not start: port $port is still in use." >&2
  exit 1
}

cleanup() {
  [[ "$CLEANED" == true ]] && return
  CLEANED=true
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
}

trap cleanup EXIT INT TERM

for service in "${SERVICES[@]}"; do
  case "$service" in
    backend) release_port 8090 backend ;;
    frontend) release_port 5174 frontend ;;
  esac
done

for service in "${SERVICES[@]}"; do
  case "$service" in
    backend)
      echo "Starting backend at http://localhost:8090"
      (cd "$BACKEND_DIR" && mvn spring-boot:run) &
      PIDS+=("$!")
      ;;
    frontend)
      echo "Starting frontend at http://127.0.0.1:5174"
      (cd "$FRONTEND_DIR" && npm run dev -- --host 127.0.0.1 --port 5174) &
      PIDS+=("$!")
      ;;
  esac
done

wait "${PIDS[@]}"
