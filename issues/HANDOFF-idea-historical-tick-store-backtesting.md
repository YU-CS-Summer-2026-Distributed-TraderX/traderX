# Handoff: Historical Tick Store + Backtesting / Research Platform (TAQ dataset)

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 02 slides 14/25/44/47, deck 06 in full, deck 07
> slides 33–39). Each idea is a service the deck describes that TraderX does not have.
> Self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's Data Infrastructure storage + Research & Analytics material against
  TraderX. Missing entirely: **time-series tick storage** (columnar/compressed, time-partitioned,
  tiered hot/warm/cold, bulk retrieval for backtesting) and the **research platform** on top
  (point-in-time-correct data access, backtesting engine, walk-forward validation, gated model
  deployment).
- Connected it to an existing asset: the professor's **~3TB compressed NYSE TAQ dataset**
  (trades + NBBO quotes; NOT L2 depth). `HANDOFF-market-data-realism.md` already established the
  user's stance: the dataset is a *pluggable input*, not the architecture driver. This idea is
  the natural home for actually landing it.
- Confirmed non-overlap: YU05 (settlement/reporting/TCA/auth) doesn't include storage or
  research; TCA and the VWAP algo idea are downstream *consumers* of this store (volume
  profiles, benchmark prices).

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Design and scaffold a new YUxx state adding, in slices:

1. **Tick capture**: persist TraderX's own live trades/ticks (from NATS) into a time-partitioned
   columnar store — Parquet on GCS is the honest cloud-native mapping of the deck's
   "columnar, compressed, time-partitioned, cold tier = object store" (deck 02 slides 14/47).
2. **TAQ ingestion pipeline**: normalize a *subset* of the professor's TAQ data (a few symbols ×
   a few months first — 3TB is a cost/logistics problem, see open questions) into the same
   schema, so real and self-generated history are queryable uniformly.
3. **Query/serving layer**: the deck says researchers get "a Python client library + bulk
   Parquet" (deck 02 slide 27) — DuckDB over Parquet/GCS gives range queries and aggregations
   with near-zero infrastructure. Serve volume profiles (for VWAP), benchmark prices (for YU05
   TCA), and raw tick ranges.
4. **Backtesting engine (slice 2+)**: replay stored ticks through a strategy interface with the
   deck's safeguards — point-in-time correctness (no look-ahead), walk-forward validation,
   realistic transaction costs (deck 06 slide 4, deck 07 slide 35). A killer TraderX-specific
   variant: **replay historical ticks through the actual BLP matching engine** as a
   deterministic simulation — the LMAX event-sourced design makes this uniquely cheap here.
5. (Stretch) Gated model-deployment story (deck 06 slides 7–8): research zone is read-only,
   models reach production only via a registry + review pipeline. Probably a doc/ADR-level
   commitment in this state, implemented later.

## Key files

| Path | Why it matters |
|---|---|
| `HANDOFF-market-data-realism.md` | Prior session's TAQ context + the 6 data-driven candidates |
| `order-matcher/` output events on NATS | Source for capturing self-generated tick history |
| `HANDOFF-idea-execution-algo-engine.md` | VWAP volume-profile consumer of this store |
| `HANDOFF-combined-yu05-state.md` | TCA consumer of benchmark prices from this store |
| `CLOUD-ARCHITECTURE.md` | GCP project layout (buckets, regions) for the GCS tier |

## Architecture / context the next chat needs

- Deck storage requirements worth quoting in the spec: 5–7 year regulatory retention;
  hot/warm/cold tiering with the query layer stitching across tiers; compression pipeline
  (delta-encode timestamps, dictionary-encode symbols, RLE, then LZ4/ZSTD — deck 02 slide 44).
  Parquet+ZSTD gets most of that for free — say so in research.md instead of hand-rolling.
- Deck research requirements: point-in-time serving (deck: "data served as of publication date,
  not revised date"), survivorship-bias-free universe, deterministic/reproducible runs (seeds +
  versioned inputs). For TraderX v1, the practical subset is: immutable versioned datasets +
  deterministic replay.
- Cost note: this is the one idea with a real GCP bill attached (3TB in GCS ≈ $60/mo standard,
  less on coldline; egress/processing extra). Start with a small symbol/date slice; the
  user should size the budget before bulk upload.
- The store is fully off the hot path — zero risk to BLP throughput (a NATS consumer writing
  Parquet batches). No bench-compare sensitivity except the added NATS subscriber.
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**; staging CI/CD only with user approval.

## Decisions already made (don't re-litigate)

- The TAQ dataset does not drive architecture; it plugs into whatever schema this state defines
  (user's explicit prior decision, recorded in `HANDOFF-combined-yu05-state.md`).
- Parquet + object store + DuckDB-class query layer, not a kdb+/QuestDB deployment — right size
  for the project, matches the deck's cold-tier reality.
- Capture TraderX's own ticks first (works with zero external data), TAQ ingestion second.

## Open questions / known issues

- Where does the 3TB physically live right now, and how does it get to GCS (upload bandwidth,
  cost approval)? Ask the user before designing the ingestion job.
- TAQ format specifics (fixed-width vs CSV vs vendor binary, which years) — need a sample file
  before writing the normalizer.
- Backtest strategy interface: same signal API as a future signal pipeline (deck 03 slide 16
  shadow mode), or a bespoke replay harness? Leaning bespoke-first.
- Does the research query layer need auth? (YU05's entitlements will eventually gate it —
  fine to leave open in v1.)

## Suggested first steps for next chat

1. Read this doc + `HANDOFF-market-data-realism.md`.
2. Ask the user: TAQ sample file + where the 3TB lives + GCS budget comfort.
3. Confirm state id/name (e.g. `YUxx-tick-store-research`).
4. Implement slice 1 (NATS → Parquet capture of TraderX's own trades + a DuckDB query recipe)
   end-to-end before touching TAQ.
