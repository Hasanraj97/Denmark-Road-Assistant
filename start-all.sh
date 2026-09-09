#!/usr/bin/env bash
#
# Start all three Denmark Road Assistant services, in order, and stop them all
# cleanly when you press Ctrl+C.
#
#   ./start-all.sh
#
# Each service is started only once the one before it is answering, so you never
# get the confusing situation where the frontend loads but the backend is not
# ready yet.
#
# Works in Git Bash on Windows, and in bash/zsh on macOS and Linux.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

ML_PORT=8000
BACKEND_PORT=8080
FRONTEND_PORT=4200

PIDS=()

# --- pretty output ---------------------------------------------------------
if [ -t 1 ]; then
  BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
else
  BOLD=""; GREEN=""; RED=""; YELLOW=""; DIM=""; OFF=""
fi

say()  { printf "%s\n" "${BOLD}$*${OFF}"; }
ok()   { printf "%s\n" "  ${GREEN}OK${OFF}  $*"; }
warn() { printf "%s\n" "  ${YELLOW}!!${OFF}  $*"; }
die()  { printf "%s\n" "  ${RED}XX${OFF}  $*" >&2; exit 1; }

# --- shutdown --------------------------------------------------------------
cleanup() {
  printf "\n%s\n" "${BOLD}Shutting down...${OFF}"
  for pid in "${PIDS[@]:-}"; do
    if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
      # Kill the whole process tree. Maven and ng each spawn children that
      # would otherwise survive and keep the port bound.
      if command -v taskkill >/dev/null 2>&1; then
        taskkill //F //T //PID "$pid" >/dev/null 2>&1
      else
        pkill -TERM -P "$pid" 2>/dev/null
        kill -TERM "$pid" 2>/dev/null
      fi
    fi
  done
  sleep 1
  printf "%s\n" "Stopped. Logs are in ${DIM}logs/${OFF}"
}
trap cleanup EXIT INT TERM

# --- helpers ---------------------------------------------------------------
port_in_use() {
  local port="$1"
  if command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E "[:.]$port[[:space:]]" | grep -qiE "listen"
  else
    lsof -i ":$port" >/dev/null 2>&1
  fi
}

require_port_free() {
  local port="$1" name="$2"
  if port_in_use "$port"; then
    printf "%s\n" "  ${RED}XX${OFF}  Port ${BOLD}$port${OFF} is already in use, so $name cannot start." >&2
    printf "%s\n" "      Something is already listening there - most likely a copy of this" >&2
    printf "%s\n" "      project you started earlier and did not stop." >&2
    printf "%s\n" "" >&2
    if command -v netstat >/dev/null 2>&1; then
      printf "%s\n" "      Find it with:   netstat -ano | grep \":$port \"" >&2
      printf "%s\n" "      Then stop it:   taskkill //F //PID <the last number on that line>" >&2
    else
      printf "%s\n" "      Find it with:   lsof -i :$port" >&2
      printf "%s\n" "      Then stop it:   kill <the PID it shows>" >&2
    fi
    exit 1
  fi
}

wait_for_http() {
  local url="$1" name="$2" seconds="${3:-90}" logfile="$4"
  local waited=0
  printf "  ..  waiting for %s" "$name"
  while [ "$waited" -lt "$seconds" ]; do
    if curl -sf --max-time 3 "$url" >/dev/null 2>&1; then
      printf "\r"; ok "$name is up  ${DIM}($url)${OFF}    "
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
    printf "."
  done
  printf "\r"
  printf "%s\n" "  ${RED}XX${OFF}  $name did not become healthy within ${seconds}s." >&2
  printf "%s\n" "      Last 25 lines of $logfile:" >&2
  tail -25 "$logfile" >&2
  exit 1
}

# --- locate the python virtual environment ---------------------------------
if [ -x "$ROOT/ml-service/.venv/Scripts/python.exe" ]; then
  VENV_PY="$ROOT/ml-service/.venv/Scripts/python.exe"      # Windows layout
elif [ -x "$ROOT/ml-service/.venv/bin/python" ]; then
  VENV_PY="$ROOT/ml-service/.venv/bin/python"              # macOS / Linux layout
else
  die "No Python virtual environment found at ml-service/.venv
      Create it first (see README section 2):
        cd ml-service && py -3.12 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r requirements.txt"
fi

[ -d "$ROOT/frontend/node_modules" ] || die "frontend/node_modules is missing. Run:  cd frontend && npm ci"

say ""
say "Denmark Road Assistant"
say "======================"
say ""

require_port_free "$ML_PORT"       "the ML service"
require_port_free "$BACKEND_PORT"  "the backend"
require_port_free "$FRONTEND_PORT" "the frontend"

# --- 1. ML service ---------------------------------------------------------
say "1/3  ML service (port $ML_PORT)"
printf "%s\n" "     ${DIM}first start trains the model - this takes a few seconds${OFF}"
( cd "$ROOT/ml-service" && exec "$VENV_PY" -m uvicorn app.main:app --host 127.0.0.1 --port "$ML_PORT" ) \
  > "$LOG_DIR/ml-service.log" 2>&1 &
PIDS+=($!)
wait_for_http "http://127.0.0.1:$ML_PORT/health" "ML service" 120 "$LOG_DIR/ml-service.log"

# --- 2. Backend ------------------------------------------------------------
say "2/3  Backend (port $BACKEND_PORT)"
( cd "$ROOT/backend" && exec ./mvnw -q spring-boot:run ) > "$LOG_DIR/backend.log" 2>&1 &
PIDS+=($!)
wait_for_http "http://127.0.0.1:$BACKEND_PORT/api/health" "Backend" 180 "$LOG_DIR/backend.log"

# --- 3. Frontend -----------------------------------------------------------
say "3/3  Frontend (port $FRONTEND_PORT)"
( cd "$ROOT/frontend" && exec npm start ) > "$LOG_DIR/frontend.log" 2>&1 &
PIDS+=($!)
# NOTE: localhost, not 127.0.0.1. The Angular dev server binds to IPv6 only
# ([::1]:4200), so an IPv4 health check never succeeds even though the server
# is up and serving. The ML service and backend bind IPv4, so they keep
# 127.0.0.1 and avoid a pointless IPv6 attempt first.
wait_for_http "http://localhost:$FRONTEND_PORT" "Frontend" 180 "$LOG_DIR/frontend.log"

say ""
say "All three services are running."
printf "%s\n" ""
printf "%s\n" "    Open  ${BOLD}http://localhost:$FRONTEND_PORT${OFF}"
printf "%s\n" ""
printf "%s\n" "    ${DIM}logs/ml-service.log   logs/backend.log   logs/frontend.log${OFF}"
printf "%s\n" ""
printf "%s\n" "    Press ${BOLD}Ctrl+C${OFF} to stop everything."
printf "%s\n" ""

# Wait until interrupted.
wait
