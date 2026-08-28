# Five GKE proofs read a global counter that ADR-072's replayed flow now moves

**Filed 2026-08-26** on YU17, at the coordinator's request, alongside the ADR-072 implementation.
**ALL FIVE ARE NOW FIXED** — `yu13-gke-replace-proof.sh` first (`d7144170`, 2026-08-27), then the
other four the same day; see the two resolutions at the bottom, the first of which also corrects two
things this file got wrong about the fifth. **The file stays OPEN on one residual**: the DR proof
`yu12-gke-restore-from-gcs` is landed but **cannot be exercised on any tier we have**, because the
`yu12-snapshot-backup` CronJob is not deployed on the bench cluster. None of the five is in any
suite, so nothing automated will red them — which is the condition that let twenty-eight defects
accumulate across them (eleven in the fifth, seventeen in the other four). **The seventeenth is a
shape none of the first sixteen were**: every assertion in `yu12-gke-cross-epoch-idreuse` was sound,
and the CLAIM ITS NAME AND BANNER MADE was not a property of the system — see the last section.

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

---

## RESOLUTION for the remaining FOUR (2026-08-27) — and two shapes the fifth did not have

`yu12-gke-recovery`, `yu12-gke-failover-transparency`, `yu12-gke-cross-epoch-idreuse` and
`yu12-gke-restore-from-gcs`. **Fifteen exposed assertions, one claim asserted nowhere, and four
missing DESTRUCTIVE gates — sixteen findings.** Three are GREEN on the GKE bench under a live tape, run twice against
one epoch; the DR proof is landed and **cannot be exercised on any tier we have** — see the residual.

| proof | exposed | shape |
|---|---|---|
| `yu12-gke-recovery` | 5 | 1 guaranteed-red flagship, 1 accusatory delta, 3 that could not fail |
| `yu12-gke-restore-from-gcs` | 6 | 1 **unmeasurable** flagship, 1 guaranteed-red, 1 dead else-arm, 3 others |
| `yu12-gke-failover-transparency` | 2 + 1 absent | 1 **falsified premise**, 1 accusatory verdict, 1 claim in a step title the code never asserted |
| `yu12-gke-cross-epoch-idreuse` | 2 | its central claim was ALREADY an identity claim and was never exposed |

### The counting method again, because it changed the answer again

Enumerating by **assertion** (`grep -nE '^\s*\[\[|^\s*\(\('`) rather than by metric read found sites
no metric sweep reaches: `R1 > R0 && R0 >= PRE_REF` is **two** assertions on one line; the
`for r in ${NEW_REFS}` loop is **N**; and `yu12-gke-failover-transparency`'s step 3 asserted
**nothing at all** while its title claimed "no member bounced except the leader this proof killed" —
the `yu13-otel-trace-join` shape, found only by reading the step against its code.

### THE TWELFTH: a falsified PREMISE, not a contaminated reading

`yu12-gke-failover-transparency` step 0 sampled `next_order_ref` and `book_open_orders`, slept 5s,
and required both **unchanged** — the quiet-cluster guard the whole `booked == acked` verdict rested
on. Since ADR-072 that window moves the ref counter by ~30 on the GKE bench. **It fails on every run,
on a correct cluster.**

This is not the sibling's class. There is no better counter to retreat to and no retry to add: the
guard was a **true statement about a property the system no longer has**. Widening it deletes it, and
what it guards is the verdict.

> **Ask whether the proof's PRECONDITION still holds, not only whether its readings are clean.** A
> contaminated reading can be re-pointed. A falsified premise means the METHODOLOGY has to change.

Fixed by scoping the premise rather than the tolerance: the venue is never quiet again, but **our
slice of it is**, and that is what the equality always needed. The guard now reads the operator
twins, which replayed flow cannot move by construction.

### THE THIRTEENTH: sound, and NOT SAMPLEABLE AT ALL

The sibling's tenth was a sound claim that could not be sampled *coherently* — three sequential
execs, fixed by a retry. `yu12-gke-restore-from-gcs` step 4 is the same family one step further on:

    [[ "${R}" == "${S}" ]]      # "the restored state is EXACTLY the backup point"

The restored cluster **resumes taking replayed order flow the instant it is up**, so the venue-wide
state diverges from the backup point monotonically from the first second. There is no window in
which the equality holds, and **waiting makes it strictly worse**. A retry converges on the tenth;
here it cannot converge at all. Its else-branch then reports *"restored state matches neither the
backup nor the pre-wipe state — restore is corrupt"*: a green DR path reported as data loss, which is
the most damaging false accusation in this whole issue.

> Retry fixes a claim you can sample at the wrong INSTANT. It does nothing for a claim whose subject
> is moving away from you. Ask which one you have before reaching for `agree_on`.

### The repair that is NOT in the library's list of three: SCOPE the equality, do not delete it

The sibling deleted its equality across a member kill, correctly — its content was "the venue was
quiet across a rejoin", which is not a claim worth having. **Here the equality IS the proof.**
"Nothing changed across the rebuild" and "the restore came back at the backup point" are what these
two files exist to establish, and deleting them would have left the proofs claiming less than their
banners say.

They survive because the operator twins are **snapshotted** — so they are carried through a rebuild
and restored *from the tarball* — **and** tape-proof. So the equality holds, scoped to state the
proof owns, across the entire destructive sequence:

* `yu12-gke-recovery` now asserts operator refs and trade legs **unchanged across the follower
  rebuild AND every leader election in step 4** — a wider window than the original covered.
* `yu12-gke-restore-from-gcs` uses them as the **restore-vs-survive discriminator**: back at the
  captured `S` means restored from the tarball; back at `S+` means the cluster survived and the DR
  path was never exercised. That is exactly what the venue-wide hash was for, on a reading that works.

**This only works because those two twins are snapshotted.** `traderx_stp_operator_cancels` and
`traderx_band_operator_*` are per-process (plain fields, in neither the snapshot writer nor the
reader — `MatchingEngine:139,161`, and the `PER-PROCESS` javadoc at `:1549,:1556`). A cross-member
absolute on those is a statement about **uptime**, unsatisfiable on every epoch these four proofs
produce. **Neither is used in any of the four.** Verified in the writer/reader, not taken on trust.

### The twins fix SAMPLING too, which is why they beat a retry here

Measured on the GKE bench, 2026-08-27, tape at 6.13/s, 20 unretried three-member samples each:

| reading | coherent samples |
|---|---|
| four-quantity `identity_consensus` (order hash, position hash, trades, refs) | **5 / 20** |
| two-field book digest | **5 / 20** |
| the operator twins (`operator_next_order_ref`, `operator_trades`) | **20 / 20** |

A counter that does not move **cannot be sampled incoherently**. So swapping to a twin removes
contamination and skew together, where `agree_on()` removes only the second. The corollary matters
for the readings that must stay global: `identity_consensus`'s retry loop is **load-bearing on this
tier at 75% of samples skewed** — it is spending those tries, not holding them in reserve. It was
kept at full budget in both proofs that use it. **Do not trim it as excessive.**

### Exercised: GREEN on the GKE bench, twice each against one epoch

`gke_traderx-505400_us-east1-b_traderx-bench`, `cluster-node:yu17-6374c110`, all five twins present,
images uniform. Every run gates the REPORTED rate at step 0 and the OBSERVED rate (submitted delta ÷
elapsed) at the end, so a quiet or quarter-rate tape cannot produce a green.

**The write pressure each green ran under — printed by the proofs themselves, so every future run is
self-qualifying:**

| proof | observed tape | foreign trade legs | foreign order refs | OUR legs | OUR refs |
|---|---|---|---|---|---|
| `cross-epoch-idreuse` run 1 | 6.23/s | **+352** | **+488** | 2 | 21 |
| `cross-epoch-idreuse` run 2 | 6.20/s | **+478** | **+604** | 2 | 21 |
| `failover-transparency` run 1 | 6.24/s | **+576** | **+1248** | 0 | 567 |
| `failover-transparency` run 2 | 6.23/s | **+710** | **+1295** | 0 | 498 |
| `recovery` run 1 | 6.16/s | **+1262** | **+1470** | 4 | 4 |
| `recovery` run 2 | 6.17/s | **+862** | **+1022** | 4 | 4 |

Every one of these ran **twice against a single epoch**, because idempotence across runs is a
separate property from correctness within one — and it is what exposed the fifth proof's eleventh
defect (a proof whose own step 4 restarted the member its step 3 asserted about, so the second run
red and blamed the cluster for its own damage). Nothing of that shape survives here: every reading
is either a delta, an equality scoped to snapshotted state, or an identity claim on a freshly minted
ticker, and none of them carries state between runs.

**The counterfactuals, measured rather than argued:**

| proof | what the venue actually did | what the old assertion would have done |
|---|---|---|
| `failover-transparency` run 1 | depth `1481 -> 2051`, **+570 net for 567 orders of ours** | **failed: "3 DUPLICATED"** — against a failover that duplicated nothing |
| `failover-transparency` run 2 | depth `1940 -> 2392`, **+452 net for 498 orders of ours** | **failed: "46 LOST"** — against a failover that lost nothing |
| `recovery` step 3 | agreed refs `77674 -> 78006` across the follower rebuild (**+332**) | **failed by 332** — "the cluster's agreed state changed across a follower rebuild" |
| `recovery` step 5 | `traderx_cluster_trades` +1262 across the run | `T1 == T0+2` **failed**, blaming the rebuilt leader |
| `cross-epoch` step 3 | tape advances the global ref counter ~6/s | `R_NEW > R_OLD` **passed for the wrong reason**, as it always had |

`failover-transparency`'s is the one worth remembering, and the two runs together are the argument:
the same assertion on the same correct venue would have said **"3 DUPLICATED"** on the first run and
**"46 LOST"** on the second. **The contamination is not even a consistent bias** — the venue-wide
depth delta lands either side of the true count depending on what the tape happened to rest and fill
in the window, so the failure text accuses the gateway of opposite defects on consecutive runs. The
identity claim that replaced it names all 567 (then 498) refs individually and cannot do either.

### What replaced the sixteen

* **Identity claims (the read model).** `failover-transparency`'s `booked == acked` became **set
  equality between the orderRefs the clients were ACKED for and the refs RESTING on a minted
  ticker** — a lost order is an acked ref not resting, a duplicate is a resting ref nobody was acked
  for, and both are named individually instead of summed into a number the tape contributes to.
* **Scoped equalities on the snapshotted twins** — the two flagship claims above.
* **`assert_order_effects`** (4 sites) where the claim is about volume and must be attributable.
* **Operator anti-vacuity guards** replacing three that the tape satisfied unconditionally.
* **One assertion added** where a step title had been claiming something the code never checked:
  exactly one member is a new pod, read on **pod UIDs captured before the kill** — a deleted-and-
  recreated pod has `restartCount` 0, identical to one that never bounced, so the old printout could
  not have carried the claim even if it had been asserted.

### Not the read model, for the DR proof — and the reason generalises

Fix #1 is "ask the order", and it is the **wrong instrument** for `restore-from-gcs`:
trade-processor's database is **not restored with the cluster**, so after a restore it still holds
the post-backup `S+` orders as open. An identity claim read from it would report `S+` present and
call a correct restore a failure — the same false accusation, reached from the opposite direction.

> **The read model is the effect end for anything that does not wipe the cluster.** When the scenario
> restores replicated state, only readings that move WITH the restore can carry the claim.

### Gates: all four now refuse by default

All four performed pod deletes, scale-to-zero or PVC-wiping restores with `DESTRUCTIVE` mention
count **0**. Each now requires `DESTRUCTIVE=1` and otherwise prints what did not run and exits 2
without touching the cluster, per `yu17-halt-survives-failover`. **They refuse rather than running a
reduced subset**: every one prints a PASS banner claiming survival across the destructive event, and
a run that skipped it has not shown that.

### New shared library, because a private reimplementation is what the sweeps kept missing

`scripts/proofs/lib-gke-replay-gates.sh` — the destructive gate, the uniform-image divergence rule,
the tape-live + rate-band gate, the observed-rate assertion and the six-counter write-pressure table
(**one scrape**, so the six readings are mutually coherent). `SELFTEST=1` on any of the four
exercises the rate-band and pressure arithmetic offline in about a second, including that an
**unreadable** rate is refused rather than read as 0.

`lib-consensus-readings.sh` gained the read-model identity readers (`tp_orders`, `order_row`,
`open_refs_on`, `require_read_model`, `await_open_set`, `assert_row_terms`), with nine parser shapes
covered in the library selftest — dash-anchoring (`ref 4` must not match order id `1-504`), numeric
rather than lexicographic ref sort, and empty/non-JSON parsing as "cannot read" rather than "absent".
`yu13-gke-replace-proof` carries private equivalents that predate these; collapsing them is a tidy-up,
not a defect.

### STILL OPEN

* **`yu12-gke-restore-from-gcs` is LANDED BUT NOT EXERCISED, and cannot be on any tier we have.**
  The `yu12-snapshot-backup` CronJob **does not exist** on the yu17 bench cluster (checked
  2026-08-27), and the DR path also needs the GCS HMAC secret and bucket from the 2026-07-19 drill.
  The proof now refuses at step 0 naming this as a **missing prerequisite, not a restore failure**.
  Standing up that CronJob on a shared rig was not in scope and was not done.
* **Whether `price-publisher` re-establishes its cluster session after a full-cluster restore is
  UNVERIFIED.** A restore is a new epoch and every client must reconnect; the publisher is a client.
  The proof therefore gates the tape rate across the **pre-destroy** window — where every rewritten
  assertion is measured — and only reports it afterwards. Gating the post-restore rate would turn
  this open question into a red about DR, which is the failure mode this file exists to remove.
* **None of the four is in any suite.** `run-proofs.sh` is kind-only and all four kill members, so an
  entry there would red the suite structurally. Nothing automated will red these files, which is the
  condition that let sixteen assertions rot in them.
* **Ten kind-tier proofs perform pod deletes or scale-downs with no `DESTRUCTIVE` gate**
  (`yu13-readmodel-effect-end`, `yu13-stp-and-replace`, `yu16-book-grid`, `yu17-fx-credit`,
  `yu17-swap-netting`, `yu04-offline-catchup`, `yu15-risk-extract`, `yu17-keyed-ack-correlation`,
  `failover-nodeclock`). On the kind rig that is the expected mode and this is an **observation, not
  a filed defect** — but the shared-rig argument that earned these four a gate applies there too.

---

## A SEVENTEENTH, found after the sixteen were fixed and green: the proof's NAME was the defect

Both sibling lanes replied after the first commit. The ADR-072 lane's persistence table matched what
had already been derived independently from the writer and reader — but one of its points did not
apply to `yu12-gke-cross-epoch-idreuse` as written, and chasing *why* it did not apply is what found
the seventeenth.

**The point raised:** a fresh epoch wipes the PVCs, `nextOrderRef` initialises to 1
(`MatchingEngineClusteredService:502`, `:716`), so **no counter comparison can span a mint — twin or
global**, and a twin cannot fix it.

**Why it did not apply:** this proof kills a **leader**. Measured across four runs, the operator ref
counter went `9 -> 19 -> 29 -> 30` and then `30 -> 40 -> 50 -> 51`, monotonic, no reset. So the
assertions were correct.

**And that is exactly the problem.** The proof is called *cross-epoch id reuse* and its header
claimed the generator "NEVER reissues an order id from a prior epoch". **That is not a property of
this system.** `OrderNatsPublisher:20`, verbatim:

> "`orderRef` restarts at 1 on a fresh cluster incarnation, so a table keyed on the bare ref collides
> across epochs — partially, silently. The read-model key is therefore `epoch + "-" + orderRef` …
> **stable across FAILOVER** (orderRef does not reset on failover), and **bumped together with wiping
> the DB on a fresh incarnation** — they are one artifact."

and `MatchingEngineClusteredService:1278` is blunt: **"Nothing here makes ids unique ACROSS a wiped
epoch."**

So one word was doing two jobs:

| "epoch" as used by... | ...means | and so |
|---|---|---|
| **the proof's code** | a LEADERSHIP TERM — a leader kill | `nextOrderRef` is snapshotted, the restarted pod reads the same `CLUSTER_EPOCH`, refs continue. **Real, and worth proving**: it is the YU11→YU12 snapshot fix. |
| **the proof's NAME and banner** | a WIPED INCARNATION — a fresh mint | refs **restart at 1 and DO collide**. The proof never performs one and shows nothing whatever about it. |

The hazard the file's own preamble cites — trade-processor dedup eating real trades on 2026-07-22 —
arises at a **mint**, which this proof does not do. What actually prevents it is the epoch qualifier
on the read-model id, which no proof was checking.

> **The eleventh's question, asked of the proof rather than of an assertion.** Every assertion here
> was sound; the CLAIM WRAPPED AROUND THEM was not. A file can be entirely correct line by line and
> still establish something other than what its name, banner and header say — and a reader cites the
> banner, not the lines. **Ask what the proof establishes, not only whether its readings are clean.**
> This was invisible until the contamination was fixed, exactly as the fifth proof's tenth and
> eleventh were: three passes, three different questions.

**What changed.** The assertions were kept — they are correct for a failover. The header, the step
titles and the PASS banner now say *failover*, and the banner explicitly names what is **not** shown.
And the half that IS testable here, and never was, is now asserted: **the epoch qualifier is
unchanged across the kill**, which is precisely `OrderNatsPublisher`'s "stable across failover". If
it had moved, the ref continuity above it would prove far less than it appears to — the two halves
would sit in different keyspaces and could not have collided whatever the generator did.

### A REAL GAP, now named rather than papered over

**No proof anywhere covers id separation across a wiped incarnation.** The mechanism is
`CLUSTER_EPOCH` in the read-model key; it is off-consensus, set from the manifest, and "bumped
together with wiping the DB" by convention rather than by construction — `OrderNatsPublisher` calls
them "one artifact", which is a statement about operator discipline, not an invariant the system
enforces. **A mint that bumps the epoch and does NOT wipe the DB, or wipes the DB and does NOT bump
the epoch, silently reintroduces the 2026-07-22 collision**, and nothing would red. That is worth its
own proof and its own issue; it is not in scope here and was not attempted, because minting an epoch
on the shared bench rig is exactly the destruction this work spent the day gating.

### Re-exercised after the correction

`yu12-gke-cross-epoch-idreuse` was re-run **twice against one epoch** on the GKE bench with the new
qualifier assertion and the `_agreed` reshape in place — green both times, tape observed at 6.21/s
and 6.25/s, and the qualifier read `1` and **unchanged across the kill** on both. The banner now ends
by naming what it does not show, so a future reader cannot cite the run as cross-epoch coverage.

`yu12-gke-failover-transparency` was re-run once after its `_agreed` reshape — green, 529 acked
orderRefs all resting on the minted ticker and nothing else, tape observed at 6.22/s.

A fix is a probe, and this one had to be exercised for a specific reason: the new assertion reads the
read model, so a proof that previously needed no reader now refuses without one. `require_read_model`
was added to its step 0 for exactly that — a route answering `[]` for every account is
indistinguishable from "not visible yet" until a timeout, and would then be reported as a verdict
about id reuse rather than as the probe failing.

**The STP arm in the write-pressure table was checked rather than assumed**, after the ADR-072 lane
cautioned that `traderx_stp_operator_cancels` reading `+0` demonstrates attribution only if the
GLOBAL was moving in the same window — otherwise the arm is quiet rather than passing. Across every
run recorded here `traderx_stp_cancels` climbed by **+40 to +206** while the twin held at `1`. The arm
was never quiet, so the demonstration stands.

### The fourth question, turned on this work's OWN four files

The replace-proof lane applied the seventeenth's question to its own proof and found two overclaims
in its banner (`8cdfe465`). Running the same audit here: **no banner among these four stated anything
false — but three of them named no limits at all**, and a reader cites the banner. All four now end
by saying what the run does NOT establish. Three limits were surfaced that were nowhere in the files:

* **No claim under concurrent OPERATOR load, in any of the four.** Every volume claim brackets an
  operator counter, and every scoped equality rests on one — a SECOND operator writing during the run
  moves exactly those. `failover-transparency` and `restore-from-gcs` check quiescence and
  `yu12-gke-recovery` does not even do that; and a check at step 0 covers **a moment, not a run**. This
  is a real limit of the repair, not of the old assertions, and it arrived with the fix.
* **`failover-transparency`'s identity claim is scoped to one ACCOUNT.** An order double-booked under
  a different account is invisible to `open_refs_on` and would not be caught. The claim is "none of
  *this account's* acked orders were lost or duplicated", which is narrower than the banner implied.
* **`restore-from-gcs` says nothing about the read model after DR** — and cannot, since
  trade-processor's database is not restored with the cluster. So a passing run leaves the engine and
  the read model **knowingly divergent**, the engine having dropped exactly the S+ orders the read
  model still shows open. That is a genuine gap in the DR story rather than an oversight in the proof,
  and it is now stated where someone citing the [PASS] will see it.

> The audit is cheap and the failure it prevents is not: a wrong assertion reds and gets
> investigated; **a wrong banner gets quoted.**

### …and then the limit got a DETECTOR, which is better than a caveat

Naming "no claim under concurrent operator load" and leaving the reader nothing to do about it is
honest and not much use. The replace-proof lane's answer (`a5abe888`) was that **the detecting number
was already being printed and merely unlabelled**: the write-pressure table carries
`traderx_cluster_operator_next_order_ref`, and a proof knows exactly how many orders it submits, so
the row can be checked against that count **across the whole run** — which is precisely what a step-0
quiescence check cannot do.

Adopted here as `operator_expectation` in `lib-gke-replay-gates.sh`. Counted from the call sites, not
recalled, and matched on every run already recorded:

| proof | expected | measured |
|---|---|---|
| `cross-epoch-idreuse` | `2 x N_PER_EPOCH + 1` = **21** | +21 on all five runs |
| `recovery` | 3 in step 1 + 1 in step 5 = **4** | +4 on both runs |
| `failover-transparency` | **floor** of `ACKED` (time-bounded stream; a retry burns a ref) | +567 / +498 / +529 against exactly that many acks |

**Deliberately a reading, not an assertion**, and the reasoning is the library's own: operator
counters are global over order *writers*, so the algo engine or another lane's proof would red a
correct run on a shared rig. That is a flake, and **a flake gets re-run until it passes — the exact
failure mode this whole file exists to remove.** A number a human checks beats an assertion that
erodes. The `assert_order_effects` windows still assert their own tight brackets; this covers the
gaps between them.

Verified on the GKE bench: `operator refs +21 == the 21 order(s) this proof submitted: no OTHER
operator wrote at any point during this run`. Four selftest arms cover the exact, mismatch and both
floor outcomes — a detector that cannot tell them apart is worse than none, because it reads as one.

**A free corroboration fell out of the count**, the same way one did for the sibling: `failover-
transparency` measured `+567` refs for 567 acks *with 1 retry reported*. If every retry burned a ref
the count would be 568 — so that retried send never reached the engine at all. The floor was the
right shape and an equality would have been wrong in the other direction.

## Two changes taken from the sibling lanes' replies

* **The hand-rolled retries are gone.** `yu12-gke-cross-epoch-idreuse` and
  `yu12-gke-failover-transparency` each carried a private retry loop around a space-joined triple.
  The readers at risk from the sampling defect are *exactly* the ones that hand-roll their comparison
  instead of calling `_agreed`, which has always retried — so both are now written to `_agreed`'s
  per-ordinal signature. That also buys the fast-fail a private loop cannot have: three members
  answering `-1` is reported immediately as **"the metric is absent, this build predates the ADR-072
  operator counters"** rather than burning two minutes on a disagreement that is not happening.
  `identity_consensus` stays separate and must: `_agreed` requires `^[0-9]+$` and the order hash is
  routinely negative.
* **`EXPECT_CTX` on the irreversible proof.** `DESTRUCTIVE=1` records that the operator accepted
  destroying *a* cluster, not **this** one, and `CTX` is a default that rots — these files shipped for
  weeks pointing at `traderx-501015`, a project deleted 2026-08-01, and a wrong-context `kubectl`
  answers truthfully about the wrong cluster. A member rebuild is recoverable; a scale-to-zero is
  not, so `yu12-gke-restore-from-gcs` now refuses unless the operator NAMES the target context. That
  converts "I forgot to set CTX" from an outage into a refusal.
