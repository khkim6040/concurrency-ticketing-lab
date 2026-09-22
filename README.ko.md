# concurrency-ticketing-lab

[English](README.md) · **한국어**

티켓 예매 시스템의 경쟁 상태를 켜고 끄면서 그 결과를 숫자로 보는 시뮬레이터입니다. 좌석 N개와 동시 사용자 M명을 정하고 방어 전략을 고르면 정합성 점수와 처리량 점수를 함께 돌려줍니다.

하려는 말은 하나입니다. 정합성을 강하게 걸수록 처리량은 줄어듭니다. 두 축을 늘 함께 보여줍니다. 방어를 전부 켜는 것이 정답인 도구라면 잘못된 것을 가르치는 셈이기 때문입니다.

![같은 시드로 두 번 실행: oversell NONE은 실패하고, CONDITIONAL_UPDATE + 유니크 인덱스는 통과합니다. 둘을 나란히 비교합니다](docs/demo.gif)

## 무엇을 하는가

- 앱 서버 두 대, MySQL, Redis를 Docker Compose로 띄웁니다. 한 대만 두면 JVM 락 하나로 여기서 다루는 문제가 전부 풀려 버립니다. 그것이야말로 배우면 안 되는 결론이라 최소 두 대입니다.
- `raceWindowMs` 노브는 읽기와 쓰기 사이, 락이나 트랜잭션 안에서 잠듭니다. 덕분에 매 실행 경합이 재현됩니다.
- 전략 축 세 개. 재시작 없이 실행마다 바꿉니다.
  - `oversell`: `NONE`, `LOCAL_LOCK`(JVM `ReentrantLock`), `CONDITIONAL_UPDATE`, `PESSIMISTIC`(`SELECT ... FOR UPDATE`), `OPTIMISTIC`(버전 확인, 재시도 상한 없음)
  - `doubleBooking`: `NONE`(좌석을 조회한 뒤 INSERT)과 `UNIQUE_CONSTRAINT`(`(event_id, seat_no)` 유니크 인덱스. 매 실행 시작에 만들거나 지웁니다)
  - `cacheConsistency`: `NONE`(cache-aside, TTL 60초), `TTL_SHORT`(1초), `INVALIDATE_ON_WRITE`, 그리고 카운터가 Redis에 있고 `DECR`이 결정하며 oversell 전략이 무시되는 `REDIS_AS_SOT`
- 실행이 끝난 뒤 DB에서 세는 값 네 개: `oversoldCount`, `ledgerMismatch`, `doubleBookedSeats`, `duplicateKeyCount`. 실행 중에 재는 값 네 개: 처리량, p50/p95/p99, `retryCount`, `dbConnectionPeak`. 판정은 PASS 아니면 FAIL이고, 정합한 실행이 같은 조건 `NONE`의 절반 이하 처리량이면 DEGRADED입니다.
- 조회자 스레드 두 개가 실행 중과 종료 후 2초 동안 10ms마다 재고 캐시(`GET /api/stock`)를 폴링합니다. 매진 뒤에도 잔여석이 있다고 답한 조회가 `phantomStockViews`이고, `staleWindowMs`·`viewDbReads`와 함께 보고합니다. 셋 다 판정에는 들어가지 않습니다. stale read는 설계 선택이고, UI에도 보고서 아래에 그렇게 적혀 있습니다.
- 실행 중에 채워지는 좌석 그리드. 회색은 미판매, 초록은 한 번 판매, 빨강은 두 명 이상에게 판매, 주황은 정원을 넘긴 예약입니다.
- 공유 링크. 실행이 끝나면 주소창이 `?seatCount=...&seed=...&oversell=...`으로 바뀌고, 그 링크를 열면 폼이 채워지며 바로 실행됩니다. 시드가 각 사용자의 좌석 선택을 고정하므로, 링크가 재현하는 것은 숫자가 아니라 실험입니다.
- 직전 실행과 이번 실행의 비교 표. 달라진 파라미터는 굵게 나오고, 전략마다 "왜?" 해설이 붙습니다. 영어가 기본이고 한국어 토글이 있습니다.

## 결과

모든 요청은 좌석을 먼저 잡고, 좌석을 얻은 요청만 카운터까지 갑니다. 그래서 `NONE`의 정원 초과 판매는 고정된 900이 아니라 수십에서 수백입니다. 어느 좌석이 부딪치는지가 무작위라 실행마다 달라집니다.

N=100, M=1,000, `raceWindowMs=20`, `doubleBooking=NONE`으로 각각 세 번씩(`./scripts/dod.sh`):

| 전략 | 앱 | 판정 | 정원 초과 | 원장 | 처리량 (req/s) | p99 | 재시도 | 커넥션 최대 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | 2 | FAIL 3/3 | 73\~113 | -207\~-167 | 952\~4,201 | 225\~1,031 ms | 0 | 17\~20 |
| LOCAL_LOCK | 1 | FAIL 3/3 | 0 | 0 | 152\~190 | 5,019\~6,344 ms | 0 | 20 |
| LOCAL_LOCK | 2 | FAIL 3/3 | 94\~100 | -100\~-94 | 290\~386 | 2,403\~3,228 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 2 | FAIL 3/3 | 0 | 0 | 2,500\~2,849 | 343\~389 ms | 0 | 20 |
| PESSIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 94\~104 | 9,298\~10,357 ms | 0 | 20 |
| OPTIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 417\~423 | 2,353\~2,390 ms | 16,603\~18,224 | 20 |

읽어야 할 것은 `LOCAL_LOCK` 두 줄입니다. 앱 한 대에서는 락이 모든 요청을 줄 세워 카운터가 정확히 맞습니다. 정원 초과도 원장도 0입니다. 두 대에서는 각 JVM이 자기 절반만 줄 세우고 두 절반이 서로 경쟁해, 좌석 100개 중 94\~100개를 초과 판매합니다. 나머지 전략을 앱 2대로만 적은 이유는 방어가 DB에 있어 1대일 때 수치가 오차 범위 안에서 같기 때문입니다. 모든 줄이 FAIL인 것은 여기서 `doubleBooking`이 `NONE`이라 카운터 총합이 정확해도 한 좌석이 두 사람에게 갈 수 있어서입니다. 그 축은 다음 표에서 따로 봅니다. `PESSIMISTIC`에서 커넥션 최대치가 풀 크기에 붙어 있는 것은 각 트랜잭션이 행 락 안에서 자는 동안 커넥션을 쥐고 있기 때문입니다. `OPTIMISTIC`은 카운터를 정확히 지키면서 두 락보다 빠르지만, 그 대가로 수만 번을 재시도합니다.

같은 파라미터에 `CONDITIONAL_UPDATE`, 앱 2대, `doubleBooking` 두 전략을 각각 열 번씩:

| oversell | doubleBooking | 판정 | 정원 초과 | 원장 | 중복 배정 | 중복 키 | 처리량 (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CONDITIONAL_UPDATE | NONE | FAIL 10/10 | 0 | 0 | 21\~31 | 0 | 2,277\~2,857 | 341\~429 ms |
| CONDITIONAL_UPDATE | UNIQUE_CONSTRAINT | PASS 10/10 | 0 | 0 | 0 | 900 | 5,076\~5,917 | 159\~190 ms |

`CONDITIONAL_UPDATE`만으로는 개수는 맞지만 좌석이 겹쳐 여전히 실패합니다. 윗단 코드가 무엇을 했든 두 번째 예약을 거절하는 것은 유니크 인덱스뿐입니다.

반대는 성립하지 않습니다. 유니크 인덱스는 많아야 N개의 요청만 카운터까지 보내므로, `UNIQUE_CONSTRAINT`에서는 oversell 전략이 무엇이든 `oversoldCount`가 0입니다. `oversell=NONE`에 `doubleBooking=UNIQUE_CONSTRAINT`를 걸어 보고 `oversoldCount`가 아니라 `ledgerMismatch`를 읽으면 됩니다. 또 저 줄의 `PASS`는 정합하다는 뜻일 뿐입니다. DoD 스크립트가 그 좌석 전략의 `NONE` 기준선을 남기지 않아 거기서는 `DEGRADED` 검사가 아예 돌지 않습니다.

같은 파라미터에 `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT`, 앱 2대, `cacheConsistency` 네 전략을 각각 다섯 번씩:

| 캐시 | 판정 | 매진 후 노출 | stale 구간 | DB 조회 / 전체 조회 | 처리량 (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| NONE | PASS 5/5 | 256\~279 | 2,032\~2,055 ms | 1\~2 / 267\~294 | 5,154\~5,847 | 160\~189 ms |
| TTL_SHORT | PASS 5/5 | 120\~128 | 877\~912 ms | 5\~6 / 273\~285 | 5,208\~6,024 | 158\~182 ms |
| INVALIDATE_ON_WRITE | PASS 5/5 | 274\~286 (5번 중 2번) | 2,048\~2,056 ms (5번 중 2번) | 6\~7 / 265\~292 | 5,235\~5,952 | 161\~181 ms |
| REDIS_AS_SOT | PASS 5/5 | 0 | 0 ms | 0 / 276\~294 | 5,291\~5,882 | 162\~182 ms |
| `INVALIDATE_ON_WRITE` (raceWindowMs=200) | PASS 5/5 | 240\~250 (5번 중 5번) | 2,030\~2,052 ms | 2 / 242\~252 | 5,319\~6,060 | 160\~183 ms |

`NONE`은 처음 본 값을 TTL이 끝날 때까지 쥐고 있는데, 그 시점은 실행이 끝난 뒤입니다. `TTL_SHORT`는 구간을 TTL 길이로 묶을 뿐, 그보다 짧게는 못 줄입니다. 구간을 0으로 닫는 TTL은 캐시가 없는 것과 같습니다. `INVALIDATE_ON_WRITE`가 옳은 것은 캐시를 놓쳐 DB를 읽고 잠들었던 조회자가 마지막 쓰기의 삭제 뒤에 자기 옛값을 얹기 전까지입니다. 그다음에는 아무도 다시 지우지 않습니다. 기본 20ms 구간에서는 5번 중 2번, 200ms에서는 다섯 번 모두 걸립니다. 이 경합은 읽기 지연과 쓰기 폭주의 관계에서 나오고, 노브는 그것을 눈에 보이게 만들 뿐입니다. `REDIS_AS_SOT`는 값을 두 벌 들고 있지 않아 측정값이 0입니다. 그 대가는 DB 조회 열에 드러납니다. 구간이 짧을수록 캐시가 덜 흡수합니다.

### 모든 조합

아래 모든 경우는 N=100, M=1,000, `raceWindowMs=20`, `seed=42`이고, 캡션에 따로 적지 않으면 앱 2대입니다. `./scripts/case-shots.sh`가 찍습니다. 경우마다 스크린샷 한 장에 좌석 그리드, 판정, 그 실행의 지표 표 전체가 들어 있습니다. FAIL 25, PASS 7, DEGRADED 6.

축 세 개와 앱 대수로 조합은 80가지지만 38가지로 접힙니다. `REDIS_AS_SOT`는 카운터를 Redis에 두고 oversell 전략을 무시하므로 다섯 줄이 한 줄이 됩니다. `appInstances`가 결과를 바꾸는 전략은 JVM 안에서만 도는 유일한 방어인 `LOCAL_LOCK`뿐입니다. 나머지는 MySQL이나 Redis에 있어 앱이 1대든 2대든 결과가 같습니다.

각 묶음은 `oversell=NONE`을 먼저 돌리므로 앱 2대에서는 `DEGRADED` 검사에 기준선이 있습니다. 앱 1대 실행은 맞는 기준선이 없어 PASS 아니면 FAIL만 나옵니다. `LOCAL_LOCK` 1대가, `PESSIMISTIC`이었다면 DEGRADED로 찍혔을 처리량인데도 PASS로 나오는 이유입니다.

<details>
<summary><b>oversell = NONE</b>. 방어 없음. 카운터를 읽고, 자고, 다시 씁니다 (6)</summary>

**doubleBooking=NONE · cacheConsistency=NONE** → FAIL

![none-none-none-2app FAIL](docs/cases/none-none-none-2app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT** → FAIL

![none-none-ttl_short-2app FAIL](docs/cases/none-none-ttl_short-2app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE** → FAIL

![none-none-invalidate_on_write-2app FAIL](docs/cases/none-none-invalidate_on_write-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE** → FAIL

![none-unique_constraint-none-2app FAIL](docs/cases/none-unique_constraint-none-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT** → FAIL

![none-unique_constraint-ttl_short-2app FAIL](docs/cases/none-unique_constraint-ttl_short-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE** → FAIL

![none-unique_constraint-invalidate_on_write-2app FAIL](docs/cases/none-unique_constraint-invalidate_on_write-2app.png)

</details>

<details>
<summary><b>oversell = LOCAL_LOCK</b>. JVM `ReentrantLock`. 앱 1대와 2대를 모두 담았습니다 (12)</summary>

**doubleBooking=NONE · cacheConsistency=NONE · appInstances=1** → FAIL

![local_lock-none-none-1app FAIL](docs/cases/local_lock-none-none-1app.png)

---

**doubleBooking=NONE · cacheConsistency=NONE · appInstances=2** → FAIL

![local_lock-none-none-2app FAIL](docs/cases/local_lock-none-none-2app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT · appInstances=1** → FAIL

![local_lock-none-ttl_short-1app FAIL](docs/cases/local_lock-none-ttl_short-1app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT · appInstances=2** → FAIL

![local_lock-none-ttl_short-2app FAIL](docs/cases/local_lock-none-ttl_short-2app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE · appInstances=1** → FAIL

![local_lock-none-invalidate_on_write-1app FAIL](docs/cases/local_lock-none-invalidate_on_write-1app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE · appInstances=2** → FAIL

![local_lock-none-invalidate_on_write-2app FAIL](docs/cases/local_lock-none-invalidate_on_write-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE · appInstances=1** → PASS

![local_lock-unique_constraint-none-1app PASS](docs/cases/local_lock-unique_constraint-none-1app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE · appInstances=2** → FAIL

![local_lock-unique_constraint-none-2app FAIL](docs/cases/local_lock-unique_constraint-none-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT · appInstances=1** → PASS

![local_lock-unique_constraint-ttl_short-1app PASS](docs/cases/local_lock-unique_constraint-ttl_short-1app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT · appInstances=2** → FAIL

![local_lock-unique_constraint-ttl_short-2app FAIL](docs/cases/local_lock-unique_constraint-ttl_short-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE · appInstances=1** → PASS

![local_lock-unique_constraint-invalidate_on_write-1app PASS](docs/cases/local_lock-unique_constraint-invalidate_on_write-1app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE · appInstances=2** → FAIL

![local_lock-unique_constraint-invalidate_on_write-2app FAIL](docs/cases/local_lock-unique_constraint-invalidate_on_write-2app.png)

</details>

<details>
<summary><b>oversell = CONDITIONAL_UPDATE</b>. `UPDATE ... WHERE remaining > 0` 한 문장 (6)</summary>

**doubleBooking=NONE · cacheConsistency=NONE** → FAIL

![conditional_update-none-none-2app FAIL](docs/cases/conditional_update-none-none-2app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT** → FAIL

![conditional_update-none-ttl_short-2app FAIL](docs/cases/conditional_update-none-ttl_short-2app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE** → FAIL

![conditional_update-none-invalidate_on_write-2app FAIL](docs/cases/conditional_update-none-invalidate_on_write-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE** → PASS

![conditional_update-unique_constraint-none-2app PASS](docs/cases/conditional_update-unique_constraint-none-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT** → PASS

![conditional_update-unique_constraint-ttl_short-2app PASS](docs/cases/conditional_update-unique_constraint-ttl_short-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE** → PASS

![conditional_update-unique_constraint-invalidate_on_write-2app PASS](docs/cases/conditional_update-unique_constraint-invalidate_on_write-2app.png)

</details>

<details>
<summary><b>oversell = PESSIMISTIC</b>. 트랜잭션 안의 `SELECT ... FOR UPDATE` (6)</summary>

**doubleBooking=NONE · cacheConsistency=NONE** → FAIL

![pessimistic-none-none-2app FAIL](docs/cases/pessimistic-none-none-2app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT** → FAIL

![pessimistic-none-ttl_short-2app FAIL](docs/cases/pessimistic-none-ttl_short-2app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE** → FAIL

![pessimistic-none-invalidate_on_write-2app FAIL](docs/cases/pessimistic-none-invalidate_on_write-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE** → DEGRADED

![pessimistic-unique_constraint-none-2app DEGRADED](docs/cases/pessimistic-unique_constraint-none-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT** → DEGRADED

![pessimistic-unique_constraint-ttl_short-2app DEGRADED](docs/cases/pessimistic-unique_constraint-ttl_short-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE** → DEGRADED

![pessimistic-unique_constraint-invalidate_on_write-2app DEGRADED](docs/cases/pessimistic-unique_constraint-invalidate_on_write-2app.png)

</details>

<details>
<summary><b>oversell = OPTIMISTIC</b>. 버전 확인, 재시도 상한 없음 (6)</summary>

**doubleBooking=NONE · cacheConsistency=NONE** → FAIL

![optimistic-none-none-2app FAIL](docs/cases/optimistic-none-none-2app.png)

---

**doubleBooking=NONE · cacheConsistency=TTL_SHORT** → FAIL

![optimistic-none-ttl_short-2app FAIL](docs/cases/optimistic-none-ttl_short-2app.png)

---

**doubleBooking=NONE · cacheConsistency=INVALIDATE_ON_WRITE** → FAIL

![optimistic-none-invalidate_on_write-2app FAIL](docs/cases/optimistic-none-invalidate_on_write-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=NONE** → DEGRADED

![optimistic-unique_constraint-none-2app DEGRADED](docs/cases/optimistic-unique_constraint-none-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=TTL_SHORT** → DEGRADED

![optimistic-unique_constraint-ttl_short-2app DEGRADED](docs/cases/optimistic-unique_constraint-ttl_short-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT · cacheConsistency=INVALIDATE_ON_WRITE** → DEGRADED

![optimistic-unique_constraint-invalidate_on_write-2app DEGRADED](docs/cases/optimistic-unique_constraint-invalidate_on_write-2app.png)

</details>

<details>
<summary><b>cacheConsistency = REDIS_AS_SOT</b>. 카운터가 Redis에 있고 `DECR`이 결정하므로 oversell 전략이 무시됩니다 (2)</summary>

**doubleBooking=NONE** → FAIL

![none-none-redis_as_sot-2app FAIL](docs/cases/none-none-redis_as_sot-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT** → PASS

![none-unique_constraint-redis_as_sot-2app PASS](docs/cases/none-unique_constraint-redis_as_sot-2app.png)

</details>

## 실행 방법

JDK 21과 Docker가 필요합니다.

```bash
docker compose up -d --build
open http://localhost:8080      # 파라미터 폼과 결과
./scripts/dod.sh                # 위 표를 재현합니다
./scripts/demo-gif.sh           # docs/demo.gif를 다시 녹화합니다 (Node와 ffmpeg 필요)
./scripts/case-shots.sh         # docs/cases/*.png를 조합마다 다시 찍습니다 (Node 필요)
```

`./gradlew test`가 단위 테스트를, `node --test src/test/js/ui.test.mjs`가 UI 함수를 돌립니다. 둘 다 Docker 없이 돌아갑니다.

GIF 후반부를 직접 재현해 보는 링크:

```
http://localhost:8080/?seatCount=100&userCount=1000&appInstances=2&raceWindowMs=20&seed=42&oversell=CONDITIONAL_UPDATE&doubleBooking=UNIQUE_CONSTRAINT&cacheConsistency=NONE
```

## API

```
POST /api/runs
{
  "seatCount": 100, "userCount": 1000, "appInstances": 2,
  "raceWindowMs": 20, "strategies": { "oversell": "NONE", "doubleBooking": "NONE", "cacheConsistency": "NONE" }
}
→ 202 { "runId": "..." }   (실행이 이미 진행 중이면 409)

GET /api/runs/{runId}
→ { "status": "RUNNING" | "DONE" | "ERROR", "report": { ... } }
```

## 어떻게 짜여 있는가

Spring Boot 모듈 하나를 이미지 하나로 빌드하고, 역할은 프로파일로 고릅니다. `app` 프로파일은 예매 엔드포인트만 열고, `web` 프로파일은 실행 API와 부하 생성기를 엽니다. 앱 인스턴스는 무상태입니다. 전략과 지연이 요청 본문으로 들어오므로 전략을 바꿀 때 재시작이 필요 없습니다.

부하 생성기는 가상 스레드 M개를 띄워 `CountDownLatch` 하나 뒤에 줄 세웁니다. 요청은 서버에서 출발해야 합니다. 브라우저에서 출발하면 출발 시각이 흔들리는 것만으로 경합이 사라집니다.

DB 접근은 `JdbcClient`로 하고 SQL은 손으로 씁니다. JPA는 일부러 쓰지 않았습니다. 락을 거는 부분(`FOR UPDATE`, 조건부 업데이트, 버전 확인)이 코드에 보이지 않으면 배울 것이 없습니다. Redis는 `stock:{eventId}`에 재고 캐시를 담고, `REDIS_AS_SOT`에서는 카운터 자체를 담습니다.

```
src/main/kotlin/lab/
  Models.kt                 공용 타입과 리포트 집계
  app/ReservationService.kt 좌석 단계, 그다음 카운터 전략별 SQL 경로. REDIS_AS_SOT면 DECR
  app/StockCache.kt         stock:{eventId} 읽기 경로, DECR 카운터, 쓰기 시 DEL
  app/ReserveController.kt  POST /api/reserve
  web/LoadRunner.kt         동시 출발, 결과 수집
  web/RunController.kt      POST /api/runs, GET /api/runs/{id}
  static/index.html         폼, 좌석 그리드, 비교 표, 이중 언어 해설
  static/lib.js             공유 링크 직렬화, 비교 행, 판정 이유, 문자열
db/schema.sql               MySQL initdb
scripts/dod.sh              재현성 확인
```

## 문서

- [스펙과 로드맵](concurrency-ticketing-lab_spec.md)
- [결정 기록](docs/decisions.md). 스펙이 열어둔 선택지와 코드가 스펙과 달라진 부분을 한 줄에 하나씩 적었습니다.
- [마일스톤 설계와 구현 계획](docs/milestones/)
