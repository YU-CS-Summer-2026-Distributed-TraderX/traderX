# Pre-mint producer sweep: the $10–$100 equities under the price-derived grid

**Status: MEASURED 2026-08-25**, against the live kind rig (`:yu17-markwait2`) and the operative
`scripts/` tree, per `format-8-price-derived-grid-design.md` §5 ("Pre-mint sweep") and
`prove-cluster-engine-change` §1b. This is a report, not fixes — the fixes belong to the mint
chip. Written by the proof-set chip alongside the §5 red halves.

**The question.** The seven equities the map moves from tick 1000 to tick 100 — DB, UBS, BAC, C,
FNF, FIS, MS — have their half-band tighten from ±$65.54 to **±$6.5536** (65 536 × 100 Px). Any
`/orders` producer under `scripts/` that rests one of them further than the *new* band from the
live feed reference goes red at the mint. Live references at measurement (publisher, 00:24Z):

| ticker | live | open/close (committed) | new band around live |
|---|---|---|---|
| DB  | 16.612 | 16.80 / 17.05 | [10.06, 23.17] |
| UBS | 29.782 | 29.40 / 29.90 | [23.23, 36.34] |
| BAC | 42.958 | 41.10 / 41.70 | [36.40, 49.51] |
| C   | 60.384 | 59.20 / 58.60 | [53.83, 66.94] |
| FNF | 49.500 | 49.50 / 49.95 | [42.95, 56.05] |
| FIS | 75.976 | 74.70 / 75.20 | [69.42, 82.53] |
| MS  | 90.623 | 90.50 / 91.10 | [84.07, 97.18] |

## Hits

### 1. `scripts/proofs/yu03-risk-proof.sh:90` — BAC rested at a literal 40 (CONDITIONAL, the only hit)

`{"security":"BAC","side":"Buy","quantity":11,"limitPrice":40}`, rested three times per run
(lines 112, 116 expect `NEW`; line 114 expects `RESTRICTED`), seeded at `BAC:40` (line 104).

- **Arithmetic**: |40 − 42.958 live| = **2.96 < 6.55** — inside the new band *today*. The break
  arrives when the live reference leaves **[33.45, 46.55]**; against the committed close 41.70 the
  headroom is $4.85 of publisher walk.
- **Why it is conditional and not clean**: the script's own comment (lines 99–102) says the seed
  pins the reference at the traded price. Since the feed adapter went live (2026-08-24) that is no
  longer true — the adapter re-sequences the publisher's BAC within one flush, so the proof's
  correctness silently depends on the *publisher's* walk staying within ±$6.55 of the literal 40.
  Under the current ±$65.54 band that coupling is invisible; the mint makes it a ~$5-headroom bet.
- **For the mint chip**: order (and seed) BAC at the live publisher price the way
  `seed-proof-fixtures.sh` does (`live_px`), or move the restricted-security arm to a ≥$100
  ticker whose band the mint does not change. Also update the stale lines 99–102 while there
  (message/mechanism rule: the comment asserts a pinning the adapter has revoked).

yu03's other arms are unaffected: IBM@190/200 (Δ ≤ 15.4, band unchanged at ±$65.54) and the
IBM@400 PRICE_COLLAR arm (Δ 215, refused on both builds).

## The seeder's `hold()` crossings — claim VERIFIED, not inherited

`scripts/yu15/seed-proof-fixtures.sh:231–233` crosses NVDA/AAPL/IBM with both legs at
`live_px <ticker>` (read from the same publisher `/prices` payload the adapter sequences):
distance from reference ≈ 0, safe under any band — and all three are ≥$100 instruments whose grid
the mint does not move anyway. Two caveats recorded, neither a mint blocker:

- `live_px` falls back to a literal `200` for a ticker absent from the feed (line ~229 awk
  `END {if (!f) print "200"}`). All three holds would then be refused — loudly, since 2026-08-22
  `hold()` treats a refused leg as fatal. Pre-existing, unchanged by the mint.
- The seeder's FIRST pass (line 99) ticks all 20 fixture tickers flat at `PRICE:-200` — for DB
  that is 12× the live price — before the same script re-seeds the whole quoted universe at live
  prices and the adapter resumes. **Post-mint wrinkle, benign**: a book *created* inside that
  seconds-wide window on a $10–$100 ticker would derive tick 1000 from the transient 200 reference;
  no producer orders in the window (the holds run after the live re-seed), and §2.3's empty-book
  re-derivation heals it at the next empty admission. Noted so the mint chip does not rediscover
  it as a mystery `tickDrift`.

## Producers checked and clean (the population, per vacuous-pass-audit rule 2)

Every `/orders` producer under `scripts/` was enumerated (41 files) and each was checked for
resting prices on the seven tickers. None besides yu03 names them with a literal price:

- `scripts/sim/session.mjs` — quotes RELATIVE to the prevailing price (its own stated rule 4:
  "a hardcoded limit is how a script rejects itself"), probes each anchor AT the published price,
  rounds to cents — and cents sit on every producible map tick (each divides 10 000 Px). Clean.
- `scripts/proofs/` — yu05-settlement (IBM 190/200), yu10-fix-session (IBM @ live), yu13-*,
  yu15-*, yu16-* (minted `RM…`/`DUP…`/`STP…`/`BND…` throwaways, bonds, or IBM/AAPL),
  seed-option-chain (options at live premium ± cents), yu12-gke-* / yu13-gke-* (other rig, and
  IBM/minted tickers). None rest the seven.
- `scripts/bench/` — loaders default to JPM/GS/COF/DFS (live 195.7/397.4/153.3/126.7 — all ≥$100,
  band unchanged by the mint) with env-overridable tickers; `TICKERS=DB` etc. would inherit the
  tightened band, an operator note, not a code hit.
- `scripts/yu15/demo-otel-traffic.sh` — AAPL only (band unchanged).

## Out-of-scope observations (pre-existing under ADR-066, not mint deltas — left alone)

- `max-load.mjs` defaults `LIMIT=1_000_000` on ≥$100 tickers: already refused PRICE_COLLAR by
  today's engine band on the cluster tier (the bench predates ADR-066 and its comments describe
  risk-gate 4xxs as the expected wall).
- `demo-otel-traffic.sh` seeds its universe flat at 150 and orders AAPL@150 against a live ~238:
  Δ 88 > today's ±$65.54 — the same adapter-overrides-the-seed mechanism as the yu03 hit, already
  live at the current band. Filed here as context for whoever next runs that demo; not a format-8
  regression.
