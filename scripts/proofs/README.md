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

### Three constraints that are not about forwards

- **The applied sequence is GLOBAL, and there are permanent OTHER WRITERS — now two of them.**
  Since 2026-08-24 the feed adapter holds its own cluster session and sequences `PRICE_TICK`
  directly — one price-publisher flush is **69 sequences** with nothing of yours in them. Since
  2026-08-26 (ADR-072) the tape replay submits sampled TAQ prints as **real orders**, continuously,
  at order 6/s. So `applied` moving by N, or not moving at all, says nothing about your commands,
  and neither does any raw order or trade counter. Three proofs asserted on `applied` and two were
  GREEN on window luck — the write-up is
  `issues/resolved/stillness-assertions-on-the-global-applied-sequence-race-the-live-feed.md`.
  **Do not read a global counter for a delta. Source
  `scripts/proofs/lib-consensus-readings.sh` and use a predicate from it** —
  `quiesced_order_refs` for order-shaped commands, the contract id itself for OTC
  bookings, `assert_order_effects` for what an order DID (the trade counter, bracketed by the ref
  generator so the trade delta is attributable to *your* orders), `assert_band_effects` for
  ADR-066 re-anchors and stranded cancels. Every one of those now reads a
  `*_operator_*` metric: the replay is tagged at the source by account range and excluded at the
  writer, so the four readings kept their names and their meanings while the numbers underneath
  them changed. If you need another reading, add it there, and the bar is: name a counter the
  other writers do not advance and show it standing still on a live rig while `applied` climbs —
  `yu17-replay-attribution.sh` is that demonstration for the four that exist.
  `./lib-consensus-readings-selftest.sh` pins every predicate offline, red
  arm and green arm, no rig needed.
- **The band and trade counters are LIFETIME totals, and they are per-process.** Two separate
  traps, both found 2026-08-25 in `yu17-band-follows-market.sh`. (1) `traderx_band_reanchors` /
  `_stranded_cancels` read 1 and 3 on the standing epoch *before* any proof runs, so an absolute
  `>= 1` assertion is satisfied on arrival and can never fail — the reading is a delta against a
  captured baseline or it is nothing. (2) They are plain in-process fields on `MatchingEngine`,
  never snapshotted, so a member's *absolute* value is a function of how much log that process has
  applied since it started: members 0 and 1 read 2 where member 2, restarted 61 minutes later, read
  4 — on a cluster in perfect agreement on the book digest. Cross-member equality of the absolutes
  is a false assertion; the per-member *delta* is the replicated one.
- **The format-8 proofs are GREEN as of the mint (2026-08-25) — the deliberate-red convention has
  retired.** The nine `yu17-*` format-8 proofs (`fnma-collar`, `option-collar`, `fine-grid`,
  `book-retick`, `retick-determinism`, `session-closed-rejects`, `preopen-queue-open`,
  `halt-survives-failover`, `closed-survives-restart`) default to `EXPECT=after` and that is now
  simply the claim, asserted normally: **a red is a regression to chase, like any other.** The
  `EXPECTED RED until the format-8 mint (design §5)` text those proofs used to print is gone from
  all of them. `EXPECT=before` still re-measures the defect against a pre-mint build and remains
  the banked red half; its lines now say so (`banked against :yu17-markwait2`) rather than
  implying a mint that has not happened.
- **The two durability proofs need arming.** `yu17-halt-survives-failover` and
  `yu17-closed-survives-restart` default to `DESTRUCTIVE=0` and SKIP: every step of each is the
  destructive part (one kills a leader, the other restarts a member), so there is no safe prefix.
  Run them `DESTRUCTIVE=1 EXPECT=after`. `yu17-closed-survives-restart` also takes `MEMBER=` — the
  default `2` exercises the restore path only, and the **leader** must be run separately because
  that additionally triggers an election.
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

### Session phase machine & price-derived grid (YU17, format 8)

The nine proofs that rode the `SNAPSHOT_FORMAT` 7 → 8 mint. Every one was written **before** the
build, run red against `:yu17-markwait2` to bank its red half, and turned green by the mint on
2026-08-25. `EXPECT=after` is the default on all nine, so the suite states the post-mint claim;
`EXPECT=before` re-measures the defect on a pre-mint build and is the banked half.

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu17-session-closed-rejects.sh`](yu17-session-closed-rejects.sh) | The venue can be **CLOSED**, and a CLOSED venue refuses an order `kind 2 / MARKET_CLOSED` — while a **cancel still succeeds** (decision (c): a cancel only ever reduces exposure) and an **OTC swap booking still completes** (decision (d): the halt is the venue's book, not bilateral desk business). `/health` carries `phase`, `POST /session` carries the command. | `SessionPhaseGateTest`, `SessionSnapshotRestoreTest` |
| [`yu17-preopen-queue-open.sh`](yu17-preopen-queue-open.sh) | **PRE_OPEN queues without trading**: three orders that would cross are held (`queueDepth` 3, trade counter flat across a window in which exactly those three were sequenced), then the open releases them and they match. A close with a non-empty queue **cancels the queue** with `SESSION_CANCELED` (decision (b)), and the queued rows render as `QUEUED` in the read model (decision (g)). | `SessionPhaseGateTest`, `QueuedOrderSizingTest` |
| [`yu17-halt-survives-failover.sh`](yu17-halt-survives-failover.sh) | **A halt a failover cannot bypass.** CLOSE the venue, kill the leader, and the new leader is still CLOSED — the phase rides `T_SESSION` through the election, not the dead leader's memory. `DESTRUCTIVE=1` required; every step is the destructive part. | `SessionSnapshotRestoreTest` |
| [`yu17-closed-survives-restart.sh`](yu17-closed-survives-restart.sh) | **A halt a restart cannot bypass.** CLOSE the venue, restart a member, and it restores CLOSED with its queue intact — the phase and the queue are snapshot records, not process state. Run against a follower (`MEMBER=2`, restore only) **and against the leader** (election *and* restore). `DESTRUCTIVE=1` required. | `SessionSnapshotRestoreTest`, `SnapshotCompletenessAuditTest` |
| [`yu17-book-retick.sh`](yu17-book-retick.sh) | **The empty-book re-derivation, end to end across a decade crossing.** A ticker minted with no price rests on the provisional grid (`/bbo` `tickPx` 1000); the book empties; a tick at $1.15 crosses decades; the next admission **re-derives** (`traderx_book_reticks` +1) and the 20× probe is refused `PRICE_COLLAR` while a limit inside the new band rests at `tickPx` 10. Gate V4's detonator target — see [the assertion issue](../../issues/resolved/the-book-retick-tickpx-assertions-could-neither-pass-nor-fail.md) for why its two grid readings had to be repaired first. | `PriceDerivedGridTest`, `LimitBookRetickTest`, `BookGridDerivationTest` |
| [`yu17-retick-determinism.sh`](yu17-retick-determinism.sh) | A re-derivation is **replicated, not local**: the re-tick survives a leader kill and all three members agree on the book digest afterwards, with the per-member `traderx_book_reticks` delta identical. The counter is a lifetime, per-process field, so the reading is a delta — never an absolute. | `PriceDerivedGridTest`, `GridRestoreFormatTest` |
| [`yu17-fine-grid.sh`](yu17-fine-grid.sh) | A sub-dollar instrument gets a **grid fine enough to quote on**: a limit the pre-mint tick-1000 grid refused as off-grid is admitted at tick 10. | `BookGridDerivationTest` |
| [`yu17-fnma-collar.sh`](yu17-fnma-collar.sh) | The collar **binds on FNMA** (~$1.15, the instrument the mint scope §7(f) exists for): a 20× order is refused `PRICE_COLLAR` where the inert ±$65.54 equity band admitted it. | `PriceDerivedGridTest`, `BookGridDerivationTest` |
| [`yu17-option-collar.sh`](yu17-option-collar.sh) | The collar **binds on a listed option premium**: same 20× refusal, off the option's live premium rather than a category constant — scope decision (e), discharged by the reference-derived map rather than an `OPTION_BOOK_TICK_PX`. | `BookGridDerivationTest`, `AllocationGateTest` |

**Not yet tabulated**: the seven `yu16-*` proofs and `yu17-band-follows-market`,
`yu17-swap-netting`, `yu17-swaption-terms`, `yu17-fx-credit`, `yu17-keyed-ack-correlation` have no
rows here. They predate this section and belong to other lanes' work; the gap is recorded rather
than silently filled.

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
