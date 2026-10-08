# RI-25 — Tape identity, availability status and replay universe need cleanup

Updated: 2026-10-07. Status: in progress (structured tape status integrated; identity/universe work remains). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

The prior absent/corrupt shape ambiguity is resolved by integrated structured status/reason codes. PRICE_TICKERS still limits the replay universe. TAQ ingestion drops SYM_SUFFIX; historical truncated/duplicated stored objects require live revalidation.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Add stable producer status/reason codes for missing, malformed and loaded tape, preserving readable detail.
- [ ] Reconcile configured ticker/reference universe and available tape; identify uncovered symbols explicitly.
- [ ] Preserve security identity when normalizing root/suffix; settle mapping using source conventions before ingestion changes.
- [ ] Revalidate stored-object integrity when authorized; no deletion/re-ingest or TAQ-to-Parquet conversion is authorized here.
- [ ] Update stale collar/mark descriptions to current tape, session-close, model and labeled synthetic behavior.

## Acceptance

- [x] Synthetic absent/corrupt/loaded fixtures return distinct machine-readable outcomes.
- [ ] Class-share fixtures cannot merge two securities.
- [ ] Historical cloud data counts stay unverified until measured; no sampling claim inferred from medians.

## Original reports

- [the-publisher-signals-absent-and-corrupt-tape-identically.md](../open/the-publisher-signals-absent-and-corrupt-tape-identically.md)
- [the-replayed-universe-stops-at-the-publishers-price-tickers.md](../open/the-replayed-universe-stops-at-the-publishers-price-tickers.md)
- [tick-store-drops-taq-sym-suffix-and-merges-share-classes.md](../open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md)
- [truncated-uploads-make-one-day-of-the-tick-store-unreadable.md](../open/truncated-uploads-make-one-day-of-the-tick-store-unreadable.md)
- [HANDOFF-collar-price-sourcing.md](../open/HANDOFF-collar-price-sourcing.md)

Next: Reconcile source/ticker identity and universe coverage without assuming vendor rights.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 class-window assignment: producer tape state/reason contract and necessary prose-parsing consumer adaptation only. Universe/data identity/storage portions remain queued. Handoff shared HANDOFF-CODEX-TAPE-STATUS-20261007.md; exact checkout claim pending.

2026-10-07 review: `f7678382`,76 independent producer/consumer checks pass. Added invalid-day timestamp fixture still crashes status with RangeError; narrow R1 time-domain refusal correction queued for next class-window slot. No live tape/health claim. Shared evidence ri25-coordinator-20261007. Broader data work remains queued.

2026-10-07 R1 reviewed locally at `289724b9`:87 independent current producer/consumer tests pass,30source/evidence hashes match. Numeric Date/asOf/clock-overflow refusal addresses status crash; status-contract milestone locally accepted pending controlled integration. Broader data/universe/security/financial work remains queued. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri25-coordinator-r1-20261007/review.md`.

## October 7 integration outcome

Producer health and the console consume structured tape state/reason codes, distinguishing missing, corrupt, unaddressable clock, replay, pause and completion. Generated publisher/console tests passed. Source/ticker identity, share classes, reference-universe breadth and licensed data work remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.

## Evening offline declared-universe coverage

2026-10-07: codex-tape-status owns the bounded offline diagnostic milestone in an isolated checkout at accepted `22d4c906`. Locally verified, coordinator review pending. Explicit supplied declaration and tape metadata, optional exact CSV directory and decoded print sample, preserve source-role differences and exact identities. This makes exclusions observable; it does not reconcile/widen the production universe, normalize classes, inspect stored cloud objects, or establish admission/financial validity. Prior structured tape status is integrated; broader RI25 tasks remain open. Shared index updates are coordinator-owned.

Offline diagnostic acceptance: 18 actual CLI cases pass against source and generated reader roots; exact four-reader parity and prior 87 status/consumer cases pass. Deliberate wrong-overlap/omitted-exclusion implementation controls fail. Synthetic fixture inventory proves exact role-specific overlap/gaps and private deterministic provenance; no current cloud universe/admission/financial claim. Diagnostic milestone alone is implemented; production reconciliation/widening and security identity remain undecided and open.

## Offline source identity loss follow-up

2026-10-07 evening: source-identity diagnostic/proof locally verified, coordinator review pending, dependent on locally reviewed81eb3184 universe candidate. Current YU07 CT/CQ AST-isolated SELECT is exercised on owned synthetic CSV only; separate reader tuple repetitions from distinct root/suffix tuples sharing output symbol. Exact filter/null/type scope and private hashes are required. No actual TAQ conversion, ingestion, stored data, alias mapping, normalized key, production universe, finance, core, or cloud changes. Original identity/storage decisions remain unresolved.

Source identity milestone evidence: current pinned YU07 SELECT/reader/filter on invented CT/CQ proves root-only output loses three reader-tuple distinctions in each eligible fixture, with repeated observations classified separately. Source/generated13 CLI cases pass, stalled-child20s control is reaped, exact ingester parity and prior105 diagnostic/status/consumer cases pass. Omitted-suffix/wrong-projection private implementation variants fail. This is source/reader behavior evidence only; canonical root/suffix mapping, actual vendor data and stored integrity remain unmeasured/unresolved.

October 7 evening integration: the local milestones above passed coordinator review; delivery-specific historical pending notes are superseded by the final integration record. Broader unchecked deployment, mapping, sizing and recovery work remains open.
