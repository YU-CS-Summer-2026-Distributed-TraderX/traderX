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
sub-command (e.g. `yu03-risk-demo.sh controls`). Bring the target state up first (see the state's
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
| [`yu03-risk-demo.sh`](yu03-risk-demo.sh) | The two-tier in-memory risk gateway rejects orders that breach a control (position/notional/restriction/kill-switch); each reject control is demonstrated live. Takes a sub-command (`controls`, …). | `BlpRiskStateTest`, `RiskControlControllerTest`, `OrderMatcherRiskMismatchTest`, `EntitlementGateTest` |
| [`yu04-live-delta.sh`](yu04-live-delta.sh) | A control-feed change is delivered as a **live delta** with no consumer restart (watermark advances before→after). | `ControlFeedSubscriberTest` |
| [`yu04-offline-catchup.sh`](yu04-offline-catchup.sh) | A change made while a replica is **offline** is caught up on reconnect via the watermarked-snapshot bootstrap (would be lost in YU03). | `ControlFeedBootstrapStateTest` |

### Post-trade / compliance (YU05)

_All four source [`yu05-common.sh`](yu05-common.sh) (shared setup: trade-processor port-forward + edge-proxy)._

| Script | What it proves | CI counterpart |
|---|---|---|
| [`yu05-auth-entitlements.sh`](yu05-auth-entitlements.sh) | Real HS256 JWT auth + entitlement codes (cross-account→401, foreign-scope→403, no-bearer→401), replacing the YU02–YU04 open surface. | `JwtAuthenticatorTest`, `EntitlementGateTest` |
| [`yu05-recon.sh`](yu05-recon.sh) | Reconciliation is the CQRS integrity check: journal↔projection classified matched / missing / mismatch, plus the full-history orphan sweep. | `ReconciliationServiceTest` |
| [`yu05-regulatory-reproducible.sh`](yu05-regulatory-reproducible.sh) | The regulatory export is a **pure function of the journal** — the same query answered byte-reproducibly from the source of truth. | `RegulatoryReportDeterminismTest` |
| [`yu05-settlement.sh`](yu05-settlement.sh) | The real settlement lifecycle: a booked trade walks Processing → Settled with a settlement date. | `SettlementServiceTest` |

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
| [`yu13-stp-and-replace.sh`](yu13-stp-and-replace.sh) | The member bundle: **self-trade prevention** (ADR-057, cancel-oldest) and **engine-native atomic replace** (ADR-058). | `LimitOrderBookTest` (selfMatch*, rejectedReplace*, replaceCrosses*, replayReproduces*) |
| [`yu13-gke-replace-proof.sh`](yu13-gke-replace-proof.sh) | The three things atomic replace still needed proving on a **real cluster** — run on GKE because kind cannot carry them. | `LimitOrderBookTest` (replace*) + `ThreeMemberClusterTest` |

### Listed options & EOD risk extract (YU14–YU15)

| Script | What it proves / does | CI counterpart |
|---|---|---|
| [`seed-option-chain.sh`](seed-option-chain.sh) | YU14 setup + smoke: seeds the packaged listed-equity-option chain into the running gateway and smoke-proves one option cross books. (Setup helper for the two proofs below.) | `SeedOptionChainTest` / gateway option-cross tests |
| [`yu15-option-persistence.sh`](yu15-option-persistence.sh) | Listed options reach the SQL read model, and the shipped migration fixes a database created by an older state. | `RiskExtractTest`, `TradeProcessorPersistenceIT` (integration) |
| [`yu15-risk-extract.sh`](yu15-risk-extract.sh) | The EOD risk-extract acceptance proof end-to-end: sequenced cut, byte-identical across members, quiescence, write-once (gs://-aware on GKE). | `RiskExtractTest`, `RiskReplayDeterminismTest`, `RiskExtractGcsSinkLiveProofTest` |

### High availability

| Script | What it proves | CI counterpart |
|---|---|---|
| [`failover-nodeclock.sh`](failover-nodeclock.sh) | Node-clock-precise failover measurement for the Aeron Cluster: automatic promotion, sub-second, zero order loss. | `ThreeMemberClusterTest` (dedicated job) |

## Notes

- **Scope:** this reorganization is on the **YU15 tip** (the presentation branch). Earlier branches
  (YU10/YU13/YU14) still keep these scripts under `scripts/bench/` with the flat layout — internally
  consistent for them. Propagate the move to those branches only if a state needs to demo from its own
  worktree with the new layout.
- **`yu05-common.sh`** is a shared helper, not a standalone proof.
- Benchmarks, load generators, and one-off measurement/utility scripts remain in
  [`../bench`](../bench) on purpose — they measure throughput/latency and have no pass/fail.
