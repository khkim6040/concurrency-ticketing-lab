#!/usr/bin/env bash
# M1 DoD: LOCAL_LOCK은 앱 1대 PASS·2대 FAIL. CONDITIONAL_UPDATE/PESSIMISTIC/OPTIMISTIC은 양쪽 모두 정합. NONE은 양쪽 모두 FAIL.
# M2 DoD: CONDITIONAL_UPDATE + 앱 2대에서 좌석 NONE은 dup>0으로 FAIL, UNIQUE_CONSTRAINT는 dup=0·dupKey>0으로 PASS.
# M3 DoD: 정합 조합(CONDITIONAL_UPDATE + UNIQUE_CONSTRAINT)에서 캐시 전략 4종의 phantom·stale 창·DB 조회 수를 비교한다. 판정은 바뀌지 않는다.
#         INVALIDATE_ON_WRITE의 삭제-재적재 경합은 20ms에서는 판매가 조회보다 먼저 끝나 걸리지 않으므로 200ms로 한 번 더 돌린다.
# NONE을 먼저 돌려 같은 파라미터의 DEGRADED 기준선을 만든다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUNS=${RUNS:-3}
M2_RUNS=${M2_RUNS:-10}
M3_RUNS=${M3_RUNS:-5}

run() { # $1 oversell, $2 appInstances, $3 doubleBooking, $4 cacheConsistency (기본 NONE), $5 raceWindowMs (기본 20)
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":$2,\"raceWindowMs\":${5:-20},\"strategies\":{\"oversell\":\"$1\",\"doubleBooking\":\"$3\",\"cacheConsistency\":\"${4:-NONE}\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) dup=\(.report.consistency.doubleBookedSeats) dupKey=\(.report.performance.duplicateKeyCount) phantom=\(.report.consistency.phantomStockViews) stale=\(.report.consistency.staleWindowMs)ms viewDb=\(.report.performance.viewDbReads)/\(.report.performance.viewCount) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms retries=\(.report.performance.retryCount) connPeak=\(.report.performance.dbConnectionPeak)"'
}

for s in NONE LOCAL_LOCK CONDITIONAL_UPDATE PESSIMISTIC OPTIMISTIC; do
  for n in 1 2; do
    echo "== $s apps=$n doubleBooking=NONE"
    for _ in $(seq "$RUNS"); do run "$s" "$n" NONE; done
  done
done

for d in NONE UNIQUE_CONSTRAINT; do
  echo "== CONDITIONAL_UPDATE apps=2 doubleBooking=$d"
  for _ in $(seq "$M2_RUNS"); do run CONDITIONAL_UPDATE 2 "$d"; done
done

for c in NONE TTL_SHORT INVALIDATE_ON_WRITE REDIS_AS_SOT; do
  echo "== CONDITIONAL_UPDATE apps=2 doubleBooking=UNIQUE_CONSTRAINT cache=$c"
  for _ in $(seq "$M3_RUNS"); do run CONDITIONAL_UPDATE 2 UNIQUE_CONSTRAINT "$c"; done
done

echo "== CONDITIONAL_UPDATE apps=2 doubleBooking=UNIQUE_CONSTRAINT cache=INVALIDATE_ON_WRITE raceWindowMs=200"
for _ in $(seq "$M3_RUNS"); do run CONDITIONAL_UPDATE 2 UNIQUE_CONSTRAINT INVALIDATE_ON_WRITE 200; done
