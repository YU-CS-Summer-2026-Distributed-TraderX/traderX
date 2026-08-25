# `yu17-book-retick`'s two tickPx assertions: one could never pass, the other could never fail

**Filed and resolved 2026-08-25**, during the format-8 mint chip (chip 4), by the on-rig detonator
run that the proof's own header demands. Fourth member of the class this four-chip sequence keeps
turning up — after
[`the-band-follows-market-guard-asserts-absolute-counters-and-cannot-fail`](the-band-follows-market-guard-asserts-absolute-counters-and-cannot-fail.md),
[`stillness-assertions-on-the-global-applied-sequence-race-the-live-feed`](stillness-assertions-on-the-global-applied-sequence-race-the-live-feed.md)
and [`the-suite-gate-cannot-tell-reduced-from-complete`](the-suite-gate-cannot-tell-reduced-from-complete.md).

It matters because `yu17-book-retick` is **the headline proof of the price-derived grid** — design
§5 row 4, and the one proof gate V4 detonates. Both of its grid readings were broken, in opposite
directions, and between them they meant the proof could not have reported the truth about the
mechanism on either arm.

## Defect 1 — the step-2 assertion could never pass

`scripts/proofs/yu17-book-retick.sh`, `EXPECT=after` arm:

```bash
[[ "${ROW}" == *'"tickPx":1000'* ]] || fail "no tickPx:1000 on the /bbo row — ..."
```

`ROW` comes from `bbo_json()`, which builds it with `json.dumps(b)` — **default separators**, which
emit `": "` with a space. The row the minted build actually produces is:

```
{"ticker": "RTK111746", "bid": 100.0, "tickPx": 1000}
```

`"tickPx":1000` (no space) is not a substring of that. The pattern could not match any output the
function is capable of producing, so the after arm was **unsatisfiable**.

It hid perfectly. Chip 1 banked this proof's red half against `:yu17-markwait2`, where the row
genuinely carried no `tickPx` at all — so the red was real, correctly attributed, and would have
looked *identical* on the minted build. The proof would have gone red at the mint saying "the
tickPx surface ships with the mint" about a build that ships it.

## Defect 2 — the step-6 assertion could never fail

```bash
[[ "${ROW}" == *'"tickPx":10'* ]] || fail "after the re-derivation /bbo must show tickPx:10; ..."
```

`"tickPx":10` is a **prefix of** `"tickPx":1000`. A book whose grid never re-derived — which is
exactly the failure this assertion exists to catch — still satisfies it. Demonstrated:

```bash
STUCK='{"ticker":"X","tickPx":1000}'      # re-derivation did NOT happen
[[ "${STUCK}" == *'"tickPx":10'* ]]       # true
```

(Defect 2 is masked by defect 1 in the same run — step 2 aborts first — but it is independent, and
it survives any fix to defect 1 that keeps substring matching.)

## What changed

Both now read the **value** off the row and compare it exactly, via the `field()` helper the script
already had:

```bash
[[ "$(field "${ROW}" tickPx)" == "1000" ]] || fail ...   # step 2
[[ "$(field "${ROW}" tickPx)" == "10"   ]] || fail ...   # step 6
```

No substring can prefix-match a parsed integer, and no serializer's spacing can defeat it. Both
sites carry the account above in place, so neither is "tidied" back to a substring.

## Verified

On the deployed detonator (`traderx/cluster-node:yu17-format8-detonator` — today's tree minus the
single line `rederiveIfEmpty(book, e.securityId);` in `onNewOrder`, per design §7b V4), fresh epoch,
`kind-traderx-yu12-cluster`:

- **Before the fix**, the run died at step 2 on defect 1 — with `"tickPx": 1000` *present* in the
  row it printed. A red that named the wrong thing.
- **After the fix**, step 2 passes (the `tickPx` surface is not what the detonator removes) and the
  run dies where it must:

```
--- 2. BUY @100.00 on the never-ticked book
[ok] rests on the provisional grid, visibly: {"ticker": "RTK111936", "bid": 100.0, "tickPx": 1000}
--- 5. SELL @22.00 (~20x the reference) — the decade-crossing probe
    -> kind=1 reason=<none>
[FAIL] format-8 build must REFUSE the 20x probe after re-deriving; got kind=1 {"orderRef":21,"kind":1}
```

and on that same detonator epoch the counter is **present and standing still**, which is what makes
the red attributable to the mechanism rather than to a missing metric:

```
traderx_book_reticks{member="0"}   0     <- exported, never moved: no re-derivation happened
traderx_band_reanchors{member="0"} 1     <- the OLD mechanism answered instead
```

## The general lesson, which is not about JSON

The two defects are the same mistake wearing different clothes: **an assertion written against a
rendering of a value instead of the value**. A substring test over serialized output inherits every
property of the serializer — its spacing, its ordering, its numeric formatting — none of which the
assertion's author is thinking about, and all of which can silently make the test unsatisfiable or
unfalsifiable. Parse, then compare.

The detonator is what found it, and nothing else could have: the proof was red on the pre-mint build
for a *true* reason, so no amount of re-reading the red would have exposed it. That is the argument
for gate V4's on-rig half being owed at all, discharged here.
