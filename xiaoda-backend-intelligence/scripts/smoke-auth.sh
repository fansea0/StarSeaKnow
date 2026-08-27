#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8090}
SU_USER=${SU_USER:-su}

if [[ -z "${SU_PWD:-}" ]]; then
  echo "SU_PWD is required (the platform administrator password)." >&2
  exit 1
fi

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

json_value() {
  local path=$1
  python3 -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    value = value[key]
print(value)
' "$path"
}

post_json() {
  curl -fsS -X POST "$1" -H 'Content-Type: application/json' "${@:2}"
}

utc_after_minutes() {
  python3 -c '
from datetime import datetime, timedelta, timezone
import sys
print((datetime.now(timezone.utc) + timedelta(minutes=int(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))
' "$1"
}

echo "1. Platform administrator login"
SU=$(post_json "$BASE/platform/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SU_USER\",\"password\":\"$SU_PWD\"}")
SU_TOK=$(printf '%s' "$SU" | json_value data.accessToken)
MUST_CHANGE=$(printf '%s' "$SU" | json_value data.mustChangePassword)

if [[ "$MUST_CHANGE" == "True" || "$MUST_CHANGE" == "true" ]]; then
  if [[ -z "${SU_NEW_PWD:-}" ]]; then
    echo "SU_NEW_PWD is required because this platform account must change its initial password." >&2
    exit 1
  fi
  echo "2. Change initial platform password"
  post_json "$BASE/platform/auth/change-initial-password" \
    -H "Authorization: Bearer $SU_TOK" \
    -d "{\"currentPassword\":\"$SU_PWD\",\"newPassword\":\"$SU_NEW_PWD\",\"confirmPassword\":\"$SU_NEW_PWD\"}" >/dev/null
  SU=$(post_json "$BASE/platform/auth/login" \
    -d "{\"username\":\"$SU_USER\",\"password\":\"$SU_NEW_PWD\"}")
  SU_TOK=$(printf '%s' "$SU" | json_value data.accessToken)
else
  echo "2. Initial password already changed"
fi

echo "3. Create an active invitation"
CREATE=$(post_json "$BASE/platform/invitations" \
  -H "Authorization: Bearer $SU_TOK" \
  -d "{\"validFrom\":\"$(utc_after_minutes 0)\",\"validUntil\":\"$(utc_after_minutes 60)\"}")
INV=$(printf '%s' "$CREATE" | json_value data.code)

SUF=$(date +%s)
USERNAME="smoke-tenant-$SUF"
TENANT_PASSWORD="Smoke!$SUF"

echo "4. Register a tenant with the invitation"
post_json "$BASE/auth/register" -c "$JAR" \
  -d "{\"inviteCode\":\"$INV\",\"username\":\"$USERNAME\",\"password\":\"$TENANT_PASSWORD\",\"confirmPassword\":\"$TENANT_PASSWORD\"}" >/dev/null

echo "5. Log in as the new tenant user (username and password only)"
post_json "$BASE/auth/login" -c "$JAR" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$TENANT_PASSWORD\"}" >/dev/null

echo "6. Verify the tenant is listed by the platform"
TENANTS=$(curl -fsS "$BASE/platform/tenants?page=1&pageSize=100" \
  -H "Authorization: Bearer $SU_TOK")
printf '%s' "$TENANTS" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
username = sys.argv[1]
if not any(username in str(item) for item in payload["data"]["items"]):
    raise SystemExit("registered tenant was not returned by platform list")
' "$USERNAME"

echo "7. Disable a second invitation and verify registration is rejected"
SECOND=$(post_json "$BASE/platform/invitations" \
  -H "Authorization: Bearer $SU_TOK" \
  -d "{\"validFrom\":\"$(utc_after_minutes 0)\",\"validUntil\":\"$(utc_after_minutes 60)\"}")
SECOND_ID=$(printf '%s' "$SECOND" | json_value data.id)
SECOND_CODE=$(printf '%s' "$SECOND" | json_value data.code)
post_json "$BASE/platform/invitations/$SECOND_ID/disable" \
  -H "Authorization: Bearer $SU_TOK" >/dev/null
DISABLED_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"inviteCode\":\"$SECOND_CODE\",\"username\":\"disabled-$SUF\",\"password\":\"$TENANT_PASSWORD\",\"confirmPassword\":\"$TENANT_PASSWORD\"}")
if [[ "$DISABLED_STATUS" -lt 400 || "$DISABLED_STATUS" -ge 500 ]]; then
  echo "disabled invitation registration returned HTTP $DISABLED_STATUS; expected a 4xx response" >&2
  exit 1
fi

echo "Smoke onboarding OK (no credentials or tokens printed)."
