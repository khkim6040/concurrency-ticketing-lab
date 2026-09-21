# concurrency-ticketing-lab

A simulator that lets you switch race conditions in a ticket booking system on and off and see the result as numbers. You pick N seats and M concurrent users, choose a defense strategy, and get back both a consistency score and a throughput score.

The whole point is one claim: the more consistency you enforce, the less throughput you get. If turning every defense on looked like the right answer, the tool would be teaching the wrong lesson, so both axes are always shown side by side.

![Two runs with the same seed: oversell NONE fails, CONDITIONAL_UPDATE with a unique index passes, compared side by side](docs/demo.gif)

## What works today (M0 to M4)

- Two app servers and MySQL, started with Docker Compose. With a single server every problem here can be solved by a JVM lock, which is exactly the wrong lesson, so two is the minimum.
- A `raceWindowMs` knob that sleeps between the read and the write to widen the race window on purpose. Without it, oversell shows up in some runs and not others, which is useless for teaching.
- After a run finishes the DB is counted directly to produce `oversoldCount`, `ledgerMismatch`, throughput, p50/p95/p99 and a PASS or FAIL verdict.
- Five oversell strategies: `NONE`, `LOCAL_LOCK` (a JVM `ReentrantLock`; `synchronized` would pin virtual-thread carriers on JDK 21), `CONDITIONAL_UPDATE`, `PESSIMISTIC` (`SELECT ... FOR UPDATE` inside a transaction) and `OPTIMISTIC` (version check with unbounded retry). The delay is injected between the read and the write in every strategy that has one, so a lock held while sleeping shows up as throughput lost.
- `retryCount` and `dbConnectionPeak` (HikariCP active connections, sampled per request on the app side), plus a `DEGRADED` verdict when a consistent run does at most half the throughput of the most recent `NONE` run with the same parameters.
- A seat grid that fills in while the run is in flight, polled every 200 ms. The first N cells are seats: gray unsold, green sold once, red sold to two or more users. Orange cells appended after the N seats are not seats; there is one per confirmed booking past capacity.
- A `reservation` row per confirmed seat. Each user picks a seat from `Random(seed)`, holds it first and only then takes a ticket from the counter, so the locks and transactions of the oversell axis only ever wrap the counter step. Two strategies: `NONE` (application check, then insert) and `UNIQUE_CONSTRAINT` (a unique index on `(event_id, seat_no)` that the web tier creates or drops at the start of every run).
- `doubleBookedSeats` counted directly in the DB after the run, `duplicateKeyCount` from the app's `DuplicateKeyException`s, and red cells in the seat grid.
- A Redis stock cache and a read path (`GET /api/stock`). Two viewer threads poll it every 10 ms during the run and for two seconds after, and every read that returns a positive remaining count after the N-th confirmed booking is a `phantomStockView`. Four strategies: `NONE` (cache-aside, 60 s TTL), `TTL_SHORT` (1 s TTL), `INVALIDATE_ON_WRITE` (delete the key after every decrement) and `REDIS_AS_SOT` (the counter lives in Redis, `DECR` decides, and the oversell strategy is ignored).
- `phantomStockViews`, `staleWindowMs` (how long after sell-out the last stale read was served) and `viewDbReads` (how many reads the cache did not absorb). None of them affect the verdict: a stale read is a design choice, and the UI says so under the report.
- Share links. The address bar becomes `?seatCount=…&seed=…&oversell=…` after every run, and opening such a link fills the form and runs it. The seed fixes which seat each user picks, so the link reproduces the experiment; the oversold count and the throughput still depend on timing and vary from run to run. The seed field keeps its value after a run, so pressing Run again replays the same seed.
- A comparison table. The browser remembers the previous run and shows previous, current and diff columns, with the parameters that changed in bold. Run `NONE` first, change one strategy, run again, and the throughput cost of that strategy is one row.
- A "why?" note next to every strategy, two or three sentences each, collapsed until a run finishes and then opened for the strategies that ran. The UI is English by default with a Korean toggle; strategy names, field names and metric keys stay as they are in the code.

Since M2, every request first takes a seat and only seat winners reach the counter. Under `NONE` this means oversold is a few dozen to a few hundred rather than a fixed 900, and it varies run to run because which seats collide is random. The seat step also adds a sleep in front of every strategy, so the throughput below is lower than what M1 originally measured for the same strategies.

Results from three runs each with N=100, M=1,000, raceWindowMs=20, `doubleBooking=NONE`, all five strategies, one and two app instances (`./scripts/dod.sh`):

Since M3 two viewer threads poll the stock cache during every run, so these tables were remeasured on this branch; under the default cache strategy they add one or two database reads per run and the numbers move only within run-to-run noise.

| Strategy | Apps | Verdict | Oversold | Ledger | Double booked | Throughput (req/s) | p99 | Retries | Conn peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | 1 | FAIL 3/3 | 112 to 129 | -224 to -207 | 66 to 71 | 2,824 to 4,545 | 210 to 328 ms | 0 | 20 |
| NONE | 2 | FAIL 3/3 | 73 to 113 | -207 to -167 | 53 to 60 | 952 to 4,201 | 225 to 1,031 ms | 0 | 17 to 20 |
| LOCAL_LOCK | 1 | FAIL 3/3 | 0 | 0 | 23 to 26 | 152 to 190 | 5,019 to 6,344 ms | 0 | 20 |
| LOCAL_LOCK | 2 | FAIL 3/3 | 94 to 100 | -100 to -94 | 59 to 67 | 290 to 386 | 2,403 to 3,228 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 1 | FAIL 3/3 | 0 | 0 | 23 to 29 | 783 to 2,702 | 356 to 1,262 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 2 | FAIL 3/3 | 0 | 0 | 19 to 29 | 2,500 to 2,849 | 343 to 389 ms | 0 | 20 |
| PESSIMISTIC | 1 | FAIL 3/3 | 0 | 0 | 24 to 26 | 90 to 98 | 9,981 to 10,851 ms | 0 | 20 |
| PESSIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 24 to 28 | 94 to 104 | 9,298 to 10,357 ms | 0 | 20 |
| OPTIMISTIC | 1 | FAIL 3/3 | 0 | 0 | 25 to 28 | 414 to 418 | 2,384 to 2,403 ms | 14,954 to 22,162 | 20 |
| OPTIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 24 to 31 | 417 to 423 | 2,353 to 2,390 ms | 16,603 to 18,224 | 20 |

`LOCAL_LOCK` is still the point of the milestone. On one instance the lock serializes every request and the counter balances exactly: oversold and ledger are both 0. On two instances each JVM serializes only its own half, the two halves race each other, and it oversells by 94 to 100 out of 100 seats. None of the counter-consistent strategies show `PASS` or `DEGRADED` in this table, because under the default seat strategy (`doubleBooking=NONE`) a seat can still go to two users even when the counter total is exact, and that failure is counted here too; the table below isolates that axis and shows the fix. `PESSIMISTIC` pins the connection peak at the pool size because each transaction holds its connection while it sleeps inside the row lock. `OPTIMISTIC` keeps the counter exact and is faster than either lock, but it pays for that with tens of thousands of retries.

Results from ten runs each with the same parameters, `CONDITIONAL_UPDATE`, two app instances, both `doubleBooking` strategies:

| Oversell | Double booking | Verdict | Oversold | Ledger | Double booked | Dup keys | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CONDITIONAL_UPDATE | NONE | FAIL 10/10 | 0 | 0 | 21 to 31 | 0 | 2,277 to 2,857 | 341 to 429 ms |
| CONDITIONAL_UPDATE | UNIQUE_CONSTRAINT | PASS 10/10 | 0 | 0 | 0 | 900 | 5,076 to 5,917 | 159 to 190 ms |

`CONDITIONAL_UPDATE` alone keeps the count right and still fails because seats overlap, and the unique index is the only layer that rejects the second reservation regardless of what the code above it did.

The reverse does not hold. The unique index lets at most N requests reach the counter at all, so `oversoldCount` is 0 under `UNIQUE_CONSTRAINT` whatever the oversell strategy is. Try `oversell=NONE` with `doubleBooking=UNIQUE_CONSTRAINT` and read `ledgerMismatch`, not `oversoldCount`. Also, `PASS` in the `UNIQUE_CONSTRAINT` row means consistent only: the DoD script records no `NONE` baseline for that seat strategy, so the `DEGRADED` check never runs there.

Results from five runs each with the same parameters, `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT`, two app instances, all four `cacheConsistency` strategies:

| Cache | Verdict | Phantom views | Stale window | DB reads / views | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| NONE | PASS 5/5 | 256 to 279 | 2,032 to 2,055 ms | 1 to 2 / 267 to 294 | 5,154 to 5,847 | 160 to 189 ms |
| TTL_SHORT | PASS 5/5 | 120 to 128 | 877 to 912 ms | 5 to 6 / 273 to 285 | 5,208 to 6,024 | 158 to 182 ms |
| INVALIDATE_ON_WRITE | PASS 5/5 | 274 to 286 (2/5 runs) | 2,048 to 2,056 ms (2/5 runs) | 6 to 7 / 265 to 292 | 5,235 to 5,952 | 161 to 181 ms |
| REDIS_AS_SOT | PASS 5/5 | 0 | 0 ms | 0 / 276 to 294 | 5,291 to 5,882 | 162 to 182 ms |
| `INVALIDATE_ON_WRITE` (raceWindowMs=200) | PASS 5/5 | 240 to 250 (5/5 runs) | 2,030 to 2,052 ms | 2 / 242 to 252 | 5,319 to 6,060 | 160 to 183 ms |

`NONE` keeps the first value it saw until the TTL expires, which is after the run ends. `TTL_SHORT` bounds the window to the TTL and no lower; the TTL that closes it is zero, which is no cache. `INVALIDATE_ON_WRITE` is right until a reader that missed the cache, read the database and slept lands its stale value after the last write's delete, and then nothing deletes it again. At the default 20 ms window the hundred sales finish before the viewer's first read comes back, so that reader usually refills with zero and the race lands only in some runs (2/5 here, 3/5 counting a near miss); at 200 ms the refill straddles the last write and the stale value sticks in every run (5/5). The race is a property of the read latency against the write burst, not of the window knob, which only makes it visible. `REDIS_AS_SOT` has no second copy, so the only staleness left is the time between reading a value and looking at it, which the measurement shows as zero in all five runs. The cost side is the DB reads column: the shorter the window, the less the cache absorbs.

One more of the five 20 ms runs showed a single phantom view 24 ms after sell-out: the refill landed and was wiped by a delete that arrived late, because sell-out is timed at the N-th OK response reaching the web tier, not at the commit. Counting that run the window opened in 3 of 5.

## Running it

You need JDK 21 and Docker.

```bash
docker compose up -d --build
open http://localhost:8080      # parameter form and results
./scripts/dod.sh                # reproduces the tables above
./scripts/demo-gif.sh           # re-records docs/demo.gif (needs Node and ffmpeg)
```

`./gradlew test` runs the unit tests and `node --test src/test/js/ui.test.mjs` the UI functions, both without Docker.

A link that reproduces the second half of the GIF on your own stack:

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
→ 202 { "runId": "..." }   (409 if a run is already in progress)

GET /api/runs/{runId}
→ { "status": "RUNNING" | "DONE" | "ERROR", "report": { ... } }
```

## How it is put together

One Spring Boot module, built into one image, with the role picked by profile. The `app` profile exposes only the reservation endpoint. The `web` profile exposes the run API and the load generator. App instances are stateless: the strategy and the delay come in the request body, so switching strategies never needs a restart.

The load generator spawns M virtual threads and lines them up behind a single `CountDownLatch`. Requests have to originate on the server rather than in the browser, otherwise start-time jitter alone is enough to make the race disappear.

Database access goes through `JdbcClient` with the SQL written out by hand. JPA is deliberately absent. The lock layer (`FOR UPDATE`, conditional updates, version checks) has to be visible in the code or there is nothing to learn from. Redis holds the stock cache under `stock:{eventId}`; under `REDIS_AS_SOT` it holds the counter itself.

```
src/main/kotlin/lab/
  Models.kt                 shared types and report aggregation
  app/ReservationService.kt seat step, then one SQL path per counter strategy, or DECR under REDIS_AS_SOT
  app/StockCache.kt         stock:{eventId} read path, DECR counter, DEL on write
  app/ReserveController.kt  POST /api/reserve
  web/LoadRunner.kt         simultaneous start, result collection
  web/RunController.kt      POST /api/runs, GET /api/runs/{id}
  static/index.html         form, seat grid, comparison table, bilingual notes
  static/lib.js             share-link encoding, comparison rows, verdict reason, strings; also run by node --test
db/schema.sql               MySQL initdb
scripts/dod.sh              reproducibility check
```

## Roadmap

- [x] M0 End-to-end skeleton. Oversell NONE / CONDITIONAL_UPDATE, two app instances, numbers-only UI
- [x] M1 LOCAL_LOCK / PESSIMISTIC / OPTIMISTIC, one-vs-two instance toggle, performance metrics, seat grid
- [x] M2 Double booking. `reservation` table, unique index toggled at runtime
- [x] M3 Cache layer. Redis, four stale-read strategies, `phantomStockViews`
- [x] M4 Side-by-side comparison, seed-based share links, per-strategy explanations

## Documents

- [Spec and roadmap](concurrency-ticketing-lab_spec.md) (Korean)
- [Decision log](docs/decisions.md) (Korean). One line per choice the spec left open or where the code diverges from it.
- [M0 design](docs/superpowers/specs/2026-09-20-m0-design.md) and [M0 implementation plan](docs/superpowers/plans/2026-09-20-m0-skeleton.md) (Korean)
- [M1 design](docs/superpowers/specs/2026-09-20-m1-design.md) and [M1 implementation plan](docs/superpowers/plans/2026-09-20-m1-strategies.md) (Korean)
- [M2 design](docs/superpowers/specs/2026-09-20-m2-design.md) and [M2 implementation plan](docs/superpowers/plans/2026-09-20-m2-double-booking.md) (Korean)
- [M3 design](docs/superpowers/specs/2026-09-20-m3-design.md) and [M3 implementation plan](docs/superpowers/plans/2026-09-20-m3-cache-layer.md) (Korean)
- [M4 design](docs/superpowers/specs/2026-09-21-m4-design.md) and [M4 implementation plan](docs/superpowers/plans/2026-09-21-m4-compare-share.md) (Korean)
