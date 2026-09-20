# concurrency-ticketing-lab

A simulator that lets you switch race conditions in a ticket booking system on and off and see the result as numbers. You pick N seats and M concurrent users, choose a defense strategy, and get back both a consistency score and a throughput score.

The whole point is one claim: the more consistency you enforce, the less throughput you get. If turning every defense on looked like the right answer, the tool would be teaching the wrong lesson, so both axes are always shown side by side.

## What works today (M0 + M1 + M2)

- Two app servers and MySQL, started with Docker Compose. With a single server every problem here can be solved by a JVM lock, which is exactly the wrong lesson, so two is the minimum.
- A `raceWindowMs` knob that sleeps between the read and the write to widen the race window on purpose. Without it, oversell shows up in some runs and not others, which is useless for teaching.
- After a run finishes the DB is counted directly to produce `oversoldCount`, `ledgerMismatch`, throughput, p50/p95/p99 and a PASS or FAIL verdict.
- Five oversell strategies: `NONE`, `LOCAL_LOCK` (a JVM `ReentrantLock`; `synchronized` would pin virtual-thread carriers on JDK 21), `CONDITIONAL_UPDATE`, `PESSIMISTIC` (`SELECT ... FOR UPDATE` inside a transaction) and `OPTIMISTIC` (version check with unbounded retry). The delay is injected between the read and the write in every strategy that has one, so a lock held while sleeping shows up as throughput lost.
- `retryCount` and `dbConnectionPeak` (HikariCP active connections, sampled per request on the app side), plus a `DEGRADED` verdict when a consistent run does at most half the throughput of the most recent `NONE` run with the same parameters.
- A seat grid that fills in while the run is in flight, polled every 200 ms. Green is a sold seat, orange is a seat sold past capacity.
- A `reservation` row per confirmed seat. Each user picks a seat from `Random(seed)`, holds it first and only then takes a ticket from the counter, so the oversell and double-booking axes never interfere. Two strategies: `NONE` (application check, then insert) and `UNIQUE_CONSTRAINT` (a unique index on `(event_id, seat_no)` that the web tier creates or drops at the start of every run).
- `doubleBookedSeats` counted directly in the DB after the run, `duplicateKeyCount` from the app's `DuplicateKeyException`s, and red cells in the seat grid.

Since M2, every request first takes a seat and only seat winners reach the counter. Under `NONE` this means oversold is a few dozen to a few hundred rather than a fixed 900, and it varies run to run because which seats collide is random. The seat step also adds a sleep in front of every strategy, so the throughput below is lower than what M1 originally measured for the same strategies.

Results from three runs each with N=100, M=1,000, raceWindowMs=20, `doubleBooking=NONE`, all five strategies, one and two app instances (`./scripts/dod.sh`):

| Strategy | Apps | Verdict | Oversold | Ledger | Double booked | Throughput (req/s) | p99 | Retries | Conn peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | 1 | FAIL 3/3 | 84 to 508 | -602 to -177 | 45 to 94 | 706 to 3,125 | 313 to 1,308 ms | 0 | 19 to 20 |
| NONE | 2 | FAIL 3/3 | 51 to 69 | -161 to -143 | 37 to 52 | 482 to 3,246 | 292 to 2,054 ms | 0 | 13 to 20 |
| LOCAL_LOCK | 1 | FAIL 3/3 | 0 | 0 | 25 to 27 | 192 to 207 | 4,563 to 4,977 ms | 0 | 20 |
| LOCAL_LOCK | 2 | FAIL 3/3 | 59 to 100 | -100 to -73 | 44 to 62 | 255 to 479 | 1,813 to 3,657 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 1 | FAIL 3/3 | 0 | 0 | 21 to 26 | 1,694 to 2,053 | 475 to 574 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 2 | FAIL 3/3 | 0 | 0 | 21 to 26 | 2,061 to 2,358 | 410 to 467 ms | 0 | 20 |
| PESSIMISTIC | 1 | FAIL 3/3 | 0 | 0 | 25 to 26 | 84 to 96 | 10,174 to 11,596 ms | 0 | 20 |
| PESSIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 21 to 27 | 99 to 121 | 7,978 to 9,799 ms | 0 | 20 |
| OPTIMISTIC | 1 | FAIL 3/3 | 0 | 0 | 20 to 24 | 407 to 411 | 2,426 to 2,441 ms | 15,502 to 20,686 | 20 |
| OPTIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 23 to 25 | 414 to 419 | 2,378 to 2,407 ms | 17,085 to 18,935 | 20 |

`LOCAL_LOCK` is still the point of the milestone. On one instance the lock serializes every request and the counter balances exactly: oversold and ledger are both 0. On two instances each JVM serializes only its own half, the two halves race each other, and it can oversell by close to the whole seat count. None of the counter-consistent strategies show `PASS` or `DEGRADED` in this table, because under the default seat strategy (`doubleBooking=NONE`) a seat can still go to two users even when the counter total is exact, and that failure is counted here too; the table below isolates that axis and shows the fix. `PESSIMISTIC` pins the connection peak at the pool size because each transaction holds its connection while it sleeps inside the row lock. `OPTIMISTIC` keeps the counter exact and is faster than either lock, but it pays for that with tens of thousands of retries.

Results from ten runs each with the same parameters, `CONDITIONAL_UPDATE`, two app instances, both `doubleBooking` strategies:

| Oversell | Double booking | Verdict | Oversold | Ledger | Double booked | Dup keys | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CONDITIONAL_UPDATE | NONE | FAIL 10/10 | 0 | 0 | 23 to 30 | 0 | 1,067 to 2,380 | 412 to 922 ms |
| CONDITIONAL_UPDATE | UNIQUE_CONSTRAINT | PASS 10/10 | 0 | 0 | 0 | 900 | 4,016 to 6,097 | 158 to 234 ms |

`CONDITIONAL_UPDATE` alone keeps the count right and still fails because seats overlap, and the unique index is the only layer that rejects the second reservation regardless of what the code above it did.

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
  "raceWindowMs": 20, "strategies": { "oversell": "NONE", "doubleBooking": "NONE" }
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
  app/ReservationService.kt seat step, then one SQL path per counter strategy
  app/ReserveController.kt  POST /api/reserve
  web/LoadRunner.kt         simultaneous start, result collection
  web/RunController.kt      POST /api/runs, GET /api/runs/{id}
db/schema.sql               MySQL initdb
scripts/dod.sh              reproducibility check
```

## Roadmap

- [x] M0 End-to-end skeleton. Oversell NONE / CONDITIONAL_UPDATE, two app instances, numbers-only UI
- [x] M1 LOCAL_LOCK / PESSIMISTIC / OPTIMISTIC, one-vs-two instance toggle, performance metrics, seat grid
- [x] M2 Double booking. `reservation` table, unique index toggled at runtime
- [ ] M3 Cache layer. Redis, four stale-read strategies
- [ ] M4 Side-by-side comparison, seed-based share links, per-strategy explanations

## Documents

- [Spec and roadmap](concurrency-ticketing-lab_spec.md) (Korean)
- [Decision log](docs/decisions.md) (Korean). One line per choice the spec left open or where the code diverges from it.
- [M0 design](docs/superpowers/specs/2026-09-20-m0-design.md) and [M0 implementation plan](docs/superpowers/plans/2026-09-20-m0-skeleton.md) (Korean)
- [M1 design](docs/superpowers/specs/2026-09-20-m1-design.md) and [M1 implementation plan](docs/superpowers/plans/2026-09-20-m1-strategies.md) (Korean)
- [M2 design](docs/superpowers/specs/2026-09-20-m2-design.md) and [M2 implementation plan](docs/superpowers/plans/2026-09-20-m2-double-booking.md) (Korean)
