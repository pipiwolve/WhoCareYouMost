#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8123/api/v1}"
SESSION_NAME="${SESSION_NAME:-MCP联调用户}"
LATITUDE="${LATITUDE:-31.2304}"
LONGITUDE="${LONGITUDE:-121.4737}"
OUT_DIR="${OUT_DIR:-./tmp/mcp-e2e}"

mkdir -p "$OUT_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "node is required to parse JSON responses." >&2
  exit 1
fi

echo "[1/6] Create profile..."
PROFILE_JSON=$(curl -sS -X POST "$BASE_URL/users/profile" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"$SESSION_NAME\",\"age\":30,\"gender\":\"MALE\"}")

echo "$PROFILE_JSON" > "$OUT_DIR/profile.json"

SESSION_ID=$(node -e 'const fs=require("fs");const obj=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));process.stdout.write(obj?.data?.sessionId ?? "");' "$OUT_DIR/profile.json")

USER_ID=$(node -e 'const fs=require("fs");const obj=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));process.stdout.write(obj?.data?.userId ?? "");' "$OUT_DIR/profile.json")

if [[ -z "$SESSION_ID" || -z "$USER_ID" ]]; then
  echo "Failed to extract sessionId/userId. See $OUT_DIR/profile.json" >&2
  exit 1
fi

echo "sessionId=$SESSION_ID"
echo "userId=$USER_ID"

echo "[2/6] Chat round 1..."
CHAT1=$(curl -sS -X POST "$BASE_URL/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"我这两天胸闷，偶尔心慌\",\"attachments\":[]}")
echo "$CHAT1" > "$OUT_DIR/chat1.json"

echo "[3/6] Chat round 2..."
CHAT2=$(curl -sS -X POST "$BASE_URL/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"无发热，活动后更明显\",\"attachments\":[]}")
echo "$CHAT2" > "$OUT_DIR/chat2.json"

echo "[4/6] Update location..."
LOCATION_RESP=$(curl -sS -X POST "$BASE_URL/reports/$SESSION_ID/location" \
  -H 'Content-Type: application/json' \
  -d "{\"latitude\":$LATITUDE,\"longitude\":$LONGITUDE,\"consentGranted\":true}")
echo "$LOCATION_RESP" > "$OUT_DIR/location.json"

echo "[5/6] Query report..."
REPORT_JSON=$(curl -sS "$BASE_URL/reports/$SESSION_ID")
echo "$REPORT_JSON" > "$OUT_DIR/report.json"

READY=$(node -e 'const fs=require("fs");const obj=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));process.stdout.write(String(Boolean(obj?.data?.ready)));' "$OUT_DIR/report.json")

echo "report.ready=$READY"

echo "[6/6] Download PDF..."
PDF_PATH="$OUT_DIR/report-$SESSION_ID.pdf"
HTTP_CODE=$(curl -sS -o "$PDF_PATH" -w "%{http_code}" "$BASE_URL/reports/$SESSION_ID/pdf")

echo "pdf.http=$HTTP_CODE"
if [[ "$HTTP_CODE" != "200" ]]; then
  echo "PDF download failed. See $OUT_DIR/report.json and server logs." >&2
  exit 1
fi

echo "Done. Artifacts:"
echo "- $OUT_DIR/profile.json"
echo "- $OUT_DIR/chat1.json"
echo "- $OUT_DIR/chat2.json"
echo "- $OUT_DIR/location.json"
echo "- $OUT_DIR/report.json"
echo "- $PDF_PATH"

echo "Quick checks:"
echo "- report JSON should include hospitals/routesAvailable/routeStatusMessage when planning path is active."
echo "- PDF should contain hospital planning section when report is ready."
