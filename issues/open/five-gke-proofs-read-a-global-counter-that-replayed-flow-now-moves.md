# Five GKE proofs read a global counter that ADR-072's replayed flow now moves

**Filed 2026-08-26** on YU17, at the coordinator's request, alongside the ADR-072 implementation.
**Not fixed.** They are not in `run-proofs.sh`, so nothing on the kind rig will ever red them.

## Why this is worth a file rather than a fix-when-noticed

ADR-072 made the tape replay a permanent writer of order-shaped commands at ~6/s. Eleven readings
in the kind suite stopped being about the thing they named, and were repaired. **These five were
not, because nothing runs them here** — so they will fail **on GKE, months from now, far from the
change that broke them, to somebody with no reason to connect a proof failure to replayed order
flow.** That is the most expensive form this defect takes, and it is the reason the connection is
written down tonight rather than left to be rediscovered.

```
scripts/proofs/yu12-gke-recovery.sh
scripts/proofs/yu12-gke-restore-from-gcs.sh
scripts/proofs/yu12-gke-cross-epoch-idreuse.sh
scripts/proofs/yu12-gke-failover-transparency.sh
scripts/proofs/yu13-gke-replace-proof.sh
```

## What to do when one of them reds

**First establish whether the replay is even running on that tier.** ADR-072's publisher is
`price-publisher:yu17-replay-heal2` and the replay is off unless the `taq-print-sample` Secret is
present; `/health.printReplay.error` answers in one call. **On a tier with no replay these proofs
are unaffected and a failure is a real one.**

If it IS running, the repair is one of the three the kind suite used, and **never a widened
tolerance** — `scripts/proofs/lib-consensus-readings.sh` explains why at length:

- read the operator-scoped sibling (`traderx_cluster_operator_next_order_ref`,
  `traderx_cluster_operator_trades`, `traderx_band_operator_{reanchors,stranded_cancels}`);
- measure a **bracket** rather than an equality, when the quantity legitimately moves;
- assert the **identity** of the thing itself — an order's own row, a probe id, a book's `tickPx`.

## The general form, which is the part worth keeping

**A sweep by function name measures who DELEGATES, not who DEPENDS.** The audit that declared this
class closed after the feed adapter swept for `applied` — the counter the proofs had retreated
*from* — and for the library's function names. Both are blind to a private reimplementation, and
there were eleven. **Sweep for the raw metric name; any private copy must contain it.**

```bash
grep -rln 'traderx_cluster_next_order_ref\|traderx_cluster_trades\|traderx_cluster_applied\|traderx_band_' scripts/
```

## Two residuals in the kind suite, deliberately left

Both survive today and are recorded so they are not rediscovered as new:

- **`yu13-otel-trace-join.sh`** reads `traderx_cluster_next_order_ref` but only PRINTS it. Its
  header claims step 5 asserts "ground truth advanced by exactly the number of orders submitted";
  **the code makes no such assertion.** The comment is stale, not the reading.
- **`yu17-book-retick.sh`** asserts exact deltas on `traderx_book_reticks`, a global. It is immune
  in practice because a retick needs a book to cross a PRICE DECADE while empty, which replayed
  tape prices do not do within a session — but it is immunity by the data's behaviour rather than
  by construction, and a symbol crossing $100 would end it. Its `EXPECT=before` arm asserts
  `band_reanchors` merely MOVED (`>`), which replayed flow alone can satisfy; that arm runs only on
  pre-mint builds.

## Related

- `specs/YU17-otc-rates/system/adr-072-replayed-prints-become-order-flow.md` — the eleven readings
  and the two shapes they fall into
- `scripts/proofs/lib-consensus-readings.sh` — the operator-scoped readings and the admission test
