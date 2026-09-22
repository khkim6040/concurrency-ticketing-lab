# concurrency-ticketing-lab

**English** · [한국어](README.ko.md)

A simulator that switches race conditions in a ticket booking system on and off and reports the result as numbers. Pick N seats and M concurrent users, choose a defense strategy, and you get back a consistency score and a throughput score.

The whole point is one claim: the more consistency you enforce, the less throughput you get. Both axes stay on screen at all times. A tool where switching every defense on is the right answer teaches the wrong thing.

![Two runs with the same seed: oversell NONE fails, CONDITIONAL_UPDATE with a unique index passes, compared side by side](docs/demo.gif)

## What it does

- Two app servers, MySQL and Redis on Docker Compose. With one server a JVM lock solves every problem here, and that is the wrong thing to learn, so two is the minimum.
- A `raceWindowMs` knob that sleeps between the read and the write, inside the lock or transaction, so every run reproduces the race.
- Three strategy axes, switched per run without a restart:
  - `oversell`: `NONE`, `LOCAL_LOCK` (a JVM `ReentrantLock`), `CONDITIONAL_UPDATE`, `PESSIMISTIC` (`SELECT ... FOR UPDATE`), `OPTIMISTIC` (version check with unbounded retry)
  - `doubleBooking`: `NONE` (check the seat, then insert) and `UNIQUE_CONSTRAINT` (a unique index on `(event_id, seat_no)`, created or dropped at the start of every run)
  - `cacheConsistency`: `NONE` (cache-aside, 60 s TTL), `TTL_SHORT` (1 s), `INVALIDATE_ON_WRITE`, and `REDIS_AS_SOT`, where the counter lives in Redis, `DECR` decides, and the oversell strategy is ignored
- Four numbers counted in the database once the run is over: `oversoldCount`, `ledgerMismatch`, `doubleBookedSeats`, `duplicateKeyCount`. Four more measured on the way: throughput, p50/p95/p99, `retryCount`, `dbConnectionPeak`. The verdict is PASS or FAIL, or DEGRADED when a consistent run does at most half the throughput of the matching `NONE` run.
- Two viewer threads poll the stock cache (`GET /api/stock`) every 10 ms during the run and for two seconds after. Reads that still show seats left after sell-out are `phantomStockViews`, reported with `staleWindowMs` and `viewDbReads`. None of the three affect the verdict: a stale read is a design choice, and the UI says so under the report.
- A seat grid that fills in while the run is in flight: gray unsold, green sold once, red sold to two or more users, orange for bookings past capacity.
- Share links. The address bar becomes `?seatCount=...&seed=...&oversell=...` after every run, and opening such a link fills the form and runs it. The seed fixes which seat each user picks, so the link reproduces the experiment, not the numbers.
- A comparison table of the previous run against the current one, with the parameters that changed in bold, and a "why?" note next to every strategy. English by default, Korean toggle.

## Results

Every request takes a seat first, and only seat winners reach the counter. So oversold under `NONE` lands somewhere between a few dozen and a few hundred rather than a fixed 900, and it moves run to run because which seats collide is random.

Three runs each with N=100, M=1,000, `raceWindowMs=20`, `doubleBooking=NONE` (`./scripts/dod.sh`):

| Strategy | Apps | Verdict | Oversold | Ledger | Throughput (req/s) | p99 | Retries | Conn peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | 2 | FAIL 3/3 | 73 to 113 | -207 to -167 | 952 to 4,201 | 225 to 1,031 ms | 0 | 17 to 20 |
| LOCAL_LOCK | 1 | FAIL 3/3 | 0 | 0 | 152 to 190 | 5,019 to 6,344 ms | 0 | 20 |
| LOCAL_LOCK | 2 | FAIL 3/3 | 94 to 100 | -100 to -94 | 290 to 386 | 2,403 to 3,228 ms | 0 | 20 |
| CONDITIONAL_UPDATE | 2 | FAIL 3/3 | 0 | 0 | 2,500 to 2,849 | 343 to 389 ms | 0 | 20 |
| PESSIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 94 to 104 | 9,298 to 10,357 ms | 0 | 20 |
| OPTIMISTIC | 2 | FAIL 3/3 | 0 | 0 | 417 to 423 | 2,353 to 2,390 ms | 16,603 to 18,224 | 20 |

`LOCAL_LOCK` is the pair of rows to read. On one instance the lock serializes every request and the counter balances exactly: oversold and ledger are both 0. On two instances each JVM serializes only its own half, the two halves race each other, and it oversells by 94 to 100 out of 100 seats. The other strategies are listed at two instances only, because the defense is in the database and their numbers at one instance are the same within noise. Every row says FAIL because `doubleBooking` is `NONE` here, so a seat can still go to two users even when the counter total is exact. The next table isolates that axis. `PESSIMISTIC` pins the connection peak at the pool size, because each transaction holds its connection while it sleeps inside the row lock. `OPTIMISTIC` keeps the counter exact and is faster than either lock, but pays for it with tens of thousands of retries.

Ten runs each with the same parameters, `CONDITIONAL_UPDATE`, two app instances, both `doubleBooking` strategies:

| Oversell | Double booking | Verdict | Oversold | Ledger | Double booked | Dup keys | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CONDITIONAL_UPDATE | NONE | FAIL 10/10 | 0 | 0 | 21 to 31 | 0 | 2,277 to 2,857 | 341 to 429 ms |
| CONDITIONAL_UPDATE | UNIQUE_CONSTRAINT | PASS 10/10 | 0 | 0 | 0 | 900 | 5,076 to 5,917 | 159 to 190 ms |

`CONDITIONAL_UPDATE` alone keeps the count right and still fails, because seats overlap. The unique index is the only layer that rejects the second reservation regardless of what the code above it did.

The reverse does not hold. The unique index lets at most N requests reach the counter at all, so `oversoldCount` is 0 under `UNIQUE_CONSTRAINT` whatever the oversell strategy is. Try `oversell=NONE` with `doubleBooking=UNIQUE_CONSTRAINT` and read `ledgerMismatch`, not `oversoldCount`. Also, `PASS` in that row means consistent only: the DoD script records no `NONE` baseline for that seat strategy, so the `DEGRADED` check never runs there.

Five runs each with the same parameters, `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT`, two app instances, all four `cacheConsistency` strategies:

| Cache | Verdict | Phantom views | Stale window | DB reads / views | Throughput (req/s) | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| NONE | PASS 5/5 | 256 to 279 | 2,032 to 2,055 ms | 1 to 2 / 267 to 294 | 5,154 to 5,847 | 160 to 189 ms |
| TTL_SHORT | PASS 5/5 | 120 to 128 | 877 to 912 ms | 5 to 6 / 273 to 285 | 5,208 to 6,024 | 158 to 182 ms |
| INVALIDATE_ON_WRITE | PASS 5/5 | 274 to 286 (2/5 runs) | 2,048 to 2,056 ms (2/5 runs) | 6 to 7 / 265 to 292 | 5,235 to 5,952 | 161 to 181 ms |
| REDIS_AS_SOT | PASS 5/5 | 0 | 0 ms | 0 / 276 to 294 | 5,291 to 5,882 | 162 to 182 ms |
| `INVALIDATE_ON_WRITE` (raceWindowMs=200) | PASS 5/5 | 240 to 250 (5/5 runs) | 2,030 to 2,052 ms | 2 / 242 to 252 | 5,319 to 6,060 | 160 to 183 ms |

`NONE` keeps the first value it saw until the TTL expires, which is after the run ends. `TTL_SHORT` bounds the window to the TTL and no lower; the TTL that closes it is zero, which is no cache. `INVALIDATE_ON_WRITE` is right until one reader misses the cache, reads the database, sleeps, and lands its now-stale value after the last write's delete. Nothing deletes it again. At the default 20 ms window that happens in 2 of 5 runs; at 200 ms, in all five. The race is a property of the read latency against the write burst, and the knob only makes it visible. `REDIS_AS_SOT` has no second copy, so the measurement shows zero. The cost side is the DB reads column: the shorter the window, the less the cache absorbs.

### Every combination

Every case below is N=100, M=1,000, `raceWindowMs=20`, `seed=42`, two app instances unless the caption says otherwise, recorded by `./scripts/case-shots.sh`. One screenshot per case: the seat grid, the verdict, and that run's whole metric table. 25 FAIL, 7 PASS, 6 DEGRADED.

The three axes and the instance count make 80 combinations, which fold to 38. `REDIS_AS_SOT` keeps the counter in Redis and ignores the oversell strategy, so its five rows are one. `appInstances` changes the outcome only for `LOCAL_LOCK`, the one defense that lives inside the JVM; the others are in MySQL or Redis and read the same at one instance as at two.

Each group runs its `oversell=NONE` case first, so the `DEGRADED` check has a baseline at two instances. The one-instance `LOCAL_LOCK` runs have no matching baseline and can only read PASS or FAIL, which is why `LOCAL_LOCK` at one instance passes at a throughput that would mark `PESSIMISTIC` degraded.

<details>
<summary><b>oversell = NONE</b>. No defense: read the counter, sleep, write it back (6)</summary>

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
<summary><b>oversell = LOCAL_LOCK</b>. A JVM `ReentrantLock`, at one instance and at two (12)</summary>

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
<summary><b>oversell = CONDITIONAL_UPDATE</b>. One statement: `UPDATE ... WHERE remaining > 0` (6)</summary>

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
<summary><b>oversell = PESSIMISTIC</b>. `SELECT ... FOR UPDATE` inside a transaction (6)</summary>

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
<summary><b>oversell = OPTIMISTIC</b>. A version check with unbounded retry (6)</summary>

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
<summary><b>cacheConsistency = REDIS_AS_SOT</b>. The counter lives in Redis and `DECR` decides, so the oversell strategy is ignored (2)</summary>

**doubleBooking=NONE** → FAIL

![none-none-redis_as_sot-2app FAIL](docs/cases/none-none-redis_as_sot-2app.png)

---

**doubleBooking=UNIQUE_CONSTRAINT** → PASS

![none-unique_constraint-redis_as_sot-2app PASS](docs/cases/none-unique_constraint-redis_as_sot-2app.png)

</details>

## Running it

You need JDK 21 and Docker.

```bash
docker compose up -d --build
open http://localhost:8080      # parameter form and results
./scripts/dod.sh                # reproduces the tables above
./scripts/demo-gif.sh           # re-records docs/demo.gif (needs Node and ffmpeg)
./scripts/case-shots.sh         # re-records docs/cases/*.png, one per combination (needs Node)
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

One Spring Boot module, built into one image, with the role picked by profile. The `app` profile exposes only the reservation endpoint, the `web` profile the run API and the load generator. App instances are stateless: the strategy and the delay come in the request body, so switching strategies never needs a restart.

The load generator spawns M virtual threads and lines them up behind a single `CountDownLatch`. Requests originate on the server. From the browser, start-time jitter alone is enough to make the race disappear.

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
  static/lib.js             share-link encoding, comparison rows, verdict reason, strings
db/schema.sql               MySQL initdb
scripts/dod.sh              reproducibility check
```

## Documents

- [Spec and roadmap](concurrency-ticketing-lab_spec.md) (Korean)
- [Decision log](docs/decisions.md) (Korean). One line per choice the spec left open or where the code diverges from it.
- [Milestone designs and implementation plans](docs/milestones/) (Korean)
