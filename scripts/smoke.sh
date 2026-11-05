#!/usr/bin/env bash
# Drives a running instance end to end: create -> (approve) -> poll -> print verdict.
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
REQ="${1:-Create a Spring Boot REST API for managing tasks with CRUD operations and validation.}"
ID=$(curl -fsS -X POST "$BASE/api/v1/workflows" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json,sys;print(json.dumps({"requirement":sys.argv[1]}))' "$REQ")" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "workflow $ID"
for _ in $(seq 300); do
  S=$(curl -fsS "$BASE/api/v1/workflows/$ID" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')
  case "$S" in
    WAITING_FOR_APPROVAL) echo "approving"; curl -fsS -X POST "$BASE/api/v1/workflows/$ID/approve" >/dev/null ;;
    COMPLETED|FAILED|CANCELLED) echo "status $S"; break ;;
  esac
  sleep 2
done
curl -fsS "$BASE/api/v1/workflows/$ID/report" | python3 -c 'import sys,json;c=json.load(sys.stdin)["content"];print(c["verdict"],"-",c["summary"])'
