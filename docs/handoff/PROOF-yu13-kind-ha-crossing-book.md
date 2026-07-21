# PROOF: YU13 crossing-book HA recovery on kind (T-LOB14)

**Date:** 2026-07-21 · **State:** `YU13-limit-order-book` · **Cluster:** kind
`traderx-yu12-cluster`, 3 members (one per worker, 1 CPU / 1536Mi each) + gateway, image pinned
`traderx/cluster-node:yu13` (arm64, local kind — the `linux/amd64` constraint is GKE-only).

The new risk surface vs YU12 is that the **resting book is now replicated state that must survive
snapshot and failover with price-time priority intact**. Snapshot format 2 carries book geometry,
per-security band anchors, and open rows in ascending-reference (= arrival) order so restore
rebuilds each price level's exact FIFO.

Member `/metrics` was extended for this proof with the engine's order-independent recovery digest
(`traderx_book_open_orders`, `traderx_book_order_hash`, `traderx_cluster_next_order_ref`), so
cross-member book equality is **directly asserted**, not inferred from the applied position.

## Fixture — a book whose correctness is order-sensitive

On AAPL, deliberately built so arrival order and price order DISAGREE:

1. ten asks @ **150.050** — arrive FIRST
2. ten asks @ **150.000** — arrive SECOND, but are the BETTER price

A correct book fills the 150.000 level first. A book restored in arrival order, or with scrambled
levels, fills differently — so the fill pattern is a falsifiable test of price priority.

## Sequence and evidence

Every row is the live digest from all three members.

| Phase | member0 | member1 | member2 | assertion |
|---|---|---|---|---|
| Book built | open=20 nextRef=21 hash=-8886438795743000405 | identical | identical | book replicated identically |
| Snapshot taken | snaps=1 on all three | | | book captured in a format-2 snapshot |
| **Crash #1** (leader m2 killed) | role=1 (promoted) | role=0 | recovering | promotion without operator action |
| m2 recovered | open=20 hash=-8886438795743000405 | identical | **open=20 hash=-8886438795743000405** | **format-2 snapshot round-tripped the full 20-order, two-level book** |
| Price-priority sweep (BUY 1000 @150.000) | open=10 trades=20 hash=7674545002782119168 | identical | identical | filled EXACTLY the ten 150.000 asks; the 150.050 level untouched |
| **Crash #2** (leader m0 killed) | recovering | role=0 | role=1 (promoted) | second failover; **a snapshot-recovered member successfully LED** |
| m0 recovered | hash=7674545002782119168 | identical | identical | book identical on all three |
| Post-crash order | ref **22** | | | generator continued, no reissue |
| Final sweep (150.050 level) | open=1 trades=40 nextRef=24 hash=-3220934722725496943 | identical | identical | remaining level filled exactly; all members identical |

## What is proven

- **Snapshot format 2 round-trips the full resting book across a failover.** The killed leader
  reloaded its own format-2 snapshot and reconstructed a book hash byte-identical to the two
  members that never restarted.
- **Price-time priority survives the failover.** The post-failover sweep consumed exactly the ten
  better-priced asks — the ones that arrived SECOND — and left the worse level untouched. Arrival-
  order restore would have produced a different fill set.
- **Book identical on every member** at every checkpoint (three distinct hashes across the run,
  each matching on all three members).
- **Zero ID reuse across two crashes and two promotions.** `nextOrderRef` is strictly monotonic:
  1 → 21 → 22 → 23 → 24. It never regressed and never reissued a reference.
- **Determinism across epochs.** The same input sequence, replayed in a completely different
  cluster epoch after a full wipe, produced the identical book hash (`-8886438795743000405`).

## What is NOT proven, and why (inherited defect, not YU13)

The **empty-disk rejoin** — a wiped member joining an ALREADY-ADVANCED cluster — could not be
demonstrated. The joiner loops `INIT → CANVASS → FOLLOWER_LOG_REPLICATION` forever at
`applied=-1` while the live members serve normally. This is the pre-existing Aeron 1.51 defect
documented in the YU12 lane as
`docs/handoff/ISSUES-yu12-rejoin-term-poisoning-2026-07-19.md` (degenerate leadership-term entries
in the RecordingLog; snapshots are not shipped to joiners in 1.51 static-member elections). Its
documented remediation is a full clean reset to a new epoch.

It is **independent of YU13**, established by two observations:

1. It reproduced on a cluster whose book was EMPTY and where no format-2 snapshot with book
   content existed — so neither the crossing book nor the new snapshot shape can be implicated.
2. No snapshot-format, off-grid, or band error appears anywhere in the joiner's logs; the failure
   is entirely inside consensus log replication, before the service ever loads state.

Three members starting together from empty always converge correctly (done four times in this
session). Only join-into-a-running-cluster is affected.

## Reproduce

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx scale statefulset order-matcher-cluster --replicas=3
# seed the real accounts + reference universe FIRST — without it every order books nothing
# silently (the OSFF-1 gate); then build the two-level fixture and kill leaders.
```
Helpers used for the digest assertions live in the session scratchpad (`ha-lib.sh`). Two
environment gotchas they encode: this shell is **zsh** (an unquoted `"$VAR cmd"` does not
word-split), and `grep` is **ugrep** (which treats `{` as an interval operator, so metric lines
must be matched with `awk index()`).
