# Research: YU05 Post-Trade Compliance Bundle

## The trade-identity gap (why slice 1 is what it is)

Discovered while scoping settlement/reconciliation: order-matcher already has everything needed
for a deterministic, replay-safe trade id.

- `MatchingEngine` increments a single-writer `tradeCounter` field on every booked fill/market
  trade (`long tradeSeq = ++tradeCounter;`), and `tradeCounter` is persisted in snapshot v3
  (`SnapshotStore.Data.tradeCounter`) and restored on recovery — so `tradeSeq` is a pure function
  of journal replay order, exactly like every other BLP-owned value.
- `OrderSnapshot.tradeIdFor(long tradeSeq)` already exists (`"trd-09b-" + tradeSeq`), with a doc
  comment stating it's meant to be "shared by the projector (DB row id) and the NATS bridge
  (published id) so both agree exactly — the single-id-per-trade property 009 got from one UUID."
- **It was never called at the one place that matters.** `TradeOrder.fromEvent()` — the class that
  builds the NATS payload trade-processor consumes — sets `id` from `OrderSnapshot.orderIdFor(e.orderRef)`
  (the *order's* id) instead. `TradeService.processTrade()` in trade-processor then ignores even
  that and mints `UUID.randomUUID()` for the MariaDB row id.

Net effect verified before writing any code: a MariaDB `TRADES` row today carries a random UUID
that has no derivable relationship to the journal's `tradeSeq`, the order it came from, or the
NATS message that created it. This is the actual root blocker for settlement (needs a stable id to
track through a lifecycle), reconciliation (needs a stable id to diff against the journal),
regulatory reporting (needs a stable audit-trail id), and TCA (needs a stable id to attach a
benchmark to) — so wiring up the id that already exists is the correct, minimal slice 1, not scope
creep.

## Why the trade blotter doesn't need a snapshot format change

YU03's risk state needed a new snapshot v3 section because gateway/BLP admission decisions must be
correct *instantly* on recovery, before any command can be admitted. The trade blotter has a
weaker requirement: it only needs to be correct by the time recon/settlement next runs, which is
always after recovery completes. Investigation of `LmaxEngine.afterPropertiesSet()` confirmed:
`outputDisruptor.start()` runs *before* `recoverLiveFromJournal()` — meaning the real
`MatchingEngine`'s replay of the journal during recovery publishes through the live output ring and
its full handler chain, not a separate shadow path (the shadow-engine "verify" replay at the bottom
of `afterPropertiesSet` is a distinct, read-only integrity check, not the recovery path itself).
Existing handlers (`AccountTradeHandler`, `NatsBridgeHandler`, `ProjectorHandler`) individually
guard on `readModel.isReplaying()` to suppress *external* side effects (a second NATS publish, a
second DB write) during replay — but the ring itself runs. A new handler that does **not** guard on
`isReplaying()` therefore receives every historical `KIND_TRADE_BOOKED` event during recovery,
rebuilding the blotter for free, with no snapshot format change and no new persistence layer.

## Why reconciliation is scoped to "forward-looking" in slice 1

A full, symmetric reconciliation (detecting both a DB row missing from the journal, and a journal
fill missing from the DB) needs the *complete* trade history available for comparison. The blotter
as built in slice 1 is bounded (`recon.blotter.capacity`, default 500,000) to keep memory use
predictable — appropriate for its primary purpose (recovery-safe forward tracking) but insufficient
to prove a DB row has **no** corresponding fill anywhere in a potentially years-long journal.
Slice 1 therefore only classifies `MATCHED`, `MISSING_IN_PROJECTION`, and `FIELD_MISMATCH` (all
provable from a bounded forward window); `ORPHAN_IN_PROJECTION` detection is deferred until either
the blotter gains unbounded/spillover retention or a direct journal-replay-based comparator is
built (mirroring `JournalReader`, which already exists for the *input* journal but has no
equivalent on the output/trade side).

## Where the real runtime schema lives (generation pipeline gotcha, confirmed again)

`database/initialSchema.sql` at the repo root is the base/legacy schema (Postgres-flavored,
capitalized identifiers) and is **not** what the running k8s MariaDB actually initializes from.
The real, live schema is `specs/YU02-lmax-kubernetes/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml`
(a ConfigMap embedding a lowercase-identifier, MariaDB-flavored SQL script) — this is what
`prepare-state-YU02-lmax-kubernetes-gke-manifests.sh` actually renders into the cluster. Adding
`settlementdate` to `database/initialSchema.sql` alone would silently do nothing. Per the
established fix: the YU05 override copies the file from `generated/code/target-generated/kubernetes-runtime/manifests/base/database-init-configmap.yaml`
(the currently-merged, real content) into `specs/YU05-post-trade-compliance/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml`,
adds the new column there, and the change was verified empirically (marker string, regenerate,
grep the generated output) before trusting it — see `generation/implementation-status.md` for the
verification record.

## Deferred source-side work

Regulatory reporting, TCA, and real auth/entitlements are specified (requirements, data model,
ADRs) in this same state but not implemented in slice 1 — see `plan.md` "Sequencing after slice 1."
None of them are blocked on external infrastructure the way YU03's durable control feeds were; they
are deferred purely for slice-size discipline (one real, complete capability per commit, same as
every prior state in this lineage).
