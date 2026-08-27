# Five GKE proofs read a global counter that ADR-072's replayed flow now moves

**Filed 2026-08-26** on YU17, at the coordinator's request, alongside the ADR-072 implementation.
**Four of the five are still not fixed.** They are not in `run-proofs.sh`, so nothing on the kind
rig will ever red them. `yu13-gke-replace-proof.sh` **is fixed** (`d7144170`, 2026-08-27) — see the
resolution at the bottom, which also corrects two things this file got wrong about it.

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

## The retick readings: quiet for a reason, established 2026-08-27

`yu17-book-retick` and `yu17-retick-determinism` both assert exact deltas on
**`traderx_book_reticks`** — member label only, venue-wide, no operator twin. Structurally in this
issue's class. **Measured and mechanism established, and the mechanism is not the obvious one:**

A retick needs BOTH (`MatchingEngine.rederiveIfEmpty`): the book **empty** (`openOrders() == 0`),
and the derived tick to **differ** from the book's current one. The derivation has two paths:

```java
derivedBookTickPxFor(ticker) -> isFractionOfParTicker(ticker) ? BOND_BOOK_TICK_PX : 0L
```

**Only bonds get a stored, price-independent category tick.** Equities return 0 and fall through to
`decadeTickPx(ref, bookTickPx)`, which steps at **$1 / $10 / $100 / $1000** — so for exactly the
securities the replay trades, the tick IS a function of the reference price. "The geometry is
already stored so it can never fire" is true of bonds and **false of every tape symbol.**

**They are quiet anyway, for two independent reasons.** Replayed flow keeps tape books occupied, so
the empty gate short-circuits; and measured against the shipping 23-symbol artifact, **no tape
symbol's real price range crosses a decade boundary** — the widest, GS at $520–$670 and COF at
$170–$210, sit entirely inside the $100–$1000 band.

**So: safe today, and not by construction.** A tape symbol whose reference crosses $100 (or $10, or
$1000) while its book is momentarily empty would move the counter, and the assertion would fail
naming a retick that was not the proof's. Re-check this list whenever `PRICE_TICKERS` widens.

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
| **`yu13-gke-replace-proof`** | `BEFORE == AFTER` **and** `$((BEFORE-1)) -eq AFTER` — **and seven more this row could not see**, see below | **FIXED** `d7144170` |

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

**~~The durable fix is a `traderx_book_operator_open_orders` twin~~ — REVERSED 2026-08-27, do not
build it.** See the resolution below. Pausing the replay remains a workaround for the reasons given
(it mutates shared rig state mid-suite, needs a trap to avoid stranding the rig feedless, and makes
the reading depend on a scale-down succeeding), which is why it ranks last of the three.
See `issues/the-adr-072-counter-countermeasure-stops-one-metric-short.md`.

---

## RESOLUTION for `yu13-gke-replace-proof.sh` (`d7144170`, 2026-08-27), and two corrections

### It was NINE assertions, not two. The counting method was the bug.

This file said two, from enumerating readers of `traderx_book_open_orders`. The header of the proof
itself said six, then seven. It is **nine**, and the progression is the lesson:

* **A search by METRIC NAME cannot see a site exposed through a different counter.** Four sites read
  `traderx_cluster_trades` and one reads `traderx_stp_cancels`; no amount of grepping the gauge finds
  them.
* **A loop is N assertions.** Two sites sit in the same `for m in 0 1 2` body, two lines apart, and
  the loop was read as one site.
* **Two compare a whole digest string and appear in NO metric-name search at all** — one across a
  member kill-and-rebuild, one asserting the digest *changed*.

> **Enumerate what a proof ASSERTS, not what it READS.** `grep -nE '^\s*\[\[|^\s*\(\('` and
> classify every hit. The same helper can be sound on one line and exposed on the next — this file's
> `trades_all()` fed both a sound cross-member `uniq_one` and an exposed per-member delta.

### The ninth was exposed in the opposite direction: it COULD NOT FAIL

`BEFORE != AFTER` on the digest — "the replace changed nothing on the members". Replayed flow
rewrites the digest at ~6/s, so it held whether or not the replace did anything. **Zero coverage that
reads as coverage**, and the only one of the nine that would never have printed a red. Deleted, not
repaired. Same shape deleted from `yu13-cancel-ingress`.

### Correction: do NOT build `traderx_book_operator_open_orders`

The recommendation above is withdrawn, and the reasons generalise to any future gauge twin:

* It is a **gauge over resting state**, not a monotonic counter, so there is no external contribution
  to subtract. A real twin needs resting orders tracked by account range — deterministic engine state
  added for an observability artifact.
* **The persistence rule forces the cost.** Resting orders are snapshotted, so the shadow must be
  snapshotted too: a `SNAPSHOT_FORMAT` bump and a mandatory fresh-epoch mint. That is what
  `externalOrderRefs`/`externalTradeLegs` cost at format 9. Per-process parents (`selfTradesPrevented`,
  the band counters) cost neither. **Check the parent before assuming either — the four are not
  uniform.**
* **Every one of its call sites wanted an identity claim anyway**, and now makes one.

### What replaced the nine

Five became **identity claims** on the order's own read-model row (status, quantity, limit price):
"depth fell by one" is satisfied by *any* order leaving; "ref Q reads CANCELED and ref B still rests
at the new price" says **which**. Two became **operator twins** read per member. Two became
`assert_order_effects`, so the operator trade delta is bracketed by the operator ref delta and is
therefore attributable. One was deleted.

The digest equality across the member kill was **deleted rather than swapped**: `digest_consensus`
already refuses to return until all three members agree, and after the rebuild that includes the
member restored from an empty disk — that *is* the convergence claim. The equality's only remaining
content was "the venue was quiet across a member rejoin", which is the one window where replayed flow
is guaranteed to be moving it.

### The reader was confirmed to ANSWER, not merely to exist

The static finding above (the route is in the operative YU17 layer, `trade-processor` is in the GKE
manifest set) is correct and was the right call to make without a cluster. Confirmed live
2026-08-27: on the **GKE bench rig** `GET /accounts/22214/orders?status=all` returns a JSON array,
and on **kind** the helpers parse real rows correctly, including the dash-anchoring that must not
read ref `4` out of order id `1-504`. The proof now **refuses to start** if that route does not
answer — a probe silently returning `""` would turn all five identity claims into greens that cannot
fail, which is the defect this whole issue is about, reintroduced at the fix.

### Measured, on `kind-traderx-yu12-cluster`, 30s, no operator activity

The admission test the library demands, and the concrete reason the nine failed accusingly:

| counter | t0 | t1 | delta |
|---|---|---|---|
| `traderx_cluster_trades` | 15278 | 15430 | **+152** |
| `traderx_cluster_operator_trades` | 5 | 5 | **0** |
| `traderx_stp_cancels` | 295 | 320 | **+25** |
| `traderx_stp_operator_cancels` | 0 | 0 | **0** |
| `traderx_cluster_next_order_ref` | 18354 | 18545 | **+191** |
| `traderx_cluster_operator_next_order_ref` | 6 | 6 | **0** |

The old `t1 -eq t0` ("a self-trade must book nothing") would have seen **+152 legs**; the old
`s1 -eq s0 + 1` would have seen **+25** STP cancels rather than 1.

### A TENTH, found by RUNNING it after all nine were fixed — and it is a different shape

The nine were fixed, the proof was run on kind under the live tape, and **step 3 went red**: the three
cross-member `uniq_one` checks that this issue, the proof's header, `lib-consensus-readings.sh` and the
person fixing it had all independently marked **sound**.

```
[FAIL] members disagree on nextOrderRef: [20850 20850 20850 ]
```

Three *identical* values, reported as a disagreement. The reader takes three **sequential** `kubectl
exec`s, one per pod, and the tape advances the counter between them — so the samples are from three
different instants. Measured at 6.13/s: `refs [21580 21586 21586]`, `trades [18006 18006 18010]`,
**8 skews in 80 samples (~90% pass rate)** — the flaky-green mode, where the red gets re-run.

> **Sound is not the same as safely measurable.** "Cross-member agreement cannot be disturbed by a
> third writer" is a statement about the CLAIM, and it is still true. It says nothing about whether
> the claim can be SAMPLED. Classifying the assertion is not enough — **ask how it samples.**

Reasoning could not have found this; only running it under foreign write pressure did. Fixed with an
`agree_on()` retry (`7ca3d5fa`); generalised into the library's SHAPE OF THE READING block by the
ADR-072 lane (`b836b194`), which adds the sharpest form of it: **the readers at risk are the ones that
hand-roll their own comparison instead of calling `_agreed()`**, which has always retried.

### Exercised: GREEN on kind under a live tape

`kind-traderx-yu12-cluster`, `traderx/cluster-node:yu17-stptwin`, tape at 6.13/s.

**Pre-fix**, same rig, first real red any of these has ever printed — at step 1, against a replace that
returned `200`, `"replaced":true` and the **same orderRef**, while the tape put +4 orders on the venue:

```
book: [757 ...] -> [761 ...]
[FAIL] depth moved: a replace must be one order in and one order out, not two orders
```

The ninth assertion — `BEFORE != AFTER`, the one exposed in the *passing* direction — passed on that
same run, for the wrong reason: the tape changed the digest. Zero coverage reading as coverage,
demonstrated rather than argued.

**Post-fix**, full pass, with the old assertion's counterfactual recorded at each site:

| step | venue reading | what the old assertion would have done | what is asserted now |
|---|---|---|---|
| 1 | depth `821 -> 821`, hash changed | passed *by luck* | ref 22089 NEW at qty 9 @ 153.0, sole open order |
| 2 | depth `825 -> 820` | **failed** — wanted exactly −1 | ref 22097 CANCELED, ref 22098 NEW at qty 4 @ 150.0 |
| 2 | — | — | operator stp `[1 1 1]->[2 2 2]`, operator trades `[5 5 5]->[5 5 5]` |
| 4 | depth `809 -> 820` across the kill | **failed** — blaming the rebuilt member | all three agree on the digest; refs 22089 and 22485 back at their replaced terms |
| 6 | — | — | tape submitted +687 over 111s = **6.19/s observed**, in band |

### Enabled is not the same as writing at pressure

The ADR-072 lane shipped a rig replaying at **1.53/s** with `enabled: true`, `error: null` and plausible
orders, because the print-sample Secret and `PRICE_TICKERS` disagreed — and nothing reported it. A
"tape is live" gate that checks `error` is empty and `submitted` climbed **passes on that rig**, because
a quarter-rate tape still climbs. The proof now gates the *reported* rate against ADR-072's 5–20/s band
at step 0 and the **observed** rate (submitted delta ÷ elapsed) at step 6; the second cannot be faked by
a config field. Nothing else in `scripts/` gates on rate — `yu17-replay-attribution:116` reads
`ordersPerSecond` only to print it.

### Exercised on GKE too — GREEN on the named tier, under a live in-band tape

`gke_traderx-505400_us-east1-b_traderx-bench`, `cluster-node:yu17-6374c110` (both twins present),
full sequence including the snapshot and the emptyDir rebuild. The coordinator built and rolled the
tier; the proof needed no changes — `CTX` and `IMAGE` are the only knobs.

**The write pressure this green ran under**, printed by the proof itself so every future run is
self-qualifying (six counters from ONE scrape, because six calls would reproduce the skew above):

```
tape wrote throughout: submitted 2436 -> 3933 (+1497) over 244s = 6.14/s observed
  traderx_cluster_trades                     1902 -> 3074    +1172
    traderx_cluster_operator_trades             1 -> 5          +4
  traderx_stp_cancels                         224 -> 360     +136
    traderx_stp_operator_cancels                0 -> 1          +1
  traderx_cluster_next_order_ref             2438 -> 3942    +1504
    traderx_cluster_operator_next_order_ref     2 -> 9          +7
```

**1,172 foreign trade legs and 1,504 foreign order refs crossed the venue while this proof asserted;
our own work was 4 legs and 7 refs.** That ratio is what the nine assertions used to be measured
against.

The counterfactual on this tier, starker than kind:

| step | venue actually read | old assertion |
|---|---|---|
| 1 | depth `204 -> 183` | **failed by 21** — demanded depth unchanged |
| 2 | depth `185 -> 183` | **failed** — demanded exactly −1 |
| 4 | depth `269 -> 300` across the kill | **failed by 31** — blaming a member that had converged |
| 5 | depth `318 -> 327` on a **rejected** replace | **failed** — blaming a replace the venue never accepted |

Step 4 is the one worth remembering: the book moved by 31 orders across a member destroy-and-rebuild
while all three members, including the one restored from an empty disk, agreed exactly on the digest.
The deleted assertion would have called that a convergence failure at the precise moment the system
was doing the hardest correct thing it does.

**One thing the GKE run established that kind could not:** `digest_consensus` — this proof's *primary*
assertion, and not one of the ten — needs its retry loop on this tier and earns it. A single
un-retried hand sample read `m0: 182 … / m1: 183 … / m2: 183 …`: the same sequential-sampling skew,
on the reading the file calls its primary claim. The existing retry absorbs it on every call, so it
was left untouched — but it is doing real work on GKE, not belt-and-braces.

### Still open for this proof

Nothing on the two tiers it targets. Remaining: it is in **no suite** — `run-proofs.sh` is kind-only
and this proof kills a member and waits on a snapshot, so an entry there would red the suite for a
structural reason. `SELFTEST=1` runs the read-model parsers offline in about a second and wants a CI
home beside the root gates, which is separate work. Until then nothing automated will red this file,
which is the condition that let nine assertions rot here in the first place.
