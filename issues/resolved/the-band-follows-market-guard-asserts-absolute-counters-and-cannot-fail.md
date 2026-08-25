# `yu17-band-follows-market` asserted absolute counters, so its movement test could never fail

**Filed and resolved 2026-08-25**, during the format-8 phase-machine proof chip. Sibling of
[`stillness-assertions-on-the-global-applied-sequence-race-the-live-feed`](stillness-assertions-on-the-global-applied-sequence-race-the-live-feed.md):
same class — a reading that stopped being about the thing it names — found by the same sweep.

It mattered because the format-8 mint scope (§5, last row) lists `yu17-band-follows-market` among
the collar proofs that must **stay green as the regression guard** that the width fix does not move
equity-priced behaviour. The mint changes band and grid behaviour. A guard that cannot fail, over
exactly the mechanism being changed, is the worst possible placement for one.

## What was observed

`scripts/proofs/yu17-band-follows-market.sh:93`, EXPECT=after arm:

```bash
[[ "${R1:-0}" -ge 1 && "${C1:-0}" -ge 1 ]] || fail "member-0 counters did not move: ..."
```

`R0` and `C0` were captured at line 49 and **never compared to anything** — they appeared only in
an echo. `traderx_band_reanchors` and `traderx_band_stranded_cancels` are lifetime counters on a
long-lived epoch. Live on the rig, **before the proof did anything**:

```
traderx_band_reanchors{member="0"}         1
traderx_band_stranded_cancels{member="0"}  3
```

So the assertion was already satisfied on arrival, and on a live-feed epoch those counters only
climb. It could never fail again. Its failure text — "member-0 counters did not move" — narrated a
movement test the assertion did not perform.

## The second defect, found while fixing the first

The obvious hardening (compare all three members' absolutes, since the re-anchor is replicated) is
**also wrong**, and it fired immediately on the first repaired run:

```
member-0: band_reanchors=2 stranded_cancels=4
member-1: band_reanchors=2 stranded_cancels=4
member-2: band_reanchors=4 stranded_cancels=4     <- restarted 61 min after 0 and 1
[FAIL] member-2 band counters (4 4) disagree with member-0 (2 4)
```

on a cluster in **perfect agreement on the book digest**. `bandReanchors` / `bandStrandedCancels`
are plain in-process fields on `MatchingEngine` (`:106`), never written to the snapshot. A member's
absolute reading is therefore a function of how much log **that process** has applied since it
started — a member that restored from a snapshot skips the applies the snapshot already captured.
Cross-member equality of the absolutes is a check that cannot pass against a correct system.
`yu17-retick-determinism` had already recorded the same fact for its post-failover step; it was not
recorded anywhere a reader of the band proof would find it.

**The per-member DELTA across one scenario is the replicated reading**, because every member
applies the same commands.

## What changed

* Both predicates moved into `scripts/proofs/lib-consensus-readings.sh` —
  `assert_band_effects` (deltas, exact counts, per member) and `assert_order_effects` (the trade
  counter bracketed by the order-ref generator, so a trade delta is attributable to *this proof's*
  orders rather than to any writer in the window). Neither is open-coded in a proof any more.
* `lib-consensus-readings-selftest.sh` carries the live vacuity as a **standing red arm**:
  `assert_band_effects 1 3 1 3 1 1` — the exact readings above — must go red. It does. The old
  absolute form passed on them.
* `yu17-band-follows-market.sh` now captures a per-member baseline, asserts **exactly one**
  re-anchor and **exactly one** stranded cancel on each of the three members (`>= 1` would pass on a
  band that moved twice), and asserts the trade delta only inside a window in which exactly its own
  four orders were sequenced.
* Its `EXPECT=before` arm gained the mirror assertion it never had: on the pre-change build the band
  must **not** move at all.

## Verified

`EXPECT=after` green on `kind-traderx-yu12-cluster` / `traderx/cluster-node:yu17-markwait2`,
2026-08-25: `deltas: reanchors 1  stranded 1  trades 2  order_refs 4`, all three members reading a
+1/+1 delta off three different absolutes (2, 2 and 4). The red arm is demonstrated offline by the
selftest rather than by breaking the rig.

## Also still true, and not fixed here

`traderx_cluster_trades` is feed-proof (a `PRICE_TICK` books no trade) but **not** writer-proof: the
algo engine, another lane, or a human with curl all land in it. That is why `assert_order_effects`
refuses to read it without the order-ref bracket, and why every proof asserting an exact trade delta
must run while `execution-algo-engine` is scaled to zero (`scripts/proofs/README.md`).
