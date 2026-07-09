# ADR-026: Last-Trade Closing Price + Versioned Immutable EOD Snapshot

**Status:** Accepted, implemented (slice 1)
**Date:** 2026-07-08
**State:** `YU06-eod-price-production` (parent `YU05-post-trade-compliance`)

## Context

TraderX has no trading-session close and no official closing prices — a position's mark is whatever
the last tick was. Real back-office/risk workflows require an authoritative closing price per
instrument, published once, that every downstream job agrees on. Deck 02 slide 24 is explicit: *if
Risk and Portfolio use different closing prices, VaR and NAV are irreconcilable.*

Two independent questions: (a) how is the closing price defined, and (b) how is it stored so every
consumer sees the same value.

## Decision

**(a) Closing price = last trade price.** Per instrument, the newest sample in trade-processor's
existing `PriceHistoryStore` at/before session close. That store already subscribes to
price-publisher's `pricing.*` feed, which carries last-trade prints — so this needs no new data
source and no BLP hot-path event.

**(b) Versioned immutable snapshot.** Closing prices are written to append-only MariaDB tables keyed
by `(session_date, version[, security])`. A published version is never updated; a correction
produces `version + 1`. The gate event carries `(session_date, version)` so every consumer reads
exactly the gated version.

## Alternatives Considered

- **Last mid price** — needs live bid/ask book state at close; order-matcher emits only last-trade
  prints and there is no L2 book feed (deferred out of YU05). Rejected for v1.
- **Mini closing auction in the BLP** — the realistic method, but real matching-engine (hot-path)
  work. Kept as a stretch goal; the snapshot/event/consumer contract is unchanged when the price
  *source* is later upgraded — only what computes the per-instrument close changes.
- **Mutable "current EOD price" row (read-latest-at-job-start)** — rejected: two jobs reading around
  a correction see different values, breaking the consistency invariant that is the whole point.

## Consequences

Positive: demoable immediately from existing data; corrections are auditable (every version
preserved); consumers are trivially consistent (they read a fixed version).

Costs: storage grows per version (bounded — a handful of versions per session at most); the
producer must compute "next version" transactionally. Both are minor versus the consistency
guarantee bought.
