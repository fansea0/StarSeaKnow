#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

if [[ -z "${SU_PWD:-}" ]]; then
  echo "SU_PWD env var not set. Set it to the seeded su password (or UPDATE platform_admin to a known password before running)." >&2
  exit 1
fi

echo "1. su login"
SU=$(curl -sS -X POST "$BASE/platform/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"su","password":"'"$SU_PWD"'"}')
SU_TOK=$(echo "$SU" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "  su token len=${#SU_TOK}"

echo "2. create tenant"
SUF=$(date +%s)
CREATE=$(curl -sS -X POST "$BASE/platform/tenants" \
  -H "Authorization: Bearer $SU_TOK" -H 'Content-Type: application/json' \
  -d "{\"code\":\"acme-$SUF\",\"name\":\"ACME $SUF\"}")
echo "  $CREATE"
INV=$(echo "$CREATE" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['inviteCode'])")

echo "3. accept invite"
JAR=$(mktemp)
curl -sS -c "$JAR" -X POST "$BASE/auth/accept-invite" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"$INV\",\"password\":\"Pw!12345\",\"displayName\":\"Alice\"}" >/dev/null

echo "4. login"
curl -sS -c "$JAR" -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"acme-$SUF\",\"username\":\"admin\",\"password\":\"Pw!12345\"}" >/dev/null

echo "5. /agent/list"
curl -sS -b "$JAR" -X GET "$BASE/agent/list" | head -c 200; echo

echo "6. refresh"
curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE/auth/refresh" | head -c 200; echo

echo "7. logout"
curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE/auth/logout" -o /dev/null -w '%{http_code}\n'

rm -f "$JAR"
echo "smoke OK"
