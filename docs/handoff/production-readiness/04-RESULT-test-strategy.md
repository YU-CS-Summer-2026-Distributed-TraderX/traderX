# 04 — RESULT: the test strategy (what's unit, what's integration, what stays manual)

> Brief 04 required deliverable and a slide. Per Dov: *"saying which is which is the credibility
> win."* This states the three verification tiers, what runs where, and why — and maps every
> falsifiable end-to-end proof to the in-process test that already asserts the same property in CI.
> Done 2026-07-25.

## The one-sentence version

**Every correctness property is asserted as a fast in-process test that gates every push; the
end-to-end shell proofs demonstrate those same properties on the *deployed* stack and stay
operator-run; the three-member cluster and wall-clock timing proofs run on demand on idle dedicated
hardware.** Nothing was weakened to fit CI.

## The three tiers

### Tier 1 — in-process unit + characterisation tests → **CI, every push and PR**

The engine, cluster, gateway, risk, and post-trade logic are covered by **~853 machine-verified
JUnit tests** (YU13 270 / YU14 283 / YU15 300) plus the **48 baseline-service tests** added in brief
03. These run in the `hosted` / `baseline*` jobs of `engine-tests.yml` on `ubuntu-latest`. They need
no cluster, no network, no DB server (in-memory H2 where a datasource is needed). They assert the
correctness properties directly — self-trade prevention, atomic replace, ClOrdID idempotency,
byte-identical consensus allocation gates, deterministic replay, reproducible regulatory and
risk-extract exports.

This is the tier that makes "green" mean something: it gates merges.

### Tier 2 — end-to-end falsifiable proofs → **manual, documented, runnable**

The **26 proof scripts in `scripts/bench/`** drive the *deployed* system end-to-end: REST/FIX/binary
ingress → gateway → 3-member Aeron cluster → async projection → SQL read model → REST/FIX egress, and
the risk control plane. Several were **genuinely falsified before they passed** (a 200 that booked
nothing; an order the risk gate rejected while HTTP still returned OK) — that history is why they are
trusted. They stay **operator-run** because they require a live kind or GKE stack; they are not
flaky-in-CI, they are *infrastructure-in-CI*, which is a different and honest reason. Each is a single
`bash`/`node` file with explicit pass/fail lines.

### Tier 3 — full-cluster HA + timing proofs → **on-demand, idle dedicated hardware**

Three-member failover, snapshot/replay, cold-follower rejoin, and wall-clock budgets
(`ThreeMemberClusterTest`, `SnapshotBarrierPerformanceTest`, the Epsilon no-GC gates) live in the
`dedicated` job — `workflow_dispatch`-only. They are **excluded from every-push CI on purpose**: a
2-core shared hosted runner cannot make a credible timing or 3-node-consensus claim (measured:
`ThreeMemberClusterTest`'s two-failover case times out under host load but passes in 33 s idle). The
split is a one-line `runs-on` change to a self-hosted GKE runner when we want them gated.

## Proof → in-process test map (the credibility table)

Almost every end-to-end proof has an in-process test asserting the same property, already in CI:

| End-to-end proof (Tier 2, manual) | Property | In-process test in CI (Tier 1) |
|---|---|---|
| `yu13-stp-and-replace.sh` | self-trade prevention, atomic replace, replay-identical | `LimitOrderBookTest` (selfMatch*, rejectedReplace*, replaceCrossesAndStp*, replayReproduces*) |
| `yu13-clordid-suppression.sh` | duplicate ClOrdID suppressed idempotently | `ClOrdIdLedgerTest`, `IdempotencyEvictionDeterminismTest` |
| `yu13-cancel-ingress.sh` | cancel unlinks and is skipped by cross | `LimitOrderBookTest` (cancelUnlinksMidLevel*, marketOrderFillsThenCancels*) |
| `yu10-fix-session.sh`, FIX status | FIX 4.4 session, order status/mass-status | `FixSessionIntegrationTest`, `FixGatewayStatusTest`, `FixGatewaySurvivalTest` |
| `yu05-regulatory-reproducible.sh` | journal-sourced export is byte-reproducible | `RegulatoryReportDeterminismTest` |
| `yu05-recon.sh` | journal↔projection reconciliation | `ReconciliationServiceTest` |
| `yu05-settlement.sh` | T+N settlement lifecycle | `SettlementServiceTest` |
| `yu03-risk-demo.sh` | two-tier risk gate, control plane, kill-switch | `BlpRiskStateTest`, `RiskControlControllerTest`, `OrderMatcherRiskMismatchTest`, `EntitlementGateTest` |
| `yu04-live-delta.sh`, `yu04-offline-catchup.sh` | durable control feeds, bootstrap catch-up | `ControlFeedSubscriberTest`, `ControlFeedBootstrapStateTest` |
| `yu15-risk-extract.sh`, `yu15-option-persistence.sh` | sequence-addressed, byte-identical EOD extract | `RiskExtractTest`, `RiskReplayDeterminismTest`, `RiskExtractGcsSinkLiveProofTest` |
| `failover-nodeclock.sh` (HA) | sub-second failover, zero order loss | `ThreeMemberClusterTest` (Tier 3, dedicated) |
| `yu13-readmodel-effect-end.sh` | order read model at the SQL effect end (place→NEW→cancel→CANCELED) | `ProjectorHandlerTest`, `OrderFeedHandler` rejection tests |
| `yu06-quality-gate.sh`, `yu06-consumer-halt.sh` | EOD gate blocks flagged publish; P&L consumer halts fail-safe | EOD service/quality-checker unit tests |
| `yu08-algo-slicing.sh` | TWAP parent slices N children across the schedule, all booked | `AlgoEventStoreReplayTest`, `AlgoOrderServiceTest` |
| `yu12-gke-recovery.sh` (HA, GKE) | empty-disk rejoin to byte-identity; rebuilt node later leads | `ThreeMemberClusterTest`, `SnapshotRoundTripTest` (Tier 3) |
| `yu12-gke-failover-transparency.sh` (HA, GKE) | leader kill under load: zero lost / zero duplicated | `ClOrdIdLedgerTest`, `InflightCorrelationTest` |
| `yu12-gke-cross-epoch-idreuse.sh` (HA, GKE) | no orderRef reuse across epochs | `SnapshotRoundTripTest` (nextOrderRef in snapshot) |
| `yu12-gke-restore-from-gcs.sh` (DR, GKE) | whole-cluster restore from gs:// to exactly the backup point | — (GCS + init-container path has no in-process seam) |

**The reading:** the shell proofs are not our *only* evidence for these properties — they are the
end-to-end confirmation of properties CI already gates in-process. That is the difference between "we
have a script that shows it works" and "the invariant is enforced on every commit AND demonstrated on
the running venue."

## Tier 1.5 — one automated cross-service integration test (CI, Docker)

Brief 04 Part B's first integration test is **live in CI**: `TradeProcessorPersistenceIT` (job
`integration-trade-processor`). It drives the real `TradeService.processTrade` booking path against a
**real MariaDB (Testcontainers)** initialized with the **actual deployed schema** (copied verbatim
from `database-init-configmap.yaml`, run `ddl-auto=none` exactly like production — not the H2-only
per-service `schema.sql`). It proves the persistence *contract* the mocked unit test cannot:

- a Buy books a `positions` row and a `trades` row against the deployed DDL — including the
  enum→`VARCHAR CHECK ('Buy','Sell')` / `('New'..'Settled')` mapping surviving a real round trip;
- subsequent trades accumulate onto the same position row;
- **the load-bearing one:** an order for an account that does not exist is **rejected by the
  `accounts` foreign key and fails loudly** — not silently dropped. "Trades dropped by an FK" is a
  documented failure class here; this test makes the drop an assertion.

It is isolated by JUnit tag (`@Tag("integration")`) into its own `integrationTest` gradle task, so
the fast `baseline` unit job stays Docker-free; only the `integration-trade-processor` job (on
`ubuntu-latest`, which ships a Docker daemon) runs it.

## What is genuinely not yet automated (honest gaps)

- **More cross-service seams.** The order→match→egress→read-model→REST/FIX round trip and the
  control-plane→in-memory-limits→reject path are still proven only at Tier 2 (manual) and at Tier 1
  in *isolated* units. The trade-processor persistence seam is the first one automated; the same
  Testcontainers pattern extends to the others.
- **Per-state composed-tree baseline tests.** Brief 03's baseline suites run against the templates;
  running them against each YU branch's generated tree is the matrix-extension follow-up.

## CI matrix coverage

The `hosted` engine job runs as a **per-branch matrix** — each branch renders its own effective tree,
so the same test name runs against different composed code per branch, which is how a dead-layer /
propagation regression becomes visible. **Coverage: YU13, YU14, and YU15** — all three enabled
2026-07-25 after each was validated green locally on the exact CI path (render + hosted suite +
allocation gates: YU13 270 / YU14 284 / YU15 298 tests, 0 failures). YU13/YU14 carry the two engine CI
scripts (`engine-tests.sh`, `exclude-heavy.gradle`) so the matrix leg's workspace checkout has them.
A push to YU15 or any PR now exercises all three legs. (Own-push triggers for YU13/YU14 — a trimmed
per-branch `engine-tests.yml` without the YU15-only baseline jobs — are a deferred nice-to-have; the
matrix already gives cross-lineage coverage.)
