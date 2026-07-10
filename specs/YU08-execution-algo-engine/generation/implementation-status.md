# Implementation Status: YU08-execution-algo-engine

TWAP + VWAP parent-order scheduling, child-order submission through order-matcher's existing
ingress, JetStream event-sourced own state — **implemented and unit-tested; live kind
verification blocked this session by a local Docker Desktop environment fault (see below), not by
anything in this state's own code.**

## What is implemented

### `execution-algo-engine` (new component, Java 21 / Spring Boot)

| File | Role |
|---|---|
| `schedule/TwapScheduleBuilder.java` | FR-AE02: equal-quantity time buckets, remainder on the last bucket. |
| `schedule/VwapScheduleBuilder.java` | FR-AE03: buckets weighted by a `VolumeProfileSource`. |
| `volume/SyntheticVolumeProfileSource.java` | Deterministic U-shaped intraday curve, no data dependency. |
| `volume/DuckDbVolumeProfileSource.java` | Queries YU07's unified `ticks` Parquet store via `duckdb_jdbc`; falls back to synthetic on zero matching rows or any query failure (ADR-031). |
| `eventstore/AlgoEvent.java`, `AlgoOrderState.java` | Event schema and pure in-memory projection (no I/O — fully unit-tested). |
| `eventstore/AlgoEventStore.java` | JetStream (`TRADERX_ALGO_ENGINE`) append-before-apply, durable-consumer replay-on-boot (ADR-030). |
| `orders/PriceClient.java`, `OrderMatcherClient.java` | Reference-price fetch and child-order submission via `order-matcher`'s existing `POST /orders`. |
| `orders/AlgoScheduler.java` | `@Scheduled` tick submitting due buckets. |
| `fills/OrderUpdateSubscriber.java` | `/accounts/*/orders` broadcast subscriber, correlates by `orderId`. |
| `api/AlgoOrderController.java` | `POST /algo/orders`, `GET /algo/orders/{id}`, `GET /algo/orders`. |
| `service/AlgoOrderService.java` | Orchestrates all of the above. |

### Manifests

- `execution-algo-engine-deployment.yaml` (1 replica, port 18120, no PVC — state lives in
  JetStream), `execution-algo-engine-service.yaml` (ClusterIP).
- `kustomization.yaml` extended from YU07's copy, append-only (verified below).

### Pipeline wiring

Same two-script gotcha YU07 documented — found again by running generation and reading the
failure, plus a third: the doc-sync tooling also needs a `catalog/state-catalog.json` entry
(`state not found in catalog: YU08-execution-algo-engine` on first run) — not mentioned in any
prior state's handoff, presumably because every prior YUxx state added its entry without ever
needing to debug its absence.

- `pipeline/install-generated-runtime-harness.sh` — a `YU08-execution-algo-engine)` case.
- `pipeline/install-generated-ci-assets.sh` — a `YU08-execution-algo-engine)` case adding
  `"YU08-execution-algo-engine"` and `"execution-algo-engine"` to `state_allowed_roots`.
- `catalog/state-catalog.json` — a new entry (id, title, lineage, generation entrypoint, publish
  branch/tag) mirroring YU07's shape exactly.

## Verification evidence

- **Unit tests**: `./gradlew test` — **15 passed, 0 failed**, covering:
  - TWAP bucket math (even split, remainder-on-last-bucket, exact-total invariant, bucket timing).
  - VWAP weighting (exact-total invariant, unequal buckets vs. TWAP's equal split).
  - `SyntheticVolumeProfileSource` (weights sum to 1, U-shaped curve).
  - `DuckDbVolumeProfileSource` fallback — run against a real empty local directory (no `gs://`
    prefix, so no network/GCS credential involved) to exercise the actual DuckDB query and
    confirm it returns exactly the synthetic weights on zero matching rows, and confirm the
    disabled (`synthetic`) source never touches the query path.
  - `AlgoOrderState` replay — the same event sequence applied live vs. replayed from scratch
    produces identical state; re-applying an event (simulated crash-redelivery) is idempotent.
  - `OrderUpdateSubscriber` — correlates a matching `orderId` to `AlgoOrderService.onOrderUpdate`,
    ignores messages with no `orderId`.
- **Build**: `./gradlew bootJar` produces `execution-algo-engine-0.1.0.jar` (Dockerfile's input).
- **Generation**: `bash pipeline/generate-state.sh YU08-execution-algo-engine` exits **0**.
- **Shared-file no-clobber** (`scripts/test-state-YU08-execution-algo-engine.sh`, run against the
  generated tree): `kustomization.yaml` retains every ancestor's resource entry
  (`eod-session-close-cronjob.yaml`, `order-matcher-lmax-data-pvc.yaml`,
  `tick-store-deployment.yaml`) alongside both YU08 additions
  (`execution-algo-engine-deployment.yaml`, `execution-algo-engine-service.yaml`).
- **Manifest wiring**: generated `execution-algo-engine-deployment.yaml` carries
  `ORDER_MATCHER_URL`, `PRICE_SERVICE_URL`, and port `18120`.
- **Smoke test** (`scripts/test-state-YU08-execution-algo-engine.sh`): all checks pass, including
  in-process `./gradlew test` against the generated (not just spec-source) `execution-algo-engine`
  tree.

## Not verified this session — environment blocker, not a code defect

A local kind end-to-end run (TWAP parent order → real child orders accepted by `order-matcher` →
observed fills → crash-recovery restart) and the mandatory `bench-compare` pass were **not**
completed. Every attempt to build the state's Docker images hung on `docker pull`/`docker build`
(zero output, indefinitely) — reproduced with:

- The original `reference-data` image build (`DeadlineExceeded` resolving `docker/dockerfile:1.7`).
- A bare `docker pull docker/dockerfile:1.7` and `docker pull busybox:latest` — same hang, no
  image-specific pattern.
- Diagnosed as **not** a basic connectivity problem: `curl` from the host to
  `registry-1.docker.io`, `auth.docker.io`, and from *inside* an already-running container (via the
  Docker VM's own network stack) all succeeded immediately. Only Docker Desktop's own
  pull/registry-client path hung.
- Attempted fixes, in order, none resolved it: quitting and reopening the Docker Desktop app;
  `docker desktop restart` (a full VM restart, confirmed via `docker desktop status` showing a new
  session and all containers restarting); killing and retrying the pull fresh after each restart.

This points to Docker Desktop's own image-store/registry-client state being stuck in a way that
survives an app-level and VM-level restart — likely needs a "Troubleshoot → Clean / Purge data"
reset or a full machine restart, both more disruptive than this session should take unilaterally
(the former deletes every cached image, including the `state009`-tagged images built in prior
sessions).

**Practical implication for the next session:** re-run
`bash generated/code/target-generated/scripts/start-state-YU08-execution-algo-engine-generated.sh
--provider kind --without-sail --recreate-cluster` once `docker pull busybox` (or any small image)
succeeds normally — if generation and the unit tests above still pass (they should, this state's
own code did not change), the only remaining work is the live E2E smoke order and `bench-compare`
this document defers.

## Notes / gotchas recorded

- `order-matcher`'s `POST /orders` requires a positive `limitPrice` — there is no market-order verb
  on this path (`validateOrderCreateRequest` rejects null/non-positive). Child orders derive one
  from `price-publisher`'s last price with a 10bps aggressive offset (research.md Decision 3) rather
  than needing a new order-matcher order type.
- `OrderResponse` (the payload rendered onto `/orders`/`/accounts/*/orders`) has no `clientOrderId`
  field — it comes from the hot-path output-ring's zero-allocation rendering path
  (`OrderResponse.from(...)`, Tier 2-C). Fill correlation uses `orderId` via an in-memory index
  populated at synchronous submit time instead of adding a field to that path (research.md
  Decision 5) — deliberately avoids any order-matcher/BLP change for this state.
- `state-catalog.json` needing its own new-state entry (see Pipeline wiring above) is a genuinely
  new gotcha, not previously documented by YU03–YU07's handoffs.
