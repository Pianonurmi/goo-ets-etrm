# Load testing

`ops/load/etrm.js` is a [k6](https://k6.io) script with three scenarios, each a group of virtual users running
in parallel for the whole test:

| Scenario | What a user does | Tag |
|---|---|---|
| `reads` | trade search, lots of an entity, GoO position, ETS ladder, inbox and its counts, audit trail; an available-to-sell check (ADR 0036) | `kind:read`, `kind:check` |
| `trades` | captures an EUA forward (trade approval task, EMIR fields, audit, events), then reads it back | `kind:write` |
| `allocation` | allocation what-if for the entity: snapshot, problem build and greedy solve, nothing stored | `kind:solve` |

Profiles (`-e PROFILE=`):

| Profile | Readers | Writers | Planners | Duration |
|---|---|---|---|---|
| `smoke` | 1 | 1 | 1 | 30 s |
| `baseline` | 20 | 5 | 2 | 5 min |
| `stress` | 80 | 20 | 4 | 10 min |

## Thresholds

The run fails (k6 exit code 99) when one of these is missed.

| Metric | Threshold |
|---|---|
| Failed requests | < 1 % |
| Reads | p95 < 300 ms, p99 < 800 ms |
| Writes | p95 < 800 ms, p99 < 2 s |
| Allocation what-if | p95 < 3 s |
| Available to sell | p95 < 150 ms |
| Checks | > 99 % pass |

## Running

```bash
# The application with the demo data (dev personas), quieter logs than the dev default:
SPRING_PROFILES_ACTIVE=dev,demo java -jar apps/api/build/libs/api.jar \
  --logging.level.com.pianonurmi.etrm=INFO --logging.level.org.jooq=INFO
k6 run -e BASE_URL=http://localhost:8080 -e PROFILE=baseline ops/load/etrm.js
# or without installing k6:
docker run --rm --network host -v "$PWD/ops/load:/load" grafana/k6:1.3.0 run -e PROFILE=smoke /load/etrm.js
```

Against a secured environment, pass `-e TOKEN=<bearer token>` of a user with the trader, middle-office and back-office
roles, and `-e ENTITY=<legal entity code>`. The script creates trades, so never point it at production.

In GitHub Actions, the manual workflow **Load test** (`.github/workflows/load.yml`) builds the jar and starts it
with the demo data on the runner. It runs the chosen profile and keeps the k6 summary and the application log as
an artifact.

## Baseline

Measured on 2026-10-11 against the jar with the demo data (two entities, PostgreSQL 16). The application, the
database and k6 all shared one 4-core, 16 GB machine. Java 25 ran on virtual threads with Hikari at 20 connections.
Expect better figures on a production node. These are the reference for regressions.

| Profile | Requests | Throughput | Errors | Read p95 / p99 | Write p95 / p99 | What-if p95 |
|---|---|---|---|---|---|---|
| `smoke` (3 users, no pauses, 30 s) | 6 916 | 230 req/s | 0 | 22 ms / – | 52 ms / – | 24 ms |
| `baseline` (27 users, 1 s pauses, 5 min) | 43 418 | 144 req/s | 0 | 15 ms / 25 ms | 60 ms / 115 ms | 19 ms |
| `stress` (104 users, no pauses, 3 min) | 100 444 | 556 req/s | 0 | 598 ms / 966 ms | 690 ms / 1.0 s | 594 ms |

The baseline is well inside every threshold. Under stress the machine is CPU-bound, with three processes sharing
four cores. Latency grows with the queue, but nothing fails: no errors, no timeouts, no pool exhaustion. The read
thresholds are crossed there, which is the point where a second replica (HPA at 70 % CPU) should already have
started.

**Increment 8** (2026-10-12, `smoke` only, on a different 4-core cloud container, so not directly comparable
with the rows above): 6 889 requests, 229 req/s, no errors. Read p95 13 ms, write p95 35 ms. The available-to-sell
check (`kind:check`) has p95 7 ms. The what-if has p95 121 ms, up from 24 ms. The likely cause (not measured apart): since ADR 0035 every what-if and
plan also values its lines in EUR and solves the shadow-price LP. Run `baseline` and `stress` again on the reference
machine before relying on the what-if figure.

At the close of increment 8 (same container, `smoke`): 6 200 requests, no errors. Read p95 15 ms, write p95 36 ms,
available-to-sell check p95 9 ms. The what-if has p95 343 ms. Since ADR 0038 a what-if also counts future supply:
the entity's GoO purchases (criteria loaded per schedule) and its own devices' expected issuance (forecasts, and
issued lots per device, for each month of the horizon) become extra lot classes. Measured apart on the demo data,
25 what-ifs each: median 250 ms with future supply, 112 ms with `futureSupply: false`. Still well inside the 3 s
target; the provider reads per device, so an entity with many devices is the case to watch.

The demo data is small: the what-if solves one obligation over 12 lot classes. Solver scale is measured separately
by `AllocationBenchmarkTest` (`-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000`).
