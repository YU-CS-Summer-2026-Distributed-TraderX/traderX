# A book costs ~4.1 MB empty, so the member heap silently caps how many securities the venue can list

**Found 2026-08-27 by hitting it**: widening ADR-072's replayed universe from 23 to 99 tape symbols
took the venue from ~69 to **146 books** and killed the leader with
`java.lang.OutOfMemoryError: Java heap space at LimitBook.<init>(LimitBook.java:63)`.

## The arithmetic, which nobody had done

`MatchingEngine.DEFAULT_BOOK_LEVELS = 1 << 17` = 131,072 price levels, and `LimitBook`'s
constructor **preallocates the whole grid** the moment a security's first order arrives:

| allocation | count | bytes (compressed oops) |
|---|---|---|
| `RestingOrder[levels]` × 4 (bidHead, bidTail, askHead, askTail) | 4 × 131,072 | 2,097,152 |
| `long[levels]` × 2 (bidQty, askQty) | 2 × 131,072 | 2,097,152 |
| `long[levels >> 6]` × 2 (bidBits, askBits) | 2 × 2,048 | 32,768 |

**≈ 4.1 MB per book, whether it holds two orders or two thousand.** Against the members'
`-Xmx512m` that is a ceiling of roughly **120 books**, before the engine's own state, the order
pool, snapshots or anything else. 146 does not fit, and 69 was already using about 40% of the heap.

## Why it is worth a file rather than a note

**Every symptom points somewhere else.**

- The failure names `LimitBook`, so it reads as a book bug rather than a capacity limit.
- It fires on the **first order for a new security**, which can be hours after the config change
  that widened the universe — the trigger is order flow, not deployment.
- **The pod stays `1/1 Running`.** The OOM kills the `clustered-service-0-0` thread; `/health` is
  served by another thread and keeps answering `applied 49944, phase OPEN` with a plausible,
  advancing sequence. All three members read healthy. The only tell was the gateway's
  `/ready` → `{"connected":false}` — the *client* reporting that the engine it talks to is gone.
  (Same shape as `throw-out-of-main-does-not-exit`: a dead loop inside a live JVM.)
- `MAX_SECURITIES` is 1024 on every current build, so the obvious capacity knob says there is room.
  **The binding limit is heap, and nothing declares it.**

## The rig constraint that makes this tighter than it looks

The kind "nodes" are four containers on **one 9.7 GiB Docker VM** — `kubectl get nodes` reports
~9.7 GiB for *each*, which is the same memory counted four times. Three members × 146 books is
~1.9 GiB of book arrays alone. Raising `-Xmx` is not free on this box, and it was already brought
down once on 2026-08-27 by resource exhaustion.

## What to do

- **Short term**: keep the replayed universe inside the heap. ~50 tape symbols fits `-Xmx512m`
  with headroom; 99 does not. `PRICE_TICKERS` is the knob and it is in
  `specs/YU17-otc-rates/generation/kubernetes/cluster/eod-chain.yaml`.
- **Before any future widening**: `books × 4.1 MB` against `-Xmx`, and remember the non-tape
  instruments (treasuries, corporates, options) hold books too — the census in
  `seed-proof-fixtures.sh` prints the real total.
- **The real fix, out of scope here**: `BOOK_LEVELS` is a *deterministic-core* parameter, so
  lowering it or making the grid sparse cannot be rolled gradually — scale to zero, wipe, mint.
  A sparse or lazily-grown level array would decouple venue breadth from heap entirely, and is
  the only thing that makes "every book comes alive" affordable at tape scale.
- **A guard worth having**: the engine could refuse a book allocation it cannot afford and say so,
  rather than dying inside a thread nothing watches. A member whose service thread is dead should
  not report `phase: OPEN`.

## Related

- `specs/YU17-otc-rates/system/adr-072-replayed-prints-become-order-flow.md` — the widening and its
  measured artifact sizing (the transport ceiling is a rate; **this** ceiling is a count)
- `issues/open/the-replayed-universe-stops-at-the-publishers-price-tickers.md` — step 1 of that
  issue says widening costs a re-check of ADR-070's flush sizing. It does; it also costs this,
  which that issue did not know about.
