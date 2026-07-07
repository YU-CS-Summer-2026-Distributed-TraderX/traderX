# Handoff: Market Data Quality Engine (spike / staleness / sequence-gap detection)

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 02 slides 17, 37–43). Each idea is a service the deck
> describes that TraderX does not have. Self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's Data Infrastructure requirements against TraderX's market-data path
  (price-publisher → NATS → consumers, plus the YU03 binary tick path into the order-matcher).
- Identified the **Data Quality Engine** as fully missing. Deck 02 slide 17: price spike
  detection, staleness detection, cross-source comparison, missing-instrument detection, and the
  key principle — *"flagged events are forwarded with quality flags, NOT silently discarded."*
- Deck 02 slides 38–39 give complete pseudocode for **sequence gap detection** (monotonic seq
  numbers, buffer out-of-order, request retransmission, discard duplicates); slides 42–43 give
  the **z-score spike detector** (rolling window, flag |z| > threshold). Both are directly
  implementable.
- TraderX today: synthetic prices flow with no sequence numbers, no gap detection, no staleness
  or spike flags. A wedged price-publisher simply means silently stale marks.

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Design and scaffold a new YUxx state adding data-quality machinery to the market-data path:

1. **Sequence numbers on the tick feed** (price-publisher stamps a monotonic seq per instrument
   or per stream) + **gap detection in consumers** — at minimum in the order-matcher's binary
   tick consumer, with a Prometheus counter and a recovery/replay story (NATS JetStream replay
   covers retransmission naturally if the feed becomes a JetStream stream).
2. **Quality engine** (small stream-processor service or a stage inside price distribution):
   z-score spike detection and staleness detection per instrument; events forwarded with a
   quality flag, never dropped.
3. **Consumer reactions**: the deck's fail-safe table (deck 07 slide 44) says "market data feed
   goes stale → strategies stop trusting quotes." TraderX equivalent: the YU03 risk gateway's
   price-reasonability control should distrust flagged/stale ticks (fail-safe: block, don't
   fail-open), and the UI should badge stale prices.
4. **Grafana panel + alerts** for gap count, staleness, and spike flags (dashboards exist since
   YU03 — extend that dashboard set).

## Key files

| Path | Why it matters |
|---|---|
| `price-publisher/` | Feed source — gets sequence numbers + (deliberate) fault injection |
| `specs/YU02.../runtime-overrides/order-matcher/` | Binary tick consumer — gap detection lives here |
| `specs/YU03-in-memory-risk-gateway/` | Price-reasonability control that should consume quality flags |
| `cluster-addons/` + Grafana dashboards | Where the observability lands (YU03 added a risk dashboard) |
| `HANDOFF-durable-control-feeds.md` | JetStream patterns (YU04) reusable for feed replay |

## Architecture / context the next chat needs

- **Pipeline gotcha (important):** `price-publisher/src/main.js` is one of the files with a
  *dead-looking override path* — its actual generation source is a legacy mechanism, documented
  in `HANDOFF-durable-control-feeds.md`. Verify propagation before editing.
- The deck's rationale for why this matters (slide 38): "a missed gap means permanently lost
  data… an incorrect VWAP, an incorrect position reconciliation, a missing audit record." That
  maps 1:1 to TraderX plans: the execution-algo idea needs trustworthy volume/price streams, and
  YU05's reconciliation assumes marks weren't silently wrong.
- Demo value: this idea comes with a natural **fault-injection story** — make price-publisher
  deliberately drop/duplicate/spike ticks via an admin endpoint, and show the system detecting
  and flagging in Grafana. That's a strong "real-world-like" demonstration for the internship.
- The z-score detector is warm-path; the gap detector on the order-matcher's binary consumer
  must respect the no-allocation discipline (it's near the hot path — pre-allocated ring/bitmap
  for the reorder buffer, extend the allocation-gate tests).
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**, bench-compare after touching anything near the order/tick path.

## Decisions already made (don't re-litigate)

- Flag-and-forward, never silently drop (deck principle; also what YU03's controls expect).
- Gap detection belongs in consumers with seq numbers stamped at the source — not a middlebox —
  matching how exchange feeds actually work (deck 02 slide 38).
- Fail-safe wiring: stale/flagged prices make the risk gateway *more* restrictive, never less.

## Open questions / known issues

- Should the tick feed move onto NATS JetStream (durable, replayable — mirrors YU04's approach
  for control feeds) or stay core-NATS with an explicit retransmission request? JetStream is
  probably the answer but has throughput implications — bench it.
- Z-score window + threshold defaults (deck says threshold 5–10) — pick per-instrument defaults
  and make them admin-configurable like the YU03 `/risk/control/*` API.
- Does the UI get quality badges in v1 or is Grafana enough? (User preference.)

## Suggested first steps for next chat

1. Read this doc + the propagation-gotcha section of `HANDOFF-durable-control-feeds.md`.
2. Trace the current tick path end-to-end (price-publisher → NATS → order-matcher binary
   consumer → risk gateway price-reasonability) and write it into research.md.
3. Confirm state id/name with the user (e.g. `YUxx-market-data-quality`).
4. Implement seq numbers + gap counter first (small, measurable), then the quality engine.
