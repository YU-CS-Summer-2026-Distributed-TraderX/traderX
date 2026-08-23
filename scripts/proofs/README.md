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

## Running them by hand: use this order

`scripts/yu15/run-proofs.sh` runs the whole suite and **re-establishes and verifies all six
port-forwards before every proof**, so the order in its `PROOFS` array is optimised for a runner
that heals itself. Running them one at a time, you are the healer — and that order is the wrong one
for you. This one is ordered so a forward dies as rarely as possible.

### What actually kills a port-forward

`set env` triggers a rollout. That is not obvious from reading the script, and it is the usual
reason a proof "fails" on a system that is fine.

| Proof | Disruptive operation | Forward it kills |
|---|---|---|
| `yu06-quality-gate` | `set env deploy/trade-processor` | **18091** |
| `yu06-consumer-halt` | `set env deploy/trade-processor` | **18091** |
| `yu13-otel-reject-trace-log-join` | `set env deployment/cluster-gateway` | **18110** |
| `yu13-cancel-ingress` | `set image deploy/cluster-gateway` | **18110** |
| `yu13-stp-and-replace` | `set image` gateway **and** statefulset | **18110**, plus a fresh epoch |
| `yu04-offline-catchup` | `scale $WL --replicas=0/1` on cluster-gateway | **18110** |
| `yu15-risk-extract` | `delete pod order-matcher-cluster-2` | none — a member, not the gateway |

The other twelve disrupt nothing.

### Two constraints that are not about forwards

- **`yu08-algo-slicing` poisons every counter-exact proof.** It starts continuous algo traffic, and
  `yu13-readmodel-effect-end` asserts `next_order_ref` moves by *exactly 2*. The algo engine has
  been observed moving it by 24 mid-proof, failing a proof about a system that was behaving
  correctly. Keep `execution-algo-engine` scaled to 0 until yu08, and scale it back to 0 after.
- **`yu13-stp-and-replace` mints a fresh epoch**, which takes the seeded risk state with it.
  Anything run afterwards needs `scripts/yu15/seed-proof-fixtures.sh` again. It goes last.

### The order

Bring up the rig, seed, quiet it, and open three forwards:

```bash
MATCHER_URL=http://localhost:18110 bash scripts/yu15/seed-proof-fixtures.sh
kubectl -n traderx --context kind-traderx-yu12-cluster scale deploy/execution-algo-engine --replicas=0
# separate terminals:
kubectl -n traderx --context kind-traderx-yu12-cluster port-forward svc/order-matcher   18110:18110
kubectl -n traderx --context kind-traderx-yu12-cluster port-forward deploy/trade-processor 18091:18091
kubectl -n traderx --context kind-traderx-yu12-cluster port-forward svc/reference-data   18085:18085
```

**Block 1 — nothing disrupts anything (run straight through)**

```
yu03-risk-proof                 yu05-auth-entitlements        yu15-option-persistence
yu05-settlement                 yu13-clordid-suppression      yu10-fix-session
yu05-recon                      yu13-readmodel-effect-end
yu05-regulatory-reproducible
```

**Block 2 — reference-data (already forwarded)**

```
yu04-live-delta
yu04-offline-catchup     <- scales cluster-gateway to 0 and back
```
→ **restart the 18110 forward** (this one reads the replica in-cluster so it does not need the
forward itself, but everything after it does)

**Block 3 — observability.** Needs the stack and forwards on 3200/3100/3000:

```bash
bash scripts/yu15/start-observability-kind.sh
```
```
yu13-otel-trace-join
yu13-otel-reject-trace-log-join      <- rolls the gateway
```
→ **restart the 18110 forward**

**Block 4 — trade-processor rollers**

```
yu06-quality-gate               yu06-consumer-halt
```
→ **restart the 18091 forward**

**Block 5 — destructive, in increasing order of damage**

```
yu15-risk-extract        kills one member; it recovers on its own
yu08-algo-slicing        starts algo traffic — scale the engine back to 0 afterwards
yu13-cancel-ingress      rolls the gateway            -> restart 18110
yu13-stp-and-replace     fresh epoch                  -> re-run seed-proof-fixtures.sh
```

That is **two forward restarts** for the whole suite, against six or more if you follow the
runner's order by hand.

### When a proof fails, check these before believing it

1. **Is the forward alive?** `curl -s -o /dev/null -w '%{http_code}' localhost:18110/ready` — a
   `000` is a dead tunnel, not a defect. Anything run after a roller needs its forward remade.
2. **Is the image the baseline?** A leftover historical build from an interrupted run makes proofs
   report a *different build's* behaviour, truthfully. Check both the StatefulSet and the gateway.
   The builds that can be left behind today are `traderx/cluster-node:yu15-pre-1k` / `:yu15-stp-1k`,
   which `yu13-stp-and-replace` rolls onto deliberately and restores on EXIT — an interrupted run is
   how one survives. (The bare `:yu15-pre` / `:yu15-stp` tags were removed 2026-08-22 and cannot be
   left behind any more; if you see one named anywhere, that text is stale.)
3. **Has a previous proof moved a security's mark?** The last trade price IS the mark (ADR-051), so
   a proof that crossed the same ticker at a different price can drift the reference until a later
   proof's limit falls outside the collar. Seen live: `yu10-fix-session` rejected **1410 of 1426**
   orders with the FIX ingress working perfectly — the session logged on, every order was sequenced
   and every one reached the read model, they were simply all collared. Re-running
   `seed-proof-fixtures.sh` re-anchors the mark and it went to 1463/1463, 0 rejected. The script's
   own header records the same failure once before, on JPM. **Re-seed before `yu10-fix-session`**,
   since it runs after the option and settlement proofs have both traded IBM.
4. **Are the fixtures seeded?** On this tier an account or security exists only once sequenced, and
   most proofs count effects rather than inspecting rejections — so a missing fixture surfaces as a
   false accusation about the system, not as `UNKNOWN_ACCOUNT`.

## The proofs

### Risk gateway & durable control feeds (YU03–YU04)

| Script | What it proves (falsifiable claim) | CI counterpart |
|---|---|---|
| [`yu03-risk-proof.sh`](yu03-risk-proof.sh) | The two-tier in-memory risk gateway rejects orders that breach a control (position/notional/restriction/kill-switch); each reject control is demonstrated live. Takes a sub-command (`controls`, …). | `BlpRiskStateTest`, `RiskControlControllerTest`, `OrderMatcherRiskMismatchTest`, `EntitlementGateTest` |
| [`yu04-live-delta.sh`](yu04-live-delta.sh) | A control-feed change is delivered as a **live delta** with no consumer restart: a security injected at reference-data appears in the gateway's risk replica without anything being restarted. (The source watermark is printed for context, not asserted — it advances asynchronously, so it is read only after catch-up.) | `ControlFeedSubscriberTest` |
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
