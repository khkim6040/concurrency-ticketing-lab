# 결정 기록

스펙(`concurrency-ticketing-lab_spec.md`)에서 열어둔 선택지와, 구현 중 스펙과 달리 간 부분을 기록한다. 한 줄에 하나.

| 날짜 | 결정 | 이유 |
| --- | --- | --- |
| 2026-09-20 | Kotlin + JDK 21 | 스펙 예시 코드 그대로. 가상 스레드로 M=10,000 동시 요청. |
| 2026-09-20 | `web`과 `app`은 같은 Spring Boot 이미지, `SPRING_PROFILES_ACTIVE=web\|app`으로 역할 분기 | 코드베이스·이미지 1개. `@Profile`로 컨트롤러만 켜고 끔. |
| 2026-09-20 | DB 계층은 Spring JDBC(`JdbcClient`), JPA 미사용 | SQL이 그대로 보여야 `FOR UPDATE`·조건부 UPDATE·version 재시도가 교보재가 됨. |
| 2026-09-20 | 앱은 무상태. 전략·`raceWindowMs`를 요청 본문으로 전달 | 앱 재시작 없이 전략 전환. 실행 상태는 `web` 메모리 맵에만 보관. |
| 2026-09-20 | `ChaosConfig`/`Phase` 없이 `raceWindowMs` 파라미터 하나 + `Thread.sleep` | 지연 지점이 AFTER_READ 하나뿐. 늘어나면 그때 추가. |
| 2026-09-20 | `NONE` 전략은 트랜잭션 없이 autocommit 두 문장 | sleep 중 커넥션을 점유하지 않아야 풀 고갈이 아니라 lost update가 관측됨. |
| 2026-09-20 | 스키마는 MySQL 컨테이너 initdb(`db/schema.sql`)로 생성 | app-1/app-2가 동시에 DDL을 돌리는 경합 회피. |
| 2026-09-20 | M0 판정은 PASS/FAIL만. DEGRADED는 M1 성능 지표와 함께 | 기준선 처리량이 있어야 50% 비교 가능. |
| 2026-09-20 | M0 UI는 SSE 대신 1초 폴링 | 숫자만 보이면 충분. SSE는 M1 좌석 그리드와 함께. |
| 2026-09-20 | 검증 = 순수 집계 함수 단위 테스트 1개 + `scripts/dod.sh`(컨테이너 10/10 재현) | Testcontainers 의존성 추가 없이 DoD를 실제 환경에서 확인. |
| 2026-09-20 | M1 그리드 갱신은 SSE 대신 200ms 폴링 + `RunState.progress` 카운터 | 새 엔드포인트 없이 기존 GET에 카운터 3개만 추가. 스펙의 SSE는 필요해지면 그때. |
| 2026-09-20 | DEGRADED 기준선 = 같은 (N, M, appInstances, raceWindowMs)의 최근 `NONE` 처리량, web 메모리 보관 | 기준선이 없으면 PASS/FAIL만. 사용자가 NONE을 먼저 돌리면 이후 전략이 자동 비교됨. |
| 2026-09-20 | M1 성능 지표는 `retryCount`, `dbConnectionPeak`만. `lockWaitMs`·`errorBreakdown`은 보류 | 로드맵 M1 항목 그대로. 처리량·p99가 이미 락 비용을 보여줌. |
| 2026-09-20 | 지연 주입은 `LOCAL_LOCK`·`PESSIMISTIC`·`OPTIMISTIC` 모두 락/트랜잭션 안(읽기와 쓰기 사이) | 락을 잡은 채 잠드는 것이 비용의 실체. 밖에 두면 전략 간 처리량 차이가 사라짐. |
| 2026-09-20 | `dbConnectionPeak`는 앱이 응답마다 Hikari active 수를 실어 보내고 web이 최대값 집계 | Actuator 의존성 없이 MXBean 한 줄. 앱은 무상태 유지. |
| 2026-09-20 | `OPTIMISTIC` 재시도 상한 없음 | 버전당 한 요청이 반드시 이기므로 진행 보장. 상한을 두면 ERROR가 섞여 재시도 폭증이 가려짐. |
| 2026-09-20 | M2 요청 순서는 좌석 단계(reservation INSERT) → 카운터 단계(remaining 차감). 카운터가 OK가 아니면 자기 행 DELETE | 유니크 위반이 카운터 전에 끝나 환불이 없고, oversell·doubleBooking 두 축이 직교한다. 카운터 먼저면 환불이 LOCAL_LOCK의 절대값 쓰기와 경합. |
| 2026-09-20 | `seat` 테이블·`hotspot` 좌석 선택은 M2에서 보류. `reservation(event_id, seat_no, user_id)` 하나 + `Random(seed)` 균등 선택 | 중복 지표는 GROUP BY seat_no로 충분. `seat.status`는 lost update가 생기는 두 번째 장소가 되어 오버셀 실험과 섞임. N=100·M=1,000이면 균등만으로 좌석당 10명. |
| 2026-09-20 | 유니크 인덱스 토글은 web이 실행 시작 시 `TRUNCATE reservation` 후 `information_schema`로 확인해 CREATE/DROP | 이전 NONE 실행의 중복 행이 남으면 인덱스 생성이 실패. 동시 실행 1건이라 DDL 경합 없음. 리포트는 메모리에 있어 행을 지워도 잃는 것이 없다. |
| 2026-09-20 | `reservation`에 보조 인덱스 없음, `created_at` 없음 | 실행당 1,000행 이하라 풀 스캔으로 충분. 쓰는 곳 없는 컬럼은 두지 않는다. |
| 2026-09-20 | 좌석 거절은 `Outcome.SEAT_TAKEN`(앱 조회)과 `DUPLICATE_KEY`(DB 거절) 두 값으로 분리 | 플래그 없이 `duplicateKeyCount = count(DUPLICATE_KEY)` 한 줄. DoD가 요구하는 중복키 카운트가 그대로 나온다. |
| 2026-09-20 | DEGRADED 기준선 키에 `doubleBooking` 포함 | 좌석 NONE 경로가 sleep을 하나 더 하므로 같은 좌석 전략끼리만 비교해야 한다. |
| 2026-09-20 | `LOCAL_LOCK`은 `synchronized`가 아니라 `ReentrantLock` | JDK 21 가상 스레드는 `synchronized` 대기 중 캐리어를 핀한다. M2 좌석 단계가 락 밖에서 DB를 쓰자 캐리어 12개가 락 대기로 묶여 커넥션 풀이 고갈됐다(앱 1대에서 오류 961건, 처리량 10 rps). `ReentrantLock` 대기는 언마운트되고 "JVM 로컬 락은 앱 2대에서 무너진다"는 교훈은 같다. |
| 2026-09-20 | M1 결과 표는 좌석 전략 `NONE` 기준으로 재측정하고, M0의 10회 표는 제거 | 좌석 단계가 카운터 앞에 서면서 좌석을 잡은 요청만 카운터에 도달한다. `NONE` 오버셀은 900 고정이 아니라 수십~수백으로 줄고 처리량도 낮아져, 이전 수치는 더 이상 재현되지 않는다. |
| 2026-09-20 | `REDIS_AS_SOT`는 카운터 단계를 Redis `DECR`로 바꾸고 `oversell` 전략을 무시한다. UI가 라디오를 비활성화 | "SoT가 Redis로 옮겨가면 DB 락 전략은 의미가 없다"가 교훈. write-through(DB가 결정하고 `DECR` 추가)는 축이 직교하지만 스펙의 "Redis가 SoT" 시나리오가 사라진다. |
| 2026-09-20 | `REDIS_AS_SOT`의 DB 쓰기는 동기(`remaining - 1`). 스펙의 "비동기"는 보류 | write-behind 큐는 관측 가치가 없고 원장 검증만 어렵게 한다. |
| 2026-09-20 | `phantomStockViews`·`staleWindowMs`는 판정에 넣지 않는다 | 어떤 전략도 0이 안 되는 지표를 FAIL 조건에 넣으면 M3부터 모든 실행이 FAIL. 학습 목표 4("stale read는 설계 선택")대로 별도 표시 + 고정 해설. |
| 2026-09-20 | 조회 부하는 전용 조회자 상수(2개, 10ms 간격, 예매 종료 후 꼬리 2초). 파라미터로 노출하지 않음 | 예매 직전 조회 모델은 조회가 출발선에 몰려 매진 이후 조회가 없다. 꼬리 2초는 `TTL_SHORT`의 1초 만료를 창 안에 잡기 위함. |
| 2026-09-20 | `soldOutAt` = N번째 `OK` 응답이 web에 도착한 시각 | DB가 0이 된 시점보다 약간 늦어 phantom을 적게 세는 쪽으로 보수적. DB 폴링 없이 기존 응답 처리에 한 줄. |
| 2026-09-20 | cache-aside 미스 경로는 DB 읽기 → `sleep(raceWindowMs)` → `SET`. `REDIS_AS_SOT`의 `DECR`에는 sleep 없음 | 다른 축과 같은 read-modify-write 원칙. 이 sleep이 `INVALIDATE_ON_WRITE`의 삭제-재적재 경합을 재현시킨다. `DECR`은 원자라 `CONDITIONAL_UPDATE`와 같은 이유로 sleep이 없다. |
| 2026-09-20 | 지표에 `staleWindowMs`(마지막 phantom − soldOutAt)와 `viewDbReads` 추가 | phantom 수는 폴링 주기에 좌우되지만 ms 창은 직관적. `viewDbReads`가 이 축의 비용 면(캐시가 DB를 얼마나 막는가). |
| 2026-09-20 | `INVALIDATE_ON_WRITE`의 `DEL`은 `tx.execute` 반환 뒤(커밋 이후) | 커밋 전에 지우면 조회자가 커밋 전 값을 재적재하는 별개의 버그. |
| 2026-09-20 | DEGRADED 기준선 키에 `cacheConsistency` 포함 | `INVALIDATE_ON_WRITE`는 쓰기마다 `DEL`, `REDIS_AS_SOT`는 경로가 다르므로 같은 캐시 전략끼리만 비교. |
| 2026-09-20 | M3 DoD는 20ms 4종에 더해 `INVALIDATE_ON_WRITE`를 `raceWindowMs=200`으로 한 번 더 돌린다 | 20ms에서는 판매 100건이 조회자의 첫 SELECT가 풀 대기에서 돌아오기 전에 끝나 재적재가 대개 0을 써서 경합이 일부 실행에서만 걸린다(DoD 2/5, 근소 사례까지 세면 3/5). 200ms면 재적재가 마지막 쓰기를 가로질러 매번 걸린다. 경합은 조회 지연 대 쓰기 폭주의 성질이고 창 노브는 그것을 보이게 할 뿐이다. |
| 2026-09-21 | 공유 링크는 `RunSpec` 여덟 키를 쿼리스트링에 담는 재현 링크. 리포트 영속화 없음 | DoD의 "재현"은 실험이지 숫자가 아니다. 결과 링크는 같은 서버 접근이 필요하고 테이블·엔드포인트가 는다. |
| 2026-09-21 | 시드는 UI가 31비트 정수로 생성해 항상 보낸다. 서버 `Random.nextLong()` 기본값은 curl용 | JS 숫자는 2^53까지만 정확해 서버 Long 시드를 링크에 실으면 깨진다. |
| 2026-09-21 | "같은 시드로 재실행" 버튼 없음. 시드 입력란이 값을 유지하고 "Random"이 비운다 | 버튼 하나로 두 기능. 스펙의 세 버튼 중 재실행은 실행과 같은 경로다. |
| 2026-09-21 | 비교는 브라우저가 직전 리포트 1건을 변수로 기억. `compare` API 없음 | 브라우저가 이미 두 리포트를 갖고 있다. 기준 실행 고정은 필요해지면 그때. |
| 2026-09-21 | 해설은 라디오 옆 `<details>`, 영어 기본 + 한국어 토글(`<html lang>` + `localStorage`). 링크에 언어 없음 | 저장소 방문자 기준 영어. 결과 직후 고른 전략 3개만 자동 펼침. |
| 2026-09-21 | 전략 이름·파라미터 필드명·지표 키·판정 단어·서버 오류는 번역하지 않는다 | 코드·API·README와 같은 단어여야 대조가 된다. |
| 2026-09-21 | UI 순수 함수(링크 직렬화·비교 표·판정 이유·문자열 사전)는 `static/lib.js` ES 모듈로 분리, `node --test`로 검증 | 빌드 단계 없이 브라우저와 Node가 같은 파일을 import. UI 로직에 검증이 하나 생긴다. |
| 2026-09-21 | 데모 GIF는 Playwright `recordVideo` + `ffmpeg` 팔레트 변환(`scripts/demo-gif.sh`) | 프레임 캡처 루프 없이 녹화 한 번. 스크립트가 공유 링크를 열어 DoD를 스스로 증명한다. |
