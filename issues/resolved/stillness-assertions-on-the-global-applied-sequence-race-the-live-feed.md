# Stillness and exact-delta assertions on the global applied sequence race the live feed adapter

**Filed 2026-08-25** as a one-proof defect (`yu17-swaption-terms`). **Widened to the class and
resolved 2026-08-24 [rig date]** — three sites, one shared predicate set.

It mattered because "the full proof suite green, including the five format-8 proofs" is the
format-8 mint's completion criterion, and this failure does NOT dissolve at the mint's fresh
epoch: it recurs on any rig where the feed adapter is sequencing, which is now every rig.

## What was observed

`yu17-swaption-terms` failed step 1 in the pre-mint baseline:
`the sequence moved 3913841 -> 3913909: an unrepresentable term reached consensus` — a delta of
**68**. The feed adapter sequences the whole publisher universe per flush (`symbols=69`), so the
delta was one ambient flush, not a leaked swaption.

Measured again on 2026-08-24, idle rig, no proof running:

```
traderx_cluster_applied         3937958 -> 3938027   over ~20s   (+69, one flush)
traderx_cluster_next_order_ref  3629333 -> 3629333   same window (unmoved)
```

## The class — three sites, and why the green ones were the dangerous ones

| proof | reading | was |
|---|---|---|
| `yu17-swaption-terms` step 2 | applied delta `== 2` | **red**, observed 68 |
| `yu17-swap-netting` step 2 | applied delta `== 2` | green — its window happened not to straddle a flush |
| `yu16-bond-position` step 1 | `AFTER == BEFORE`, no movement at all | green on window luck |

Nothing regressed. The feed adapter went live 2026-08-24 (it holds its own `AeronCluster` session
and offers `PRICE_TICK` directly — it never touches the gateway), the environment grew a second
writer, and three readings stopped being about the thing they name:

* `AFTER - BEFORE == 2` is not "my two bookings were sequenced". It is "the cluster applied two
  commands" — and it would have passed just as happily on one booking plus one unrelated command.
* `AFTER == BEFORE` is not "nothing of mine was sequenced". It is "no flush landed inside my
  window", i.e. a statement about how fast two HTTP calls were.

`yu16-bond-position` is the one worth dwelling on: it was the strictest assertion, it was green,
and it would have been "fixed" by re-running. Its window is two HTTP calls where
`yu17-swaption-terms`' is long enough to straddle a flush. Same defect, shorter fuse.

## What was rejected

Widening the tolerance to `>= 2` / "allow some drift". That deletes the check: it can no longer
catch a rejected order reaching consensus, which is the entire reason `yu16-bond-position` step 1
exists.

Adapter quiesce (scale the adapter to 0 around the measurement, capture-and-restore). Sound, but
it mutates a shared rig, serialises the proofs against each other, and leaves the rig without a
feed if a script is killed between capture and restore. Not needed once the readings are right.

## The fix

`scripts/proofs/lib-consensus-readings.sh` — one file, sourced by all three, holding the rule and
the readings that survive a second writer. Two readings, and the test each had to pass is "name a
counter the feed adapter does not advance, and show it standing still on a live rig while
`applied` climbs":

* **order-shaped commands → `traderx_cluster_next_order_ref`.** The `ORDER_NEW` ref generator is
  advanced by `TYPE_ORDER_NEW` and nothing else — ticks, FX rates, symbol registers and OTC
  bookings all leave it alone. Refs are issued on apply, BEFORE any verdict, so a rejected order
  that was sequenced still burns one.
* **OTC bookings → the contract id itself.** `onSwapBook` does `contractId = appliedSeq`, so the
  id a booking answers with IS the consensus sequence it landed at. The ids are therefore tied to
  *these* bookings in a way a global delta never was.

`applied_seq` / `quiesced_seq` survive as window BRACKETS and carry the warning at the point of
use. The predicates are `assert_no_orders_sequenced`, `assert_sequenced_in_window` and
`assert_no_contracts_in_window`.

## What each assertion can still catch

* `yu16-bond-position` step 1 — a boundary-rejected order that is sequenced anyway; a retry
  sequenced twice. Demonstrated red on the rig: a legal-shaped order refused 422 by the RISK GATE
  (post-consensus) moved refs `3629334 -> 3629335` and the assertion went red, while the two
  boundary refusals (also 422) moved nothing. The check discriminates on *did it reach consensus*,
  not on the HTTP code — which is exactly the distinction the step is about.
* `yu17-swaption-terms` / `yu17-swap-netting` step 2 — an id invented at the gateway rather than
  assigned by consensus; two bookings collapsing onto one contract; a replayed or
  idempotency-echoed contract presented as new; bookings landing out of submission order; a 200
  carrying no contract at all.
* `yu17-swaption-terms` step 1 — a booking the boundary was supposed to refuse being sequenced and
  accepted, which leaves a contract at a sequence inside the refusal window. **Boundary:** it
  cannot see a booking sequenced and then refused PAST the boundary — but that case answers 422
  with a reason (`yu17-swap-netting` step 1 asserts exactly that shape), not the 400 asserted
  alongside it. The pair of assertions is what closes off "sequenced, then refused".

## Vacuity found while building the fix

`assert_no_contracts_in_window` first shipped without an anti-vacuity guard. A caller
double-prefixed the ids into `SWPT-SWPT-<n>`, nothing matched the id pattern, and "no contract in
the window" came back GREEN off an artifact holding two. An id shape the filter does not recognise
is now a loud probe failure. `scripts/proofs/lib-consensus-readings-selftest.sh` pins all 17 arms
(offline, no rig).

## Siblings found in the sweep — REPORTED, NOT FIXED

Swept `scripts/proofs/` and `scripts/yu15/` for other readers of a cluster-global counter used as
if it were private to one probe.

1. **`yu17-band-follows-market.sh:93` is vacuous today.** Its "after" arm asserts
   `traderx_band_reanchors >= 1 && traderx_band_stranded_cancels >= 1` — absolute values, not
   deltas. `R0`/`C0` are captured on line 49 and never compared. Measured on the live rig before
   the proof runs: `band_reanchors=1`, `band_stranded_cancels=3`. The assertion passes before the
   proof does anything. On a live-feed epoch these counters only ever climb, so it will never fail
   again. Fix is `R1 -ge $((R0+1))`, but it is a different chip's proof.
2. **`yu17-band-follows-market.sh:92`** asserts `traderx_cluster_trades == T0 + 2`. Global
   counter used as private. Feed-safe *today* — the adapter offers only `PRICE_TICK` and ticks
   create no trades — but it breaks under any concurrent order writer, exactly as
   `scripts/proofs/README.md` already documents for `yu08-algo-slicing`.
3. **`failover-nodeclock.sh:49-51`** samples `applied` twice to warn "no traffic is being applied —
   start load first". With the feed live that warning can never fire, so the guard on
   `servingMs` measurement validity is now dead. A probe that stopped discriminating.
4. **`yu12-gke-failover-transparency.sh:119`** quiet-check uses `traderx_book_open_orders`, which
   a band re-anchor CAN move (stranded cancels) — so it is feed-sensitive in principle. GKE proof,
   not exercised on this rig; flagged, not touched.
5. **`yu17-book-retick.sh:130,138`** takes deltas on `traderx_band_reanchors` /
   `traderx_book_reticks`. Same class, but it is one of the five deliberately-red format-8 proofs
   — left alone per the chip's do-not-touch list.
6. Clean, checked and dismissed: `yu13-readmodel-effect-end` and `yu12-gke-failover-transparency`
   assert on `next_order_ref` (order-shaped, feed-blind, and already quiesced across members);
   `yu05-recon:138` reads `traderx_cluster_trades` as a wait-loop high-water target, not a delta;
   `yu13-cancel-ingress` and `yu13-stp-and-replace` print applied sequences in failure diagnostics
   only; `yu15-option-persistence` quiesces on its own outbox version in a log line.

## Also fixed in passing (same family, one size down)

`yu05-regulatory-reproducible.sh` and `yu05-recon.sh` lumped curl `rc=28` in with `rc=7` and
narrated both as "Nothing answered, so this is the transport". Against the current flood epoch the
10s preflight takes rc=28 — a precondition failure reported as a verdict about the wire. The two
rcs are now split: 7 is nothing listening, 28 is "something is listening and did not answer inside
the budget", which on a large epoch is a busy member. The timeout is deliberately unchanged; that
failure is epoch-scale and dissolves at the format-8 mint's fresh-epoch wipe.
