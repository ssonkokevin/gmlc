# Cell-ID request path: measured performance

Measured on 2026-11-22 on the build workstation. This report exists because the
roadmap sets a target of 700 answered cell-ID requests per second on an 8 vCPU /
32 GB host. **That target has not been demonstrated.** This document records what
was measured, how, on what hardware, and what the measurements say about why.

The number 700 is a target in this repository, not a result. It must not be
quoted as a capability until a run on the target hardware reaches it.

## What was measured, and what was not

The harness drives the real Le endpoint (`POST /v1/locations`) over HTTP against
the real application: API-key authentication, quota, consent, the request state
machine, the result row, the CDR, the audit trail and the geofence check all run.

Two things are not the production path:

- **The positioning source is a test-only stub.** The harness imports a
  `@Primary LocationLocator` that returns a complete cell-ID estimate
  (LTE, 350 m, confidence 70) without signalling. So the figures below are the
  gateway's own cost for an answered cell-ID request. They do not include an HSS
  or MME round trip.
- **PostgreSQL is the embedded test server** in the same JVM as the
  application and the load generator. Its settings are `fsync=off` and
  `synchronous_commit=off`, which makes the database side of this measurement
  optimistic compared with a production server. The machine is also below the
  target hardware (see below).

What is deliberately absent from the answered scenario: armed geofences (the
evaluator queries, finds no arms, and returns), Diameter or MAP exchanges,
TLS, and the MLP XML parse cost.

## Hardware and build

| Item | Value |
|---|---|
| CPU | Intel Core i7-1165G7, 4 cores / 8 logical processors |
| RAM | 15.7 GiB |
| OS | Windows 11 Pro (10.0.26200) |
| Java | OpenJDK 17.0.20.1 |
| PostgreSQL | 17.12 embedded (zonky), `fsync=off`, `synchronous_commit=off`, `shared_buffers=128MB` |
| Connection pool | HikariCP default, maximum 10 |
| Build | current `main`, `mvn -o -DskipTests test-compile` |

The target hardware (8 vCPU / 32 GB) is not this machine, and the target design
would run the database outside the application JVM. The figures below are
therefore a lower-bound-ish ceiling for this host, not a prediction for the
target host, and not a claim about it.

## Method

The harness is
`gmlc-app/src/test/java/com/hamiltel/gmlc/perf/CellIdThroughputPerf.java`. Its
class name does not match Surefire's default patterns, so it never runs in the
normal suite; it runs only when named.

For each run:

1. Register one or more API clients with `ratePerSecond=1000`,
   `ratePerDay=1_000_000`, purpose `TEST`.
2. Grant consent for a pool of MSISDNs per client.
3. Warm up for 3 s at the measured concurrency; discard those numbers.
4. Run a closed loop for 10 s: each worker sends `POST /v1/locations` as fast as
   it can, over HTTP/1.1 keep-alive, and records the wall time per request.
5. Report requests, throughput, p50/p95/p99/max and the number of non-expected
   responses. Any failed response fails the run, so a broken measurement is not
   reported as a throughput figure.
6. Read `pg_stat_database.xact_commit` immediately before and after the window.
   Every statement runs in autocommit mode, so the delta divided by answered
   requests is the number of SQL statements issued per request.

A second scenario uses the same client and a subject with **no consent**. That
request is refused with `403 PRIVACY_NOT_SATISFIED` before any request row is
written. It exercises authentication, the two quota increments, the consent
lookup and the refusal audit row: five statements, no result, state machine,
CDR or answer audit. It is the control that says whether the answered path's
cost is in its extra database work.

The quota allows 1,000 requests per second and per client, so the quota does not
explain the ceiling; every run reported zero failures, meaning none of the
responses were quota, authentication or server errors.

## Results

Answered requests, one client, 64 consented subjects:

| Concurrency | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | SQL statements / request |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 38.5 | 25 | 32 | 35 | 37 | 20.95 |
| 8 | 83.5 | 89 | 149 | 184 | 219 | 21.03 |
| 32 | 83.7 | 360 | 615 | 905 | 1 642 | 20.87 |
| 64 | 81.7 | 698 | 1 442 | 2 010 | 2 729 | 21.85 |

Refused requests (no consent) on the same host, as the control:

| Concurrency | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | SQL statements / request |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 82.4 | 11 | 15 | 17 | 22 | 5.01 |
| 8 | 177.3 | 42 | 67 | 83 | 105 | 4.98 |
| 32 | 186.5 | 151 | 327 | 449 | 826 | 4.96 |
| 64 | 197.0 | 312 | 478 | 582 | 742 | 4.95 |

Sixteen clients, thirty-two workers (32 consented subjects each), the same
method:

| Scenario | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | SQL statements / request |
|---|---:|---:|---:|---:|---:|
| Answered | 99.5 | 246 | 804 | 1 082 | 20.46 |
| Refused | 184.4 | 156 | 303 | 436 | 5.02 |

## What the numbers say

**The ceiling is around 84 answered requests per second on this host, and more
concurrency buys latency, not throughput.** From 8 to 64 workers the throughput
does not move (83.5, 83.7, 81.7 req/s) while p50 grows from 89 ms to 698 ms. The
work is queued behind a shared resource, not parallelised.

**The answered path's database work is the best-supported explanation.** One
answered request issues about 21 SQL statements: two quota upserts, the client
authentication read, the consent read, the request insert, the acceptance audit
row, the request read-back, four state advances, the result insert, the attach,
the answer advance, the CDR insert, the answer audit row, the delivery advance,
the final read-back, and the geofence arm query. A refused request issues about
5, and reaches about 197 req/s. The answered path writes a result, a CDR, two
audit rows and a request row; the refused path writes one audit row. The
throughput ratio (0.43) does not match the statement ratio (0.24) exactly, so
statement count alone is not a complete model - row size and index maintenance
also differ - but the direction is unambiguous: the extra database work is what
the answered path spends its time on.

**Two alternative explanations were tested and rejected.**

- *Quota-row contention.* All requests for one client update the same
  `client_usage` rows for the current second and day. Sixteen clients at 32
  workers removes that sharing and gives 99.5 req/s instead of 83.7 - a 19%
  gain, real but far from the ceiling. Per-client quota rows are a cost, not the
  wall.
- *Disk latency.* The embedded database runs with `fsync=off` and
  `synchronous_commit=off`. Commit durability is not what is being waited on.

**Single-threaded latency is 25 ms for an answered request.** Of that, 21
statements at roughly 1 ms of round trip each is the dominant term. The
application spends its time in many small, separate autocommit statements rather
than in JSON, Tomcat or hashing.

## The signalling leg is not measured, and is separately capped

In a real deployment the stub is replaced by `DiameterCellLocator` (SLh RIR then
SLg PLR/PLA) or `MapCellLocator` (SRILCS/PSL or ATI). Neither transport can be
measured here: there is no HSS, MME, HLR or MSC on this host, and the lab has no
Diameter peer.

More importantly, `TcpDiameterTransport` and `TcpM3uaTransport` currently send
**one request at a time** per connection (`exchange` is `synchronized`, and the
reader consumes frames until its own hop-by-hop id). One answered cell-ID request
needs at least one SLh and one SLg round trip, so a single connection caps a live
path at one request per round trip regardless of how fast the database is. The
concurrency fixes listed as Task A4 (a reader thread per connection, in-flight
requests keyed by hop-by-hop id, and a TCAP transaction map for M3UA) have to
land before a live cell-ID measurement means anything.

The gateway-side ceiling measured here (about 84 req/s on this host) and the
transport single-flight cap are independent; both have to be lifted to approach
the target.

## Target status

| | |
|---|---|
| Target | 700 answered cell-ID requests per second, 8 vCPU / 32 GB, measured |
| Measured here | 38.5 req/s at 1 worker; ~84 req/s at 32 workers, one client; 99.5 req/s at 32 workers, 16 clients |
| Verdict | **Not met.** 700 req/s is not demonstrated and must not be quoted as a capability |
| Host | 4 cores / 8 logical processors, 16 GB, application + database + load generator in one JVM - below the target hardware |

## Reproducing

```bash
cd "C:/Users/KEVIN/core dpi/gmlc"
/c/tools/apache-maven-3.9.9/bin/mvn -o -DskipTests test-compile
/c/tools/apache-maven-3.9.9/bin/mvn -o -Dtest=CellIdThroughputPerf \
  -Dperf.concurrency=32 -Dperf.clients=1 \
  -Dperf.warmupSeconds=3 -Dperf.seconds=10 -Dperf.subjects=64 test
```

Properties: `perf.concurrency` (workers), `perf.clients` (distinct API clients,
for the quota-contention control), `perf.subjects` (consented MSISDNs per
client), `perf.warmupSeconds`, `perf.seconds`.

Each run prints a `PERF` line with the settings, PostgreSQL and pool settings,
and one `PERF answered` / `PERF refused` line per scenario, including the
statements-per-request ratio. The test fails if any response is not the expected
one, so a run with failed requests cannot be quoted as throughput.

## What would have to change before the target is re-attempted

1. **Task A5: make the finish atomic.** The result row, the attach, the
   `ANSWERED` transition, the CDR and the answer audit row belong in one short
   transaction, and the insert plus the pre-requested advances in another. That
   is first a correctness fix (today a failed CDR insert can leave an answered
   request without one) and second a reduction in connections and commits per
   request.
2. **Cut the statement count.** Four immediate state advances and two
   read-backs can collapse into one update and `RETURNING`; the two quota
   increments can be one statement. Each removed round trip is paid on every
   request.
3. **Task A4: concurrent transports.** A reader thread per Diameter connection,
   in-flight requests keyed by hop-by-hop id, and a TCAP transaction map for
   M3UA.
4. **Re-measure on the target hardware** with PostgreSQL in its own process (or
   host) on an SSD, `fsync` at its production setting, and a Diameter test peer
   over the lab link. Only that run can answer whether 700 req/s is reachable.
