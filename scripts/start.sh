#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BACKEND_DIR="$ROOT_DIR/xiaoda-backend-intelligence/Spring-AI"
FRONTEND_DIR="$ROOT_DIR/smart-agent-frontend"
DRY_RUN=false
MODE=all
PIDS=()
CLEANED=false

usage() {
  cat <<'EOF'
Usage: ./scripts/start.sh [all|backend|frontend]

Starts the Spring AI backend on port 8090 and/or the Vite frontend on port 5174.
Use Ctrl+C to stop every service started by this script.
EOF
}

if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  shift
fi

if [[ $# -gt 0 ]]; then
  MODE=$1
  shift
fi

if [[ $# -gt 0 || "$MODE" == "--help" || "$MODE" == "-h" ]]; then
  usage
  [[ "$MODE" == "--help" || "$MODE" == "-h" ]] && exit 0
  exit 2
fi

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

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

require_free_port() {
  local port=$1
  local service=$2
  if port_in_use "$port"; then
    echo "$service cannot start: port $port is already in use." >&2
    exit 1
  fi
}

cleanup() {
  [[ "$CLEANED" == true ]] && return
  CLEANED=true
  for pid in "${PIDS[@]}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
}

trap cleanup EXIT INT TERM

for service in "${SERVICES[@]}"; do
  case "$service" in
    backend) require_free_port 8090 backend ;;
    frontend) require_free_port 5174 frontend ;;
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
