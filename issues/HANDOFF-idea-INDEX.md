# Consolidated ideas backlog (single source of truth as of 2026-07-07)

This doc merges ALL open idea sources into one deduplicated backlog:
- `HANDOFF-production-realism.md` (original 6-item roadmap)
- `HANDOFF-market-data-realism.md` (6 data-driven candidates for the professor's 3TB TAQ dataset)
- The 11-item scored necessity table (produced in the YU05-planning chat; superset of the two above)
- The 8 deck-idea handoffs from the professor's slide-deck analysis (`HANDOFF-idea-*.md`, 2026-07-07)
- `HANDOFF-idea-blp-ha-hardening.md` (CLOUD-ARCHITECTURE.md §7's pre-existing BLP HA/throughput
  backlog — not a deck idea, predates YU03-05, added 2026-07-07)
- `possible_improvements.md` (perf-engineering backlog — stays its own ongoing track, not states)

**If an older doc disagrees with this one, this one wins.** The two older handoffs' candidate
menus are superseded; their architecture/gotcha sections (generation-pipeline dead overrides,
GCP/CI-CD context, two-tier gateway design rationale) remain valid reference.

## Done / in flight — retired from the backlog

| Item | Where | Status |
|---|---|---|
| Pre-trade risk gateway (SEC 15c3-5, two-tier) | YU03 | Done |
| Durable control feeds (outbox → JetStream) | YU04 | In progress (parallel chat, shared traderX dir) |
| Settlement + recon, regulatory reporting, TCA, auth/entitlements | YU05-post-trade-compliance | In progress (parallel chat, own worktree). Agreed remaining order: recon → reporting → TCA → Grafana dashboard → auth |

## Open backlog (deduplicated, in recommended order)

| # | Idea | Handoff / source | Absorbs / overlaps | Depends on |
|---|------|------------------|--------------------|-----------|
| 1 | **EOD price production + overnight batch chain** | `HANDOFF-idea-eod-price-production.md` | Gives YU05 recon/reporting a session boundary | Nothing — zero external deps. **Recommended YU06** |
| 2 | **Overnight VaR/ES batch grid** | `HANDOFF-idea-overnight-var-batch.md` | Completes YU03's two-path risk architecture | **TEAMMATE-OWNED (2026-07-07): a teammate is already building pricing + risk incl. VaR (currently NPV-focused). Do NOT start this — hand him the handoff doc; our side supplies the inputs via #1 (EOD_PRICES_READY gate, versioned price snapshot, consistent position snapshot)** |
| 3 | **Historical tick store + backtesting + replay** | `HANDOFF-idea-historical-tick-store-backtesting.md` | **Merges** table #9 (market data warehouse) + table #10 (realistic price replay) + market-data-realism candidates #1/#4 — replay is a feature of the store, not a state | Professor's sample TAQ data (requested; pilot slice, not 3TB). Parallel-trackable — fully off hot path |
| 4 | **Ops hardening** (secrets mgmt, DR, journal archival) | Table #6 / roadmap #6 — no dedicated handoff yet | Journal archival shares GCS plumbing with #3 | Nothing. Secrets slice is small — can ride alongside #1 |
| 5 | **Execution algo engine (TWAP/VWAP/POV)** | `HANDOFF-idea-execution-algo-engine.md` | YU05 TCA consumes its parent-tagged fills | TWAP: nothing. VWAP profiles: #3 |
| 6 | **Advanced order types + time-in-force (BLP)** | `HANDOFF-idea-advanced-order-types.md` | Pegged orders excluded (need NBBO from #8) | Nothing (tick feed exists from YU03). Hot-path work — bench-compare mandatory |
| 7 | **FIX protocol gateway** | `HANDOFF-idea-fix-protocol-gateway.md` | **Merges** `possible_improvements.md` §4–5 (ingress transport overhead) | Nothing |
| 8 | **Multi-venue + SOR + NBBO** | `HANDOFF-idea-multi-venue-sor-nbbo.md` | **Absorbs the L1 slice** of table #5 (market data dissemination); unblocks #9 and pegged orders | Feasibility call first (stub venue vs full second BLP) |
| 9 | **L2 dissemination + market surveillance** (pair) | Table #4 + #5 remainder / market-data-realism #3 | Explicitly deferred from YU05; surveillance needs L2, L2 needs a consumer | #8 |
| 10 | **Market data quality engine** (gaps/spikes/staleness) | `HANDOFF-idea-market-data-quality-engine.md` | Feeds YU03 price-reasonability fail-safe | Nothing — small; good gap-filler state |
| 11 | Reference-data / corporate-actions backfill | Table #8 / market-data-realism #6 | — | Low priority; mechanical |
| 12 | ML-based dynamic risk calibration | Table #11 / market-data-realism #5 | Extension of YU03; could consume #3's volatility data | Lowest priority |
| 13 | **BLP HA/throughput hardening** (lease starvation, `blp-role` label bug, beyond-42k gateway bottleneck, replication durability testing) | `HANDOFF-idea-blp-ha-hardening.md` | `CLOUD-ARCHITECTURE.md` §7's pre-existing backlog, predates YU03-05 | **TEAMMATE-ADJACENT: Tani is already working BLP performance (snapshot/journal/terminal-retention) — coordinate before starting, this is adjacent (HA-replication + gateway-CPU-path), not identical** |

## Sequencing rationale (agreed 2026-07-07)

- **YU06 = EOD/batch chain** because it needs no external data, is medium-sized, and has the
  highest fan-out (gates VaR, serves YU05 recon/reporting, deck's most emphasized pattern).
- **VaR grid moved to the teammate's track** (he's on pricing/risk, NPV first). The clean
  interface split: we own the data/orchestration spine (EOD prices, position snapshots, gate
  events, batch chain SLA monitoring), he owns the valuation/risk math that consumes it — the
  deck's exact Data-Infrastructure→Risk / Risk→Pricing boundary. His batch becomes the first
  external consumer of YU06's `EOD_PRICES_READY`, which raises YU06's priority, not lowers it.
- **YU07 candidate shifts to**: execution algo engine (TWAP slice — no deps) or the tick store
  (if the professor's sample data has arrived). ML risk calibration (#12) is now probably also
  teammate territory — check before anyone picks it up.
- **Teammate base-state risk — resolved (2026-07-07):** the pricing/risk teammate will work off
  **YU03-in-memory-risk-gateway** (parented correctly, not the stale 009b base this was originally
  worried about), **locally only, no pushes to the cloud**. No cloud pipeline exists for YU03 yet
  and none is needed for this — his work stays local. If that changes later (he wants to deploy),
  revisit CI/CD scope then; don't set anything up preemptively.
- **Tick store (#3) runs as a parallel track when sample TAQ data arrives** — independent,
  off-hot-path. Professor asked "how much data for what": answer sent — pilot slice of 5–10
  liquid symbols × 1–3 months of trades+quotes for (a) tick store/backtesting, (b) VWAP volume
  profiles, (c) VaR return scenarios; full 3TB only after the pipeline is proven.
- Quality engine (#10) is deliberately parked mid-list as a small gap-filler between larger states.

## Standing conventions (apply to every item)

Spec pack under `specs/YUxx-<name>/` mirroring YU03's file list; same-named branch; parent
lineage recorded; **commit, never push**; isolated staging CI/CD only with explicit user
go-ahead; bench-compare after anything near the order/tick path; verify generation-override
propagation empirically (dead-override gotcha — see `HANDOFF-market-data-realism.md`
"Generation pipeline gotcha"); never commit HANDOFF-*/scratch docs.
