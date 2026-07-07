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

**Correction found immediately after, before the first test run: `TradeOrder.fromEvent` is not
the live write path.** `output.legacy-trades.enabled` defaults to `false` (confirmed in the actual
deployed `order-matcher-deployment.yaml`), so `TradeSubmitHandler` — the only publisher of the
`/trades` subject `TradeOrder.fromEvent` builds — never runs, and trade-processor's
`TradeFeedHandler`/`TradeService` never receive anything in the default configuration. **The
real, live writer of the MariaDB `TRADES` table is order-matcher's own `ProjectorHandler`**
(`output.projector.db.enabled=true` by default), which does a direct `INSERT IGNORE INTO trades`
straight off the output ring — its own doc comment already said as much: "written by the
order-matcher read-model Projector off the output ring... instead of inline by trade-processor."
`ProjectorHandler.toTrade()` was *already* using `OrderSnapshot.tradeIdFor(e.tradeSeq)` correctly,
and `INSERT IGNORE` already made it idempotent against duplicate ids — there was no live trade-
identity bug at all.

**The real live-system gap was settlement, not identity**: `ProjectorHandler.toTrade()` set
`TradeState.Settled` immediately, with no settlement lifecycle whatsoever — confirmed by two
existing integration tests (`LmaxHotPathParityTest`) that asserted `Settled` right after booking
against a real H2-backed projector flush. Fixed there (not in trade-processor): `toTrade()` now
sets `Processing` + a real T+N `settlementDate`, computed with the same business-day math
`SettlementService`'s sweep already expected. The `TradeOrder.fromEvent`/`TradeService` fixes
described above remain correct, tested improvements — they just apply to the *optional, disabled-
by-default* legacy path, not the one actually running in production. Left in place (not reverted)
since turning that flag on later should not resurrect the old bugs.

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

## Full-history reconciliation reuses the shadow-replay pattern, not a new mechanism (FR-PTC10)

`LmaxEngine` already had a shadow-engine journal replay for `verifyJournalReplay()` (a digest-only
integrity check). `reindexFullHistory()` is the same construction (new `MatchingEngine` + discarding
output ring + `seedShadow` + `JournalReader.replay`), swapping the discarding handler for a
`TradeBlotterHandler` writing into an unbounded `TradeBlotter`. Deliberately on-demand
(`POST /recon/full-history/reindex`) and `synchronized` with itself — replaying a potentially large
journal is expensive and must never be scheduled or run concurrently with another replay.

## Regulatory reporting is the same shadow-replay pattern again, generalized (ADR-023, FR-PTC20-22)

`generateRegulatoryReport(fromSeq, toSeq)` reuses the identical shadow-replay skeleton a third time,
this time with `AuditLogHandler` capturing *every* reportable output kind (accept/reject/partial-
fill/fill/cancel/trade-booked), not just trades, filtered to an input-sequence range via
`OutputEvent.inputSeq`. Reproducible byte-for-byte because it is a pure function of (journal range,
seed) — no wall-clock, no external query. Three admin operations now share this skeleton
(`verifyJournalReplay`, `reindexFullHistory`, `generateRegulatoryReport`); a shared private helper
was considered and rejected for this slice — the three call sites differ enough (digest-only vs.
open-ended trade capture vs. range-filtered audit capture) that extracting one now would be
premature; revisit if a fourth consumer appears.

## TCA benchmark source: reusing the existing price feed, not a new one (ADR-024, FR-PTC30-32)

Order-matcher's BLP tracks `lastPrice`/`lastPriceTime` per security internally, but nothing external
observes price *history* — there is no output-ring event for a price tick at all (`OutputEvent` has
no `KIND_PRICE_UPDATED`), so capturing history from the BLP side would mean adding a new event kind
to the hot path, which is exactly the kind of hot-path risk this project avoids without a specific
need. Instead, `PriceHistoryStore` (trade-processor) subscribes to price-publisher's *existing*
`pricing.<ticker>` NATS JSON feed (the same one the Angular front-end's live ticker already
consumes) via a wildcard `pricing.*` subscription — zero BLP involvement, zero new data source.

VWAP is deferred (FR-PTC32): the synthetic feed carries a price but no per-tick traded volume to
weight by, so only TWAP (time-weighted) is computed in slice 1. Both benchmarks compute the same
way once real trade-and-volume data (e.g. the professor's TAQ dataset) is available — swapping the
feed only changes what feeds `PriceHistoryStore.record`, never `TcaService`'s contract.

## Deferred source-side work

Real auth/entitlements is specified (requirements, data model, ADR-025) in this same state but not
implemented — see `plan.md` "Sequencing after slice 1." Not blocked on external infrastructure the
way YU03's durable control feeds were; deferred purely for slice-size discipline (one real, complete
capability per commit, same as every prior state in this lineage) and because it is the largest,
most cross-cutting piece (it gates every endpoint the other four sub-capabilities added).
