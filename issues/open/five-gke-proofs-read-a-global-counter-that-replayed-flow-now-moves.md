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

## A sixth exposure in the same family, found 2026-08-27 by enumeration

`scripts/proofs/yu13-gke-replace-proof.sh` reads **`traderx_book_open_orders`** — a venue-wide
gauge with a member label only, **no operator-scoped twin** (unlike the four counters this issue
opens on) and no per-ticker cut — and asserts arithmetic on it at two sites:

```
150:  [[ "${BEFORE%% *}" == "${AFTER%% *}" ]]              # depth UNCHANGED across the replace
176:  [[ "$(( ${BEFORE%% *} - 1 ))" -eq "${AFTER%% *}" ]]  # exactly one order out
```

Both are the shape that broke `yu13-readmodel-effect-end` on the kind rig, where the same
`$(( BEFORE - 1 )) -eq AFTER` read **287 → 284** on a correct cancel. Same fix available
(`c691b849`): stop the replay for the measurement, on an `EXIT` trap.

**It is the ONLY genuinely exposed site of the seventeen proofs that read this gauge.** The
enumeration, so nobody has to redo it:

| proof | how it reads the gauge | verdict |
|---|---|---|
| 14 proofs (`yu17-band-follows-market`, `yu17-book-retick`, `yu17-fine-grid`, `yu16-invisible-orders-repro`, `yu12-gke-failover-transparency`, `yu17-keyed-ack-correlation`, …) | cross-member agreement / divergence — m0 vs m1 vs m2 | **safe as written** — who wrote the orders is irrelevant when the claim is that the members agree |
| `yu13-readmodel-effect-end:251` | `$((BEFORE-1)) -eq AFTER` | **fixed** `c691b849` — replay paused for the measurement |
| `yu13-stp-and-replace:785` | `BEFORE == AFTER` | **covered** — `pause_replay` at :335 with no resume before :785, so the replay is off for the whole proof |
| `yu13-cancel-ingress:465,475` | computed into `BEFORE_DEPTH`/`AFTER_DEPTH` | **safe** — never asserted; used only in the display string at :505, annotated *"which the replay moves independently"* |
| **`yu13-gke-replace-proof:150,176`** | `BEFORE == AFTER` **and** `$((BEFORE-1)) -eq AFTER` | **EXPOSED** — GKE tier, not in `run-proofs.sh`, nothing here will ever red it |

**The identity-claim reader DOES exist on the GKE tier** — checked statically 2026-08-27, because
"does the read model exist over there" was the open unknown blocking the three sites that need an
identity claim rather than a twin swap:

- `gke/trade-processor.yaml` is in the GKE manifest set **and** in `gke/kustomization.yaml`, exposing
  **18091** (container, service and probe all agree), and `scripts/yu15/bring-up-gke.sh` rolls it.
- The route is `@GetMapping("/accounts/{accountId}/orders")` with `?status=all`, in the **YU17**
  layer of `trade-processor`'s `OrderController` — the operative layer, and the same image both
  tiers run.

So the pattern `yu13-cancel-ingress` and `yu13-readmodel-effect-end` already use is **portable, not
novel**, and whoever takes points 2–4 does not need a running GKE cluster to find that out. What
still needs the tier is *exercising* the result — a reader that exists is not a reader that answers.

**The durable fix is a `traderx_book_operator_open_orders` twin**, matching the four that already
exist. Pausing the replay works but is a workaround for a missing counter: it mutates shared rig
state mid-suite, needs a trap to avoid stranding the rig feedless, and makes the reading depend on
a scale-down succeeding. See `issues/the-adr-072-counter-countermeasure-stops-one-metric-short.md`.
