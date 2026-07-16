# Issue: back YU05 TCA with YU07's tick store (arrival-price + VWAP gaps)

**Status: open (identified 2026-07-15). A cross-state integration gap — best delivered as a NEW
state on the tip, NOT by editing YU05 or YU08. Sequencing analysis below.**

## The gap

YU05's `TcaService` (trade-processor) computes its benchmark from an in-memory `PriceHistoryStore`
fed by the live `pricing.*` tick feed. Two consequences, both confirmed live on kind:

- **`arrivalPrice` is often `null`.** It's the last tick at/before `exec − tca.window-minutes`
  (default 5 min). For trades that executed before 5 min of price history had been captured (e.g.
  the journal-replayed seed trades near boot), no such tick exists → null. TWAP still computes
  because it only needs ticks *inside* the window. (META `trd-09b-4` had a TWAP over 19 samples but
  a null arrival price.)
- **No VWAP benchmark.** YU05 deferred VWAP (FR-PTC32) because the synthetic price feed carries no
  per-tick volume to weight by. TCA only offers TWAP.

`TcaService.java`'s own comment is explicit and aspirational: *"Both are computed the same way once
the professor's TAQ dataset (real trades + volume) is available, without touching this class's
contract."* — i.e. the fix was foreseen but never built.

## What already exists vs what's missing

| Capability | Where | Status |
|---|---|---|
| `TcaService` (TWAP benchmark, slippage-bps, arrival price field) | YU05 · trade-processor | Built; **never overridden in any later state** |
| Historical tick store (real TAQ, DuckDB, per-tick volume) | YU07 · standalone `tick-store` | Built |
| DuckDB volume-profile query + VWAP | YU08 · `execution-algo-engine` | Built — but a **different service** than TCA, and used for execution scheduling, not TCA |
| TCA reading the tick store / VWAP benchmark / dense arrival price | — | **Unbuilt** |

The raw material to close the gap exists (YU07 store + YU08's DuckDB/VWAP reference code); the
retrofit — pointing `PriceHistoryStore`/`TcaService` at the tick store — is what's missing.

## Why it can't go in YU05 (hard constraint, not preference)

YU05 is an **ancestor** of YU07. Generation composes a state with its ancestors only (YU05 = YU05 +
YU04 + YU03 + YU02), so a YU05 generation **cannot see YU07's tick store**. Modifying YU05's
`TcaService` to read it would break YU05 standalone generation. The retrofit must live at
YU07-or-later.

## Recommended home: a NEW state on the tip (≈ YU10), not an edit to YU08

- **YU08 is complete + verified**, and its theme is execution algos, not post-trade TCA — reopening
  it re-validates a done state and mixes concerns.
- TCA lives in **`trade-processor`**; YU08's VWAP/DuckDB code lives in **`execution-algo-engine`** —
  so the retrofit touches a different service than YU08 owns; it's not a natural in-place YU08 edit.
- A **new state parented on YU09 inherits both** YU07's tick store and YU08's `DuckDbVolumeProfileSource`
  pattern, so it can reuse the query approach without duplicating it and keep YU08 pristine.

Fallback: bundle into YU08 in place if it's specifically wanted there — accept the re-verify + scope
mixing.

## Scope for the new state

- Point `PriceHistoryStore` (or a new source behind the same interface) at YU07's tick store so
  price history is dense and reaches back arbitrarily far → `arrivalPrice` populates for any trade.
- Add a VWAP benchmark option to `TcaService`, reusing YU08's DuckDB volume-profile query pattern
  (real per-tick volume now available from TAQ).
- Keep `TcaService`'s external contract unchanged — the `benchmarkPrice`/`arrivalPrice`/`slippageBps`
  fields already exist; this only changes where the numbers come from.
- Acceptance: a replayed or live trade returns a non-null arrival price and (optionally) a VWAP
  benchmark, computed from historical tick data; existing TWAP path and slippage sign convention
  unchanged. Bench-compare not required (read-side only, off the hot path).

## Presentation note (YU05 deck)

Until this lands, present TCA honestly: show the TWAP benchmark + slippage (both real), and note
arrival price is null for warm-up-starved replayed trades. Do not present arrival price as a live
value. See `presentation/notes.md` YU05 slide-7 deep-dive.
