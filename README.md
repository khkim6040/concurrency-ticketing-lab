# concurrency-ticketing-lab

A simulator that lets you switch race conditions in a ticket booking system on and off and see the result as numbers. You pick N seats and M concurrent users, choose a defense strategy, and get back both a consistency score and a throughput score.

The whole point is one claim: the more consistency you enforce, the less throughput you get. If turning every defense on looked like the right answer, the tool would be teaching the wrong lesson, so both axes are always shown side by side.

## What works today (M0 + M1)

- Two app servers and MySQL, started with Docker Compose. With a single server every problem here can be solved by a JVM `synchronized` block, which is exactly the wrong lesson, so two is the minimum.
- A `raceWindowMs` knob that sleeps between the read and the write to widen the race window on purpose. Without it, oversell shows up in some runs and not others, which is useless for teaching.
- After a run finishes the DB is counted directly to produce `oversoldCount`, `ledgerMismatch`, throughput, p50/p95/p99 and a PASS or FAIL verdict.
- Five oversell strategies: `NONE`, `LOCAL_LOCK` (JVM `synchronized`), `CONDITIONAL_UPDATE`, `PESSIMISTIC` (`SELECT ... FOR UPDATE` inside a transaction) and `OPTIMISTIC` (version check with unbounded retry). The delay is injected between the read and the write in every strategy that has one, so a lock held while sleeping shows up as throughput lost.
- `retryCount` and `dbConnectionPeak` (HikariCP active connections, sampled per request on the app side), plus a `DEGRADED` verdict when a consistent run does at most half the throughput of the most recent `NONE` run with the same parameters.
- A seat grid that fills in while the run is in flight, polled every 200 ms. Green is a sold seat, orange is a seat sold past capacity.

Results from ten runs each with N=100, M=1,000, raceWindowMs=20, two app instances:

| Strategy | Verdict | Oversold | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- |
| NONE | FAIL 10/10 | 900 | 750 to 3,500 | 250 to 1,200 ms |
| CONDITIONAL_UPDATE | PASS 10/10 | 0 | 4,100 to 5,900 | 160 to 230 ms |

Oversold is exactly 900 every time under `NONE` because a 20 ms window is wide enough for all 1,000 users to read "100 remaining" before anyone writes. Drop `raceWindowMs` to 0 and you get the real-world version, where the bug only shows up when you are unlucky.

Results from three runs each with the same parameters, all five strategies, one and two app instances (`./scripts/dod.sh`):

| Strategy | Apps | Verdict | Oversold | Throughput (req/s) | p99 | Retries | Conn peak |
| --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | 1 | FAIL 3/3 | 900 | 780 to 2,860 | 290 to 1,100 ms | 0 | 13 to 20 |
| NONE | 2 | FAIL 3/3 | 900 | 870 to 2,970 | 310 to 1,130 ms | 0 | 17 to 20 |
| LOCAL_LOCK | 1 | DEGRADED 3/3 | 0 | 41 | 23.6 to 23.8 s | 0 | 1 |
| LOCAL_LOCK | 2 | FAIL 3/3 | 98 to 100 | 82 to 84 | 11.7 to 11.9 s | 0 | 1 |
| CONDITIONAL_UPDATE | 1 | PASS 3/3 | 0 | 5,100 to 5,800 | 160 to 190 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 2 | PASS 3/3 | 0 | 4,800 to 5,300 | 180 to 200 ms | 0 | 20 |
| PESSIMISTIC | 1 | DEGRADED 3/3 | 0 | 42 to 43 | 22.9 to 23.3 s | 0 | 20 |
| PESSIMISTIC | 2 | DEGRADED 3/3 | 0 | 42 | 23.0 to 23.3 s | 0 | 20 |
| OPTIMISTIC | 1 | DEGRADED 3/3 | 0 | 425 to 429 | 2.3 s | 51,000 to 54,000 | 20 |
| OPTIMISTIC | 2 | DEGRADED 3/3 | 0 | 408 to 410 | 2.4 s | 50,000 to 51,000 | 20 |

`LOCAL_LOCK` is the point of the milestone. On one instance the `synchronized` block serializes every request and the ledger balances. On two instances each JVM serializes only its own half, the two halves race each other, and it oversells by about the whole seat count. `DEGRADED` means the run was consistent but did at most half the throughput of the `NONE` baseline with the same parameters, which is what "safe but unusable" looks like as a number. `PESSIMISTIC` pins the connection peak at the pool size because each transaction holds its connection while it sleeps inside the row lock. `OPTIMISTIC` is consistent and ten times faster than the lock, but it pays for that with fifty thousand retries.

## Running it

You need JDK 21 and Docker.

```bash
docker compose up -d --build
open http://localhost:8080      # parameter form and results
./scripts/dod.sh                # reproduces the table above
```

`./gradlew test` runs the unit tests without Docker.

## API

```
POST /api/runs
{
  "seatCount": 100, "userCount": 1000, "appInstances": 2,
  "raceWindowMs": 20, "strategies": { "oversell": "NONE" }
}
→ 202 { "runId": "..." }   (409 if a run is already in progress)

GET /api/runs/{runId}
→ { "status": "RUNNING" | "DONE" | "ERROR", "report": { ... } }
```

## How it is put together

One Spring Boot module, built into one image, with the role picked by profile. The `app` profile exposes only the reservation endpoint. The `web` profile exposes the run API and the load generator. App instances are stateless: the strategy and the delay come in the request body, so switching strategies never needs a restart.

The load generator spawns M virtual threads and lines them up behind a single `CountDownLatch`. Requests have to originate on the server rather than in the browser, otherwise start-time jitter alone is enough to make the race disappear.

Database access goes through `JdbcClient` with the SQL written out by hand. JPA is deliberately absent. The lock layer (`FOR UPDATE`, conditional updates, version checks) has to be visible in the code or there is nothing to learn from.

```
src/main/kotlin/lab/
  Models.kt                 shared types and report aggregation
  app/ReservationService.kt one SQL path per strategy
  app/ReserveController.kt  POST /api/reserve
  web/LoadRunner.kt         simultaneous start, result collection
  web/RunController.kt      POST /api/runs, GET /api/runs/{id}
db/schema.sql               MySQL initdb
scripts/dod.sh              reproducibility check
```

## Roadmap

- [x] M0 End-to-end skeleton. Oversell NONE / CONDITIONAL_UPDATE, two app instances, numbers-only UI
- [x] M1 LOCAL_LOCK / PESSIMISTIC / OPTIMISTIC, one-vs-two instance toggle, performance metrics, seat grid
- [ ] M2 Double booking. `seat` table, unique index toggled at runtime
- [ ] M3 Cache layer. Redis, four stale-read strategies
- [ ] M4 Side-by-side comparison, seed-based share links, per-strategy explanations

## Documents

- [Spec and roadmap](concurrency-ticketing-lab_spec.md) (Korean)
- [Decision log](docs/decisions.md) (Korean). One line per choice the spec left open or where the code diverges from it.
- [M0 design](docs/superpowers/specs/2026-09-20-m0-design.md) and [M0 implementation plan](docs/superpowers/plans/2026-09-20-m0-skeleton.md) (Korean)
- [M1 design](docs/superpowers/specs/2026-09-20-m1-design.md) and [M1 implementation plan](docs/superpowers/plans/2026-09-20-m1-strategies.md) (Korean)
