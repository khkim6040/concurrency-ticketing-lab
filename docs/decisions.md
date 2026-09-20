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
