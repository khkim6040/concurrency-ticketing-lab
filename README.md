# concurrency-ticketing-lab

A simulator that lets you switch race conditions in a ticket booking system on and off and see the result as numbers. You pick N seats and M concurrent users, choose a defense strategy, and get back both a consistency score and a throughput score.

The whole point is one claim: the more consistency you enforce, the less throughput you get. If turning every defense on looked like the right answer, the tool would be teaching the wrong lesson, so both axes are always shown side by side.

## What works today (M0)

- Two app servers and MySQL, started with Docker Compose. With a single server every problem here can be solved by a JVM `synchronized` block, which is exactly the wrong lesson, so two is the minimum.
- Two oversell strategies: `NONE` (read, check, write) and `CONDITIONAL_UPDATE` (`UPDATE ... WHERE remaining > 0`).
- A `raceWindowMs` knob that sleeps between the read and the write to widen the race window on purpose. Without it, oversell shows up in some runs and not others, which is useless for teaching.
- After a run finishes the DB is counted directly to produce `oversoldCount`, `ledgerMismatch`, throughput, p50/p95/p99 and a PASS or FAIL verdict.

Results from ten runs each with N=100, M=1,000, raceWindowMs=20, two app instances:

| Strategy | Verdict | Oversold | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- |
| NONE | FAIL 10/10 | 900 | 750 to 3,500 | 250 to 1,200 ms |
| CONDITIONAL_UPDATE | PASS 10/10 | 0 | 4,100 to 5,900 | 160 to 230 ms |

Oversold is exactly 900 every time under `NONE` because a 20 ms window is wide enough for all 1,000 users to read "100 remaining" before anyone writes. Drop `raceWindowMs` to 0 and you get the real-world version, where the bug only shows up when you are unlucky.

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
- [ ] M1 LOCAL_LOCK / PESSIMISTIC / OPTIMISTIC, one-vs-two instance toggle, performance metrics, seat grid
- [ ] M2 Double booking. `seat` table, unique index toggled at runtime
- [ ] M3 Cache layer. Redis, four stale-read strategies
- [ ] M4 Side-by-side comparison, seed-based share links, per-strategy explanations

## Documents

- [Spec and roadmap](concurrency-ticketing-lab_spec.md) (Korean)
- [Decision log](docs/decisions.md) (Korean). One line per choice the spec left open or where the code diverges from it.
- [M0 design](docs/superpowers/specs/2026-09-20-m0-design.md) and [M0 implementation plan](docs/superpowers/plans/2026-09-20-m0-skeleton.md) (Korean)
