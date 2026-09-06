#!/usr/bin/env bash
# Driver for the LedgerPrime FastAPI server: boots it against a throwaway SQLite DB, runs
# migrations, then drives a real user flow (register -> JWT -> authenticated report call)
# with curl. Run from server/ (the directory this .claude/skills/run-server/ lives under).
#
# Usage:
#   bash .claude/skills/run-server/smoke.sh
#
# Requires: .venv already created and deps installed (see SKILL.md "Build").
set -euo pipefail

cd "$(dirname "$0")/../../.."   # server/

PYTHON=.venv/Scripts/python.exe
PORT=8000
BASE="http://127.0.0.1:$PORT/api/v1"
DB=ledgerprime_dev.db
LOG=/tmp/ledgerprime-server-smoke.log

echo "== fresh dev DB + migrations =="
rm -f "$DB"
"$PYTHON" -m alembic upgrade head

echo "== starting uvicorn =="
"$PYTHON" -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" > "$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for i in $(seq 1 30); do
  if curl -s -o /dev/null "$BASE/health"; then break; fi
  sleep 0.5
done

echo "== health =="
curl -sf "$BASE/health"; echo

echo "== register (creates user + JWT) =="
EMAIL="smoke-$(date +%s)@example.com"
RESP=$(curl -sf -X POST "$BASE/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"TestPass123!\"}")
echo "$RESP"
TOKEN=$("$PYTHON" -c "import json,sys; print(json.loads(sys.argv[1])['accessToken'])" "$RESP")

echo "== authenticated report call (trial balance, empty company) =="
curl -sf -w '\nHTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "$BASE/reports/trial-balance?companyId=smoke-co&financialYearId=smoke-fy"

echo "== done - server log at $LOG =="
