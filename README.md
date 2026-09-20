# concurrency-ticketing-lab

티켓 예매에서 생기는 동시성 문제를 켜고 끄면서 결과를 숫자로 보는 시뮬레이터입니다. 좌석 N개에 사용자 M명을 한꺼번에 밀어 넣고, 방어 전략을 바꿔 가며 정합성과 처리량이 어떻게 갈리는지 확인합니다.

하고 싶은 말은 하나입니다. 정합성을 지킬수록 처리량은 떨어집니다. 방어를 전부 켠 상태가 정답처럼 보이면 잘못 배운 것이라서, 두 축을 항상 같이 보여줍니다.

## 지금 되는 것 (M0)

- 앱 서버 2대와 MySQL을 Docker Compose로 띄웁니다. 1대로 돌리면 JVM `synchronized`로 풀리는 문제만 나와서 일부러 2대입니다.
- 오버셀 전략은 `NONE`(읽고, 확인하고, 쓰기)과 `CONDITIONAL_UPDATE`(`UPDATE ... WHERE remaining > 0`) 두 가지입니다.
- `raceWindowMs`로 읽기와 쓰기 사이에 sleep을 넣어 경합 창을 억지로 벌립니다. 이게 없으면 오버셀이 어떤 실행에서는 나고 어떤 실행에서는 안 나서 교재로 쓸 수 없습니다.
- 실행이 끝나면 DB를 직접 집계해 `oversoldCount`, `ledgerMismatch`, 처리량, p50/p95/p99를 내고 PASS 또는 FAIL을 매깁니다.

N=100, M=1,000, raceWindowMs=20, 앱 2대로 각 10회 돌린 결과입니다.

| 전략 | 판정 | oversold | 처리량 (req/s) | p99 |
| --- | --- | --- | --- | --- |
| NONE | FAIL 10/10 | 900 | 750 ~ 3,500 | 250 ~ 1,200 ms |
| CONDITIONAL_UPDATE | PASS 10/10 | 0 | 4,100 ~ 5,900 | 160 ~ 230 ms |

NONE에서 oversold가 매번 정확히 900인 이유는, 20ms 창이 충분히 넓어서 1,000명 전원이 잔여 100을 읽고 성공하기 때문입니다. `raceWindowMs`를 0으로 내리면 "운 좋으면 안 터지는" 현실이 나옵니다.

## 실행

JDK 21과 Docker가 필요합니다.

```bash
docker compose up -d --build
open http://localhost:8080      # 파라미터 폼 + 결과
./scripts/dod.sh                # 위 표를 재현
```

로컬에서 단위 테스트만 돌리려면 `./gradlew test` 입니다.

## API

```
POST /api/runs
{
  "seatCount": 100, "userCount": 1000, "appInstances": 2,
  "raceWindowMs": 20, "strategies": { "oversell": "NONE" }
}
→ 202 { "runId": "..." }   (진행 중인 실행이 있으면 409)

GET /api/runs/{runId}
→ { "status": "RUNNING" | "DONE" | "ERROR", "report": { ... } }
```

## 구조

Spring Boot 단일 모듈 하나를 이미지 하나로 빌드하고, 프로필로 역할을 나눕니다. `app` 프로필은 예매 API만, `web` 프로필은 실행 API와 부하 생성기만 켭니다. 앱은 무상태라서 전략과 지연량을 요청 본문으로 받습니다.

부하 생성기는 가상 스레드 M개를 만들어 `CountDownLatch` 하나로 출발선을 맞춥니다. 브라우저가 아니라 서버가 요청을 만들어야 출발 편차 없이 경합이 생깁니다.

DB 접근은 `JdbcClient`로 SQL을 그대로 씁니다. JPA를 쓰지 않은 이유는 `FOR UPDATE`나 조건부 UPDATE 같은 락 계층이 코드에 그대로 보여야 하기 때문입니다.

```
src/main/kotlin/lab/
  Models.kt                 공용 타입과 결과 집계
  app/ReservationService.kt 전략별 SQL
  app/ReserveController.kt  POST /api/reserve
  web/LoadRunner.kt         동시 출발, 결과 수집
  web/RunController.kt      POST /api/runs, GET /api/runs/{id}
db/schema.sql               MySQL initdb
scripts/dod.sh              재현율 검증
```

## 로드맵

- [x] M0 뼈대 관통. 오버셀 NONE / CONDITIONAL_UPDATE, 앱 2대, 숫자만 나오는 UI
- [ ] M1 LOCAL_LOCK / PESSIMISTIC / OPTIMISTIC 추가, 앱 1대와 2대 전환, 성능 지표, 좌석 그리드
- [ ] M2 중복 배정. seat 테이블, 유니크 인덱스 런타임 토글
- [ ] M3 캐시 계층. Redis, stale read 전략 4종
- [ ] M4 두 실행 비교, 시드 공유 링크, 전략별 해설

## 문서

- [스펙과 로드맵](concurrency-ticketing-lab_spec.md)
- [결정 기록](docs/decisions.md). 스펙에서 열어둔 선택지와 스펙과 다르게 간 부분을 한 줄씩 적습니다.
- [M0 설계](docs/superpowers/specs/2026-09-20-m0-design.md), [M0 구현 계획](docs/superpowers/plans/2026-09-20-m0-skeleton.md)
