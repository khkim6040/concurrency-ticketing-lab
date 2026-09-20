#!/usr/bin/env bash
# M0 DoD: NONE 10회 모두 oversold > 0, CONDITIONAL_UPDATE 10회 모두 oversold == 0
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

run() {
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms"'
}

for s in NONE CONDITIONAL_UPDATE; do
  echo "== $s"
  for _ in $(seq 10); do run "$s"; done
done
