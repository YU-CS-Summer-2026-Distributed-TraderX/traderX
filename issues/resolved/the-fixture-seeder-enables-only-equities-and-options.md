# The fixture seeder enables only equities and options — bonds, treasuries and ETFs are still absent

**Filed 2026-08-21**, measured on `kind-traderx-yu12-cluster`/`traderx` while closing
`issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md`. Same defect class as
that one, same symptom, same reason it hides — a different set of instruments.

**RESOLVED 2026-08-22** by option 3 of the three below, in the variant that keeps the proof: the two
pinned images were given the capacity rather than rebuilt or retired. All 68 quoted instruments are
seeded on every epoch and `yu13-stp-and-replace` still passes. See "How it was resolved" at the
bottom.

## Correction to the resolved issue as originally filed

That issue said the seeder "seeds **equities and bonds** only". It seeded **equities only**.
`TICKERS` in `scripts/yu15/seed-proof-fixtures.sh` is 20 equity symbols and nothing else. Bonds have
never been in it; they are enabled because `yu16-bond-position.sh` and `yu16-treasury-pricing.sh`
each `POST /seed` their own instrument as a setup step — which is precisely the shape the resolved
issue's own principle rejects: *the tradeable set depends on which proofs happened to run*.

## Measured

On a fresh epoch on which only `seed-proof-fixtures.sh` (already carrying the option fix) and
`yu15-option-persistence.sh` had run — no bond proof:

```
BAC                Sell 1   @ 200    -> {"orderRef":13,"kind":1}                       HTTP 200
UST-20310630       Sell 100 @ 0.9966 -> {"orderRef":14, ... "UNKNOWN_SECURITY"}        HTTP 422
UST-BILL-20270812  Sell 100 @ 0.9966 -> {"orderRef":15, ... "UNKNOWN_SECURITY"}        HTTP 422
CORP-IBM-20330215  Sell 100 @ 0.9966 -> {"orderRef":16, ... "UNKNOWN_SECURITY"}        HTTP 422
```

Same hiding mechanism as the option case: `price-publisher` quotes all of them, so the instruments
look live, and `UNKNOWN_SECURITY` reads as "wrong ticker".

**Lot size masks the reason on a first attempt.** At `quantity 1` all three answer
`{"error":"Bond quantity must be at least 100."} HTTP 422` — a *different* 422, from a different
validator, which never reaches the enablement check. Probe bonds at 100+ or the enablement question
is not being asked at all.

## Why the obvious fix was NOT applied

Seeding everything `price-publisher` quotes would exhaust the symbol table on the historical builds
the suite deliberately rolls onto. Counted from `GET /prices` on 2026-08-21 — **68 quoted
instruments**: 20 equities (seeded), 24 options (seeded by the fix), 19 bonds/treasuries/strips/
corporates and 5 ETFs (`SPY QQQ IWM VTI GLD`), none of the last 24 seeded.

`MAX_SECURITIES` is 64 on the `yu15-pre` / `yu15-stp` builds that `yu13-stp-and-replace` rolls the
members onto with a fresh epoch, and `run-proofs.sh` carries a whole block about that exhaustion
(the 510-security control-feed replay hitting a 64-capacity engine: registrations refused with
`id = -1`, surfacing as a fast `422 {"seeded":false}`). The option fix took the count from 20 to 44,
still comfortably inside 64. **Seeding the remaining 24 would take it to 68 — over the limit, before
that proof mints its own tickers on top.** That is a real regression, not a hypothetical one.

## So this needs a decision, not a patch

Roughly, in increasing cost:

1. **Seed the bond/treasury/corporate class only** (19, total 63) and leave the 5 ETFs out. Fits
   under 64 by one — which is exactly the kind of margin that fails silently the day someone adds an
   instrument to `instruments.csv`. If this is taken, the seeder should assert its own count against
   the historical limit rather than discover it as a `{"seeded":false}` two proofs later.
2. **Scope the seeded set per epoch**: full universe normally, reduced set for the epoch
   `yu13-stp-and-replace` mints. Reintroduces "the tradeable set depends on which path ran", which
   is the thing being fixed, but does so *deliberately and in one place*.
3. **Retire the historical-build arm of `yu13-stp-and-replace`**, or rebuild those images with a
   larger `MAX_SECURITIES`. Removes the constraint at the cost of the proof's whole point (epoch
   continuity across a real version boundary) or of a rebuild of two pinned historical images.

## Also not run

`scripts/proofs/yu16-bond-position.sh` had a confirmed output-corrupting bug fixed in the same
commit (`sql()` exec'd `deploy/eod-price-db` without `-c mariadb`, so kubectl's `Defaulted
container` warning was folded into all twelve of its captured comparisons by its `2>&1`). That fix
is **read off the source and not exercised** — the proof has not been run since. It is a strict
improvement over a proof that could not have been comparing anything correctly, but nobody has
watched it pass.


---

# How it was resolved — 2026-08-22

Option 3, taking the "rebuild those images with a larger `MAX_SECURITIES`" branch, but **without
rebuilding them**: the capacity was grafted onto the compiled classes of the existing images, so the
version boundary the proof exists to cross is preserved by construction rather than by care.

## The images: patched in place, NOT recovered and NOT reconstructed

Dating the pair against commit history does not work — their build times put the only commit between
them at `6b452f98` ("YU15: gs:// delivery proven live"), which has nothing to do with STP, so they
were almost certainly built from a working tree carrying uncommitted changes. That question was
sidestepped entirely: nothing was rebuilt from any tree.

`MAX_SECURITIES` is a `static final int`, so javac inlined it at every use site. In
`MatchingEngineClusteredService.class` there are **7 `bipush 64` sites, of which 5 are
`MAX_SECURITIES`**:

| method | site | patched |
|---|---|---|
| `<init>` | `new String[MAX_SECURITIES]` (`tickerById`) | yes |
| `initEngine` | 1st arg to `BlpRiskState` — **`MAX_ACCOUNTS`, also 64** | **no** |
| `initEngine` | 2nd arg to `BlpRiskState` — `MAX_SECURITIES` | yes |
| `initEngine` | 1st arg to the `MatchingEngine` ctor | yes |
| `onSymbolRegister` | `nextSymbolId >= MAX_SECURITIES` (the capacity refusal) | yes |
| `onSnapshotRecord` | `id >= MAX_SECURITIES` (the restore bound) | yes |
| `drainOutputs` | `OutputEvent.flags & 0x40` — **a flag mask, not a cap** | **no** |

The two exclusions are what a naive "replace every 64" would have got wrong: `MAX_ACCOUNTS` would
have been silently widened, and the flag mask would have corrupted output-event handling.

Patched with ASM (`asm-9.8`, out of the gradle distribution) plus the `ConstantValue` attribute, so
`javap -constants` reports the build honestly. `bipush` and `ldc` have identical stack effect, so
max-stack/locals and every frame are unchanged; only instruction offsets move, which the tree API
recomputes. Both patched classes pass full JVM link + verify against the image's own `/opt/app/lib`.

**Tags.** The grafted images are `traderx/cluster-node:yu15-pre-1k` and `:yu15-stp-1k`; the
64-capacity originals are kept as `:yu15-pre-orig64` / `:yu15-stp-orig64`. The bare `:yu15-pre` /
`:yu15-stp` tags were deliberately NOT overwritten — a tag that quietly means something new is the
trap this project has already paid for once. `IMAGE_PRE`/`IMAGE_FIX` in
`scripts/proofs/yu13-stp-and-replace.sh` and `STP_IMAGE_PRE` in `scripts/yu15/run-proofs.sh` now
name the `-1k` pair.

**The version boundary is provably intact.** The same **15 classes** differ between `pre-1k` and
`stp-1k` as between the two originals — the identical transform was applied to both sides, so the
delta between them is untouched. Statically: `RiskReason` has no `SELF_TRADE_PREVENTED` in `pre-1k`
and has it in `stp-1k`; the gateway's `/replace` route string is absent in `pre-1k` and present in
`stp-1k`.

## `SNAPSHOT_FORMAT` was deliberately NOT bumped

The bump (`ccb7aabc`) exists to make a **1024-writer → 64-reader** snapshot handoff fail legibly at
the header instead of deep in record parsing. That hazard's precondition is gone here:
`yu13-stp-and-replace` is the only proof that rolls the **StatefulSet**, and it now rolls onto
1024-capacity builds exclusively before returning to a tip that is 1024 with `MIN_READABLE 3`. The
other 64-capacity images in the tree do not participate: `:yu15-cancel` is rolled onto the
**gateway** only by `yu13-cancel-ingress.sh` (which never touches the StatefulSet) and the gateway
holds no symbol table; `:yu12`/`:yu14` belong to their own states' bring-up scripts.

Two further reasons not to bump. These builds carry **no `MIN_READABLE_SNAPSHOT_FORMAT` field at
all** (`javap` — it postdates them), so their reader is a strict equality check; moving them to 4
would make a currently-tolerant build strict for no gain. And the snapshot writes symbol ids as
`getInt`/`putInt` already, so widening the domain changes no record layout.

**What would break if the format had NOT been bumped back when it mattered** — i.e. why the bump was
right in `ccb7aabc` and is merely unnecessary here — is recorded in the source comment: a 64-build
handed a widened snapshot fails with "snapshot corrupt: symbol id 64", a false accusation, and the
incompatibility is **data-dependent** (below 64 registered securities it restores perfectly). The
residual form of that hazard is now filed separately as
`issues/open/the-retired-64-capacity-images-can-still-be-rolled-onto-a-widened-epoch.md`.

## The seeder now seeds the feed, not a list

`scripts/yu15/seed-proof-fixtures.sh`'s option block was widened from listed options to **every
quoted instrument `TICKERS` does not name** — one loop covering options, ETFs, treasuries, bills,
strips and corporates. Seeding class by class was reproducing the same defect one class further
along, which is exactly what this issue reported.

Two things came with it:

* **Full precision instead of 2dp.** The old block rounded to cents, which is right for an option
  premium and destroys a bond: `UST-BILL-20260910` quotes `0.9968` and rounds to `1.0`,
  `UST-STRIP-20560515` quotes `0.21969` and rounds to `0.22`. `POST /seed` sends a `PRICE_TICK` at
  the price passed and that becomes the risk anchor, so the old rounding would have anchored all 19
  debt instruments wrong the moment they were seeded. **This was a latent bug in the option fix,
  not a new one** — it simply had no bond to damage yet.
* **A class census and a capacity assertion.** Seeding whatever the feed quotes removes the
  hardcoded per-class lists, and with them the per-class guard those lists gave for free — so the
  extractor now fails if any of `option`/`bond`/`etf` is absent from `/prices`, and the script
  asserts its own total against `MAX_SECURITIES` rather than discovering exhaustion as a
  `{"seeded":false}` two proofs later. That is the assertion this issue asked for, against 1024
  rather than 64.

## Measured after the fix — a differential, on `kind-traderx-yu12-cluster`/`traderx`

The capacity claim is established by running the **same seeder, same feed, same rig, same
procedure** against the unmodified original and the grafted image, with members AND gateway both on
the build under test:

| members + gateway | result |
|---|---|
| `traderx/cluster-node:yu15-pre-orig64` (unmodified) | refused at the **65th** security — `[fail] seed MSFT261218C00390000: {"seeded":false}` |
| `traderx/cluster-node:yu15-pre-1k` (grafted) | **all 68** enabled, then 10 further test registrations (`ZZCAP69`..`ZZCAP78`) all `{"seeded":true}` |

That is the whole claim, measured rather than inferred: the 64-capacity build stops exactly where
the arithmetic in this issue said it would, and the grafted build does not.

Seeder output on the grafted pair:

```
   22214 .. 44044    seed HTTP 200          (all seven accounts)
   feed census: {'option': 24, 'bond': 19, 'etf': 5}
   48 instruments enabled at their live prices (68 securities this epoch)
```

On the current tip build (`:yu17-jsrebind`), the same 68, and every class takes an order — the
direct answer to this issue's opening measurement, where the first three returned
`UNKNOWN_SECURITY`:

```
UST-20310630        Sell 100 @ 0.99656  -> {"orderRef":7,"kind":1}   HTTP 200
UST-BILL-20270812   Sell 100 @ 0.9594   -> {"orderRef":8,"kind":1}   HTTP 200
UST-STRIP-20560515  Sell 100 @ 0.21969  -> {"orderRef":9,"kind":1}   HTTP 200
CORP-IBM-20330215   Sell 100 @ 0.9578   -> {"orderRef":10,"kind":1}  HTTP 200
SPY QQQ IWM VTI GLD Sell  10            -> kind:1                    HTTP 200 (all five)

UST-20310630        Sell   1 @ 0.99656  -> {"error":"Bond quantity must be at least 100."} HTTP 422
```

The last line is deliberate: the lot-size validator still answers first below 100, so the probe
methodology this issue documented is unchanged and the fix did not disturb that validator.

`scripts/proofs/yu13-stp-and-replace.sh` passes on the grafted pair, with its falsification arm
intact — step 2 books the wash trade `[0 0 0] -> [2 2 2]` on `pre-1k`, step 5 books nothing on
`stp-1k`, and step 6 confirms the identical economics from two accounts still fill `[2 2 2] ->
[4 4 4]`. Three suite runs, all `1 passed, 0 skipped, 0 failed`.

**Note what a yu15-era engine does with a bond ORDER**: it registers the symbol (that is capacity,
and it is what `MAX_SECURITIES` gates) but rejects the order with a bare `{"kind":2}`, because the
YU16 bond class postdates the build entirely. Enablement and tradability are different questions and
only the first one was ever blocked by the cap.

## What was NOT proven, and one thing this uncovered

* **The suite's own stp-prep does not seed the historical epoch at all**, and never has. Filed as
  `issues/open/the-stp-prep-seeds-through-a-tip-gateway-onto-historical-members.md`. It is unrelated
  to this fix — it was simply invisible until the seeder's exit status stopped being discarded — but
  it means the "68 instruments on a historical build" result above comes from a hand-run controlled
  measurement, NOT from the suite. The suite's stp arm still runs against an epoch holding only its
  own minted ticker.
* `scripts/proofs/yu16-bond-position.sh`'s `sql()` fix is still read off the source and unexercised;
  carried over from the "Also not run" section above and untouched by this work.
* Nothing was propagated to other branches — `specs/` was not modified (`MAX_SECURITIES` is already
  1024 on the tip and the ancestors' 64s are correct for those states), and image/script changes on
  descendants are the coordinator's to schedule.
