# Implementation Status: YU08-execution-algo-engine

TWAP + VWAP parent-order scheduling, child-order submission through order-matcher's existing
ingress, JetStream event-sourced own state — **implemented, unit-tested, and verified live
end-to-end on a local kind cluster**, including crash recovery and `bench-compare`.

## What is implemented

### `execution-algo-engine` (new component, Java 21 / Spring Boot)

| File | Role |
|---|---|
| `schedule/TwapScheduleBuilder.java` | FR-AE02: equal-quantity time buckets, remainder on the last bucket. |
| `schedule/VwapScheduleBuilder.java` | FR-AE03: buckets weighted by a `VolumeProfileSource`. |
| `volume/SyntheticVolumeProfileSource.java` | Deterministic U-shaped intraday curve, no data dependency. |
| `volume/DuckDbVolumeProfileSource.java` | Queries YU07's unified `ticks` Parquet store via `duckdb_jdbc`; falls back to synthetic on zero matching rows or any query failure (ADR-031). |
| `eventstore/AlgoEvent.java`, `AlgoOrderState.java` | Event schema and pure in-memory projection (no I/O — fully unit-tested). |
| `eventstore/AlgoEventStore.java` | JetStream (`TRADERX_ALGO_ENGINE`) append-before-apply, ephemeral full-replay-on-boot (ADR-030). |
| `orders/PriceClient.java`, `OrderMatcherClient.java` | Reference-price fetch and child-order submission via `order-matcher`'s existing `POST /orders`. |
| `orders/AlgoScheduler.java` | `@Scheduled` tick submitting due buckets. |
| `fills/OrderUpdateSubscriber.java` | Catch-all NATS subscriber (`>`, client-filtered), correlates by `orderId`. |
| `api/AlgoOrderController.java` | `POST /algo/orders`, `GET /algo/orders/{id}`, `GET /algo/orders`. |
| `service/AlgoOrderService.java` | Orchestrates all of the above, including race-safe fill correlation. |

### Manifests

- `execution-algo-engine-deployment.yaml` (1 replica, port 18120, no PVC — state lives in
  JetStream), `execution-algo-engine-service.yaml` (ClusterIP).
- `kustomization.yaml` extended from YU07's copy, append-only (verified below).
- `kubernetes-runtime/build-plan.json` extended from state 010's original copy, append-only —
  the image-build manifest every prior YU-state left untouched (see Pipeline wiring below).

### Pipeline wiring

Found by running generation and reading the failure, same as every prior state's own handoff
describes for its own new component — three gaps this time, not two:

- `pipeline/install-generated-runtime-harness.sh` — a `YU08-execution-algo-engine)` case.
- `pipeline/install-generated-ci-assets.sh` — a `YU08-execution-algo-engine)` case adding
  `"YU08-execution-algo-engine"` and `"execution-algo-engine"` to `state_allowed_roots`.
- `catalog/state-catalog.json` — a new entry (id, title, lineage, generation entrypoint, publish
  branch/tag) mirroring YU07's shape exactly. Missing this fails generation outright
  (`state not found in catalog: YU08-execution-algo-engine`) — not mentioned in any prior state's
  handoff, presumably because every prior YUxx state added its entry without ever needing to debug
  its absence.
- **`kubernetes-runtime/build-plan.json`** (new gotcha, not caught by any prior state): this file —
  not `kustomization.yaml` — is what actually drives which components get a Docker image built for
  kind/minikube. It originates entirely from state 010's base patch and had never been extended by
  any later state, including YU07: this is the *actual* root cause of `tick-store` sitting in
  `ImagePullBackOff` since YU07 (previously assumed to just be "the image was never built"). Adding
  `execution-algo-engine` to both its `images` and `deployments` lists (append-only, same pattern as
  `kustomization.yaml`) was required before the live kind run below could get past
  `ImagePullBackOff` at all.

## Verification evidence

### Unit tests

`./gradlew test` — **20 passed, 0 failed** (15 from the initial implementation, 5 added while
fixing bugs found during live verification below):

- TWAP bucket math (even split, remainder-on-last-bucket, exact-total invariant, bucket timing).
- VWAP weighting (exact-total invariant, unequal buckets vs. TWAP's equal split).
- `SyntheticVolumeProfileSource` (weights sum to 1, U-shaped curve).
- `DuckDbVolumeProfileSource` fallback — run against a real empty local directory (no `gs://`
  prefix, so no network/GCS credential involved) to exercise the actual DuckDB query and confirm
  it returns exactly the synthetic weights on zero matching rows.
- `AlgoOrderState` replay — the same event sequence applied live vs. replayed from scratch produces
  identical state; re-applying an event (simulated redelivery) is idempotent.
- `OrderUpdateSubscriber` — envelope-unwrapping + correlation to `AlgoOrderService.onOrderUpdate`,
  ignores non-matching messages.
- `AlgoOrderService` — the fill-before-submit race (see Bugs below) is correlated correctly
  regardless of which side (NATS fill broadcast vs. this engine's own submit-time registration)
  arrives first.

### Generation and static checks

- `bash pipeline/generate-state.sh YU08-execution-algo-engine` exits **0**.
- **Shared-file no-clobber** (`scripts/test-state-YU08-execution-algo-engine.sh`): both
  `kustomization.yaml` and `build-plan.json` retain every ancestor's entry alongside YU08's
  additions.
- **Smoke test** (`scripts/test-state-YU08-execution-algo-engine.sh`): all checks pass, including
  in-process `./gradlew test` against the generated (not just spec-source) tree.

### Live end-to-end verification (local kind, `traderx-state-014`)

Full bring-up (`start-state-YU08-execution-algo-engine-generated.sh --provider kind
--without-sail --recreate-cluster`), all 20 pods `1/1 Running` including `execution-algo-engine`
and `order-matcher`.

- **TWAP** (`POST /algo/orders`, IBM, Buy, qty 30, 15s duration, 5s buckets): 3 buckets of 10
  shares each submitted exactly 5s apart; all 3 accepted and filled by `order-matcher`
  (`ord-013-0016/17/18`); parent reached `COMPLETED` with every bucket's `remainingQuantity=0` and
  a real `lastExecutionPrice`.
- **VWAP** (IBM, Sell, qty 100, 24s duration, 6s buckets, synthetic profile): bucket quantities
  `[32, 17, 17, 34]` — the expected U-shape (heavier at the first/last buckets), summing exactly to
  100, confirming SC-AE05 (bucket sizes differ from an equal split).
- **Crash recovery** (SC-AE06): killed the `execution-algo-engine` pod mid-VWAP-run
  (`kubectl delete pod`); after the replacement pod became ready, `GET /algo/orders/{id}` showed
  the exact same parent order, including every bucket's fill state from before the kill, still
  `COMPLETED` — no re-submission, no lost state.
- **`bench-compare`**: `order-matcher` itself is unmodified by this state (no runtime-override
  touches it); this run confirms its hot path is unaffected by `execution-algo-engine` running
  alongside it. `avg-max-load.mjs --no-reset --batch 1000 --conc 48 --secs 20 --runs 2` against
  `http://localhost:18110` — the script's default four-ticker set (`JPM,GS,COF,DFS`) can't share one
  fixed `LIMIT` (GS trades near $387 while DFS trades near $127 — no single limit price sits within
  every ticker's 50% price collar simultaneously), so this run used `TICKERS=IBM LIMIT=200`
  (IBM ~$185–190 at the time) instead of loosening the risk gateway's collar to make the default
  set fit — the risk gateway is not touched by this state's own bench methodology, matching
  research.md's "no bypass" decision.

  | Run | peak/s | booked/s | submit/s | failed |
  |---|---|---|---|---|
  | 1 | 19227 | 99 | 101547 | 0 |
  | 2 | 19227 | 0 | 108162 | 0 |

  `peak/s` sits above every prior reference point in this lineage (YU06 pre/post-fix 9870/10366,
  YU07 post-fix 10038 — see YU07's own `implementation-status.md`), though the ticker/limit-price
  methodology differs from those runs (single ticker vs. four, different limit price) so it is not
  a strict apples-to-apples comparison; the number is reported here for the record, not as a
  precise regression measurement. Zero failed submissions across both runs, and no code change to
  `order-matcher` in this state, are the load-bearing facts: this state does not touch the BLP hot
  path, and the bench run demonstrates that in practice, not just by code inspection.

## Bugs found and fixed during live verification (not caught by unit tests or documentation)

Three real defects, all invisible without actually running child orders through a live
`order-matcher` — recorded here because they're exactly the class of thing "verified: unit tests
pass" would otherwise silently miss:

1. **`build-plan.json` never listed the new component** (Pipeline wiring above) — the image was
   never built, and the pod sat in `ImagePullBackOff` exactly like YU07's `tick-store`.
2. **`/accounts/*/orders` is not a valid NATS wildcard subscribe** — these subjects use a literal
   `/`, not NATS's `.`-delimited token hierarchy (`order-matcher` publishes the literal string
   `"/accounts/" + accountId + "/orders"`), so the whole string is one NATS token and `*` cannot be
   embedded inside it. jnats's subject validator rejects this outright at startup
   (`IllegalArgumentException: Subject wildcard improperly placed`) — a crash-loop, not a silent
   no-op. (Python's `nats-py`, which `tick-store` uses, does not validate subjects client-side, so
   the identical pattern there compiles and runs without ever raising this error — whether it
   actually delivers anything is a question this state did not need to answer, since it does not
   touch `tick-store`.) Fixed by subscribing to NATS's catch-all `>` and filtering client-side by
   subject prefix/suffix.
3. **Every message on this bus is wrapped in a `NatsEnvelope`** (`topic`/`payload`/`date`/`from`/
   `type` — `messaging/nats/NatsJSONPublisher`, the concrete `Publisher<T>` every JVM publisher on
   this branch uses). The real `OrderResponse` fields live under `payload`, not the envelope's top
   level. Fixed by unwrapping `payload` before reading `orderId` etc.

A fourth issue was a genuine design correction, not a bug in the strict sense: the original
event-store design used a **durable** JetStream consumer with explicit acking, which turned out to
silently violate FR-AE08 (see ADR-030's "Alternatives Considered") — acking permanently advances a
durable consumer's position, so a restart after a parent order fully completed would never see its
events again. Switched to an **ephemeral** consumer that always replays the entire stream from the
start on every boot, verified by the crash-recovery check above.

A fifth issue was a genuine **race condition**, not caught by any test until live order flow
exposed it: `order-matcher`'s NATS fill broadcast can arrive at this engine before the synchronous
`POST /orders` HTTP response returns (and therefore before this engine's own `orderId` →
`(parentOrderId, bucketIndex)` index is populated) — NATS delivery to the subscriber and the HTTP
response are two independent, unordered paths. Fixed with a `pendingUpdates` buffer in
`AlgoOrderService`: an unmatched fill update is stashed and reconciled the moment the corresponding
child order is registered, regardless of which side arrives first. Covered by two new unit tests
(`AlgoOrderServiceTest`) exercising both arrival orders directly.

## Notes / gotchas recorded

- `order-matcher`'s `POST /orders` requires a positive `limitPrice` — there is no market-order verb
  on this path (`validateOrderCreateRequest` rejects null/non-positive). Child orders derive one
  from `price-publisher`'s last price with a 10bps aggressive offset (research.md Decision 3) rather
  than needing a new order-matcher order type.
- `OrderResponse` (the payload rendered onto `/orders`/`/accounts/*/orders`) has no `clientOrderId`
  field — it comes from the hot-path output-ring's zero-allocation rendering path
  (`OrderResponse.from(...)`, Tier 2-C). Fill correlation uses `orderId` via an in-memory index
  instead of adding a field to that path (research.md Decision 5) — deliberately avoids any
  order-matcher/BLP change for this state.
- The risk gateway's default price collar (`risk.price.collar-bps=5000`, i.e. 50%) means a single
  fixed bench `LIMIT` value cannot cover a ticker set whose prices span a wide range (see
  `bench-compare` above) — pick a ticker/limit pair that actually fits the collar rather than
  widening the collar for a benchmark run.
