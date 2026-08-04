# Proof scripts

Falsifiable, end-to-end **correctness proofs** for the TraderX/LMAX system — the "Tier 2" of the
[test strategy](../../docs/handoff/production-readiness/04-RESULT-test-strategy.md). Each one drives
the *deployed* system (REST / FIX / binary ingress → gateway → Aeron cluster → async projection → SQL
read model / GCS) and prints explicit per-step **✔/✘** lines. Several were genuinely falsified before
they passed — that history is why they are trusted.

These are **not benchmarks** (those live in [`../bench`](../bench)) and **not unit tests** (those run
in CI — see below). They assert *behaviour on the running venue*; the same properties are also gated
in-process on every commit by the JUnit tests named in the "CI counterpart" column.

## How to run

Operator-driven against a live stack (kind unless noted GKE). Most take no arguments; some take a
sub-command (e.g. `yu03-risk-proof.sh controls`). Bring the target state up first (see the state's
`quickstart.md`), then:

```bash
bash scripts/proofs/<script>.sh
```

Prerequisites a script needs (port-forwards, a JWT, kube context) are documented in its own header
comment. The four `yu05-*` proofs share [`yu05-common.sh`](yu05-common.sh) (sourced automatically).

## The proofs

### Risk gateway & durable control feeds (YU03–YU04)

| Script | What it proves (falsifiable claim) | CI counterpart |
|---|---|---|
| [`yu03-risk-proof.sh`](yu03-risk-proof.sh) | The two-tier in-memory risk gateway rejects orders that breach a control (position/notional/restriction/kill-switch); each reject control is demonstrated live. Takes a sub-command (`controls`, …). | `BlpRiskStateTest`, `RiskControlControllerTest`, `OrderMatcherRiskMismatchTest`, `EntitlementGateTest` |
| [`yu04-live-delta.sh`](yu04-live-delta.sh) | A control-feed change is delivered as a **live delta** with no consumer restart (watermark advances before→after). | `ControlFeedSubscriberTest` |
| [`yu04-offline-catchup.sh`](yu04-offline-catchup.sh) | A change made while a replica is **offline** is caught up on reconnect via the watermarked-snapshot bootstrap (would be lost in YU03). | `ControlFeedBootstrapStateTest` |

### Post-trade / compliance (YU05)

_All four source [`yu05-common.sh`](yu05-common.sh) (shared setup: trade-processor port-forward + edge-proxy)._

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu05-auth-entitlements.sh`](yu05-auth-entitlements.sh) | Real HS256 JWT auth + entitlement codes (cross-account→401, foreign-scope→403, no-bearer→401), replacing the YU02–YU04 open surface. | `JwtAuthenticatorTest`, `EntitlementGateTest` |
| [`yu05-recon.sh`](yu05-recon.sh) | Reconciliation is the CQRS integrity check: journal↔projection classified matched / missing / mismatch, plus the full-history orphan sweep — and a **planted projection-only row proves the sweep can actually fail**. | `ReconciliationServiceTest`, `ClusterReconTapTest` |
| [`yu05-regulatory-reproducible.sh`](yu05-regulatory-reproducible.sh) | The regulatory export is a **pure function of the journal** — the same query answered byte-reproducibly from the source of truth, over a **closed** sequence range. | `RegulatoryReportDeterminismTest` |

_On the cluster tier the journal is the Aeron Cluster log: the members serve `/recon/*` and
`/regulatory/report` by replaying their own archive through a shadow engine ([`ClusterRecon`](../../specs/YU15-eod-risk-extract/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/ClusterRecon.java)),
and the gateway forwards to a member because it holds no history itself. **The source is the whole
point** — serving these trades from the SQL projection compares SQL against itself and passes
vacuously with `matched=0`, which is why both scripts assert against the log side at every step._
| [`yu05-settlement.sh`](yu05-settlement.sh) | The real settlement lifecycle: a booked trade walks Processing → Settled with a settlement date. | `SettlementServiceTest` |

### EOD price production (YU06)

_Both run against the state-014 kind rig (edge-proxy topology); recovered from the YU06 demo-prep
scripts and hardened so every claim hard-fails. Each injects `EOD_UNIVERSE` and resets it on exit._

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu06-quality-gate.sh`](yu06-quality-gate.sh) | The EOD publication quality gate is fail-safe: a MISSING price flags the session, **publish is refused (409, stays DRAFT) while flagged**, an operator override (with reason) resolves it as a new version, that version publishes, and the flagged version survives immutably. | `EodPriceServiceTest`, `EodQualityCheckerTest` |
| [`yu06-consumer-halt.sh`](yu06-consumer-halt.sh) | The P&L consumer **halts fail-safe**: an account provably holding a security absent from the published universe gets **zero** P&L rows (never a partial mark), while control accounts are marked in the same version. | EOD consumer unit tests |

### Execution algos (YU08)

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu08-algo-slicing.sh`](yu08-algo-slicing.sh) | A TWAP parent order emits exactly N children **across** the schedule — count, quantity conservation, per-bucket timing window, and a mid-schedule not-front-loaded check — each **booked on the matcher's own blotter**, not the algo engine's word. | `AlgoEventStoreReplayTest`, `TwapScheduleBuilder` tests |

### FIX ingress (YU10)

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu10-fix-session.sh`](yu10-fix-session.sh) | FIX 4.4 ingress live on kind (SC-FIX01/06) and the **FIX↔REST equivalence** claim (a FIX order lands in state, journal, and DB like a REST order). Uses `../bench/load/fix-load.mjs` as the sender. | `FixSessionIntegrationTest`, `FixGatewayStatusTest`, `FixGatewaySurvivalTest` |
| [`yu13-fix-cancel.mjs`](yu13-fix-cancel.mjs) | FIX cancel/replace message driver — exercises the cancel path over a real FIX session. | `FixGatewayStatusTest` |

### Order book & lifecycle (YU13)

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu13-cancel-ingress.sh`](yu13-cancel-ingress.sh) | A client can cancel a resting order on the cluster tier, and the cancel takes effect **identically on every member**. | `LimitOrderBookTest` (cancel*) |
| [`yu13-clordid-suppression.sh`](yu13-clordid-suppression.sh) | A resent client order id **books once** — idempotency asserted in SQL. | `ClOrdIdLedgerTest`, `IdempotencyEvictionDeterminismTest` |
| [`yu13-readmodel-effect-end.sh`](yu13-readmodel-effect-end.sh) | The order read model at the **SQL effect end**: place → member `next_order_ref` delta (ground truth) → `orderbook` row NEW → `GET /accounts/{id}/orders`; cancel → row CANCELED, out of the open set (a control order guards the disappearance check), projector rejection signal silent. | `ProjectorHandlerTest`, `OrderFeedHandler` rejection tests |
| [`yu13-stp-and-replace.sh`](yu13-stp-and-replace.sh) | The member bundle: **self-trade prevention** (ADR-057, cancel-oldest) and **engine-native atomic replace** (ADR-058). | `LimitOrderBookTest` (selfMatch*, rejectedReplace*, replaceCrosses*, replayReproduces*) |
| [`yu13-gke-replace-proof.sh`](yu13-gke-replace-proof.sh) | The three things atomic replace still needed proving on a **real cluster** — run on GKE because kind cannot carry them. | `LimitOrderBookTest` (replace*) + `ThreeMemberClusterTest` |

### Listed options & EOD risk extract (YU14–YU15)

| Script | What it proves / does | CI counterpart |
|---|---|---|
| [`seed-option-chain.sh`](seed-option-chain.sh) | YU14 setup + smoke: seeds the packaged listed-equity-option chain into the running gateway and smoke-proves one option cross books. (Setup helper for the two proofs below.) | `SeedOptionChainTest` / gateway option-cross tests |
| [`yu15-option-persistence.sh`](yu15-option-persistence.sh) | Listed options reach the SQL read model, and the shipped migration fixes a database created by an older state. | `RiskExtractTest`, `TradeProcessorPersistenceIT` (integration) |
| [`yu15-risk-extract.sh`](yu15-risk-extract.sh) | The EOD risk-extract acceptance proof end-to-end: sequenced cut, byte-identical across members, quiescence, write-once (gs://-aware on GKE). | `RiskExtractTest`, `RiskReplayDeterminismTest`, `RiskExtractGcsSinkLiveProofTest` |

### Observability (OTEL-01)

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu13-otel-trace-join.sh`](yu13-otel-trace-join.sh) | One order produces **one distributed trace spanning the gateway and the cluster member**, with **no trace context in the replicated log**. Falsifiable rather than decorative: the script derives the expected trace id *and* the expected parent span id from the ClOrdID alone, with no input from either server, then demands Tempo return exactly that trace joined across both services. A build that smuggled a traceparent through the log would still show spans; only this pins the actual claim. Also asserts zero span drops and zero export failures. **Functional only — the "telemetry is free" claim is a timing claim and belongs on GKE.** | `OrderTraceTest`, `SpanSinkTest` |
| [`yu13-otel-reject-trace-log-join.sh`](yu13-otel-reject-trace-log-join.sh) | A **rejected** order is traced even when head sampling threw it away, and its **log line and its trace carry the same derived id**. Runs with head sampling genuinely on (mask 127 on both tiers, restored on exit) and submits two orders that both *fail* the head verdict — one rejected, one accepted. The rejected one must come back from Tempo as a whole 5-span trace with the member's spans parented to the **predicted** `cluster.consensus` id (so both tiers escalated independently — a one-sided escalation is a half-trace); the accepted one must **404**, which is what stops a build that quietly traces everything from passing. Then Loki must return that order's own `ORDER-REJECT` line for the trace id predicted from the ClOrdID, and Grafana must actually have the join provisioned in **both** directions. | `OrderTraceTest`, `RejectLogCapTest` |

### High availability

| Script | What it proves | CI counterpart |
|---|---|---|
| [`failover-nodeclock.sh`](failover-nodeclock.sh) | Node-clock-precise failover measurement for the Aeron Cluster: automatic promotion, sub-second, zero order loss. | `ThreeMemberClusterTest` (dedicated job) |
| [`yu12-gke-recovery.sh`](yu12-gke-recovery.sh) | **GKE.** A member destroyed to an empty disk rejoins to **byte-identity** (order hash, position hash, trades, nextOrderRef agreed by all three) and **later becomes leader** and books a cross. The strongest correctness story, as a committed script. | `ThreeMemberClusterTest`, `SnapshotRoundTripTest` |
| [`yu12-gke-failover-transparency.sh`](yu12-gke-failover-transparency.sh) | **GKE.** A leader kill under a live order stream loses **zero** and duplicates **zero** orders — client acks vs the member `next_order_ref` delta, exact equality on a quiet cluster. (The bench probes measure timing; this is the pass/fail correctness verdict.) | `ClOrdIdLedgerTest`, `InflightCorrelationTest` |
| [`yu12-gke-cross-epoch-idreuse.sh`](yu12-gke-cross-epoch-idreuse.sh) | **GKE.** Across a failover, orderRefs continue **strictly above** the prior epoch's high-water mark — old/new ref sets disjoint, counter monotonic on all members, new refs proven live (they trade). Standing regression proof for the nextOrderRef-in-snapshot fix. | `SnapshotRoundTripTest` (nextOrderRef) |
| [`yu12-gke-restore-from-gcs.sh`](yu12-gke-restore-from-gcs.sh) | **GKE.** Whole-cluster destruction → `RESTORE_FROM_GCS=1` → state intact at **exactly** the backup point (not the post-backup state, which is the honestly-stated RPO window) on all three members, and the restored book trades. | (no in-process counterpart — GCS + init-container path) |

## Notes

- **Scope:** this reorganization is on the **YU15 tip** (the presentation branch). Earlier branches
  (YU10/YU13/YU14) still keep these scripts under `scripts/bench/` with the flat layout — internally
  consistent for them. Propagate the move to those branches only if a state needs to demo from its own
  worktree with the new layout.
- **`yu05-common.sh`** is a shared helper, not a standalone proof.
- Benchmarks, load generators, and one-off measurement/utility scripts remain in
  [`../bench`](../bench) on purpose — they measure throughput/latency and have no pass/fail.
