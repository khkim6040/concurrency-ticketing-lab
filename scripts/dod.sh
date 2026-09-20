#!/usr/bin/env bash
# M1 DoD: LOCAL_LOCK은 앱 1대 PASS·2대 FAIL. CONDITIONAL_UPDATE/PESSIMISTIC/OPTIMISTIC은 양쪽 모두 정합. NONE은 양쪽 모두 FAIL.
# NONE을 먼저 돌려 같은 파라미터의 DEGRADED 기준선을 만든다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUNS=${RUNS:-3}

run() { # $1 strategy, $2 appInstances
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":$2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms retries=\(.report.performance.retryCount) connPeak=\(.report.performance.dbConnectionPeak)"'
}

for s in NONE LOCAL_LOCK CONDITIONAL_UPDATE PESSIMISTIC OPTIMISTIC; do
  for n in 1 2; do
    echo "== $s apps=$n"
    for _ in $(seq "$RUNS"); do run "$s" "$n"; done
  done
done
