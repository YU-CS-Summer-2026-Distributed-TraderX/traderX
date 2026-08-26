# Demo runbook: the tape is the reference (ADR-070), and it trades (ADR-072)

The pitch in one sentence: **"This is Apple on February 4th, 2025"** — a sentence the audience can
check — instead of "this is a random walk seeded at 200". Every price on the equity/ETF rows is a
real Feb–Mar 2025 print (a 195 s median of regular-hours trades), replayed on a clock anchored to
the epoch, with its provenance and true timestamp on the wire.

**And since ADR-072 the tape does not merely price the venue — it trades on it.** A deterministic
sample of the real prints is submitted as ordinary orders at ~6/s, so the book, the blotter,
positions and P&L move from activity that genuinely happened. Before it, an idle rig had **3 of 69
books that had ever printed and six trades total**; twenty minutes into an epoch with it live, **23
books carry live depth and the venue has booked 430 trade legs**. The second sentence of the pitch
is therefore: *"and this is the engine working on it"*, which is the part a matching engine is
actually for.

**Display rights, before anything else — and this gates TODAY, not a future widget.** The
recorded permission (ADR-070, context) covers *use*. A live internal demo is use. But the front
end is **already displaying licensed data right now**: its ticker consumes the same `pricing.*`
subject the adapter does, so every equity row on screen has carried real Feb–Mar 2025 prices since
the replay went live. Until ADR-068 open question 1 (display rights) is settled — escalated to
yaakov 2026-08-26 as blocking — **no screenshot, recording, stream, or deck capture of any surface
showing these prices**. Design screenshots and UI work: switch to synthetic first via the revert
in step 5, which exists for exactly this.

## Prerequisites

- The kind rig up (`scripts/yu15/start-cluster-kind.sh` — it fetches the extract Secret and stamps
  the replay epoch itself). Feed adapter sequencing (`kubectl … logs deploy/feed-adapter` shows a
  `FEED …` line a minute with `sequenced` climbing).
- `price-publisher /health` shows `taqReplay.error: null`. If it shows a sentence instead, that
  sentence is the diagnosis (no Secret fetched, no epoch stamp, invalid extract).
- **Rehearse the synthetic arm first** (ADR-068's consequence: rehearsed, not merely capable —
  step 5 below). A demo that has never run the revert is not allowed to claim it.

The clock: 1 trading day = 30 wall-clock minutes (195 s windows × 13× compression = one fresh
window per 15 s sequenced flush). The 40-day tape spans 20 h from each fresh epoch.

## Reading the state (what a presenter keeps open)

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx exec deploy/price-publisher -- \
  wget -qO- localhost:18100/health | python3 -m json.tool
```
`taqReplay.position` is the story: `tapeDate`, `dayIndex`/40, `asOf`, `held`. Any quote shows the
wire truth: `/prices/AAPL` → `source: taq-replay-2025-02`, `asOf` in Feb–Mar 2025. The members'
applied view: `/bbo` on any member — `ref` is the collar's anchor, a tape value that went through
consensus.

## The replayed order flow (ADR-072), and the two things to say about it

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx exec deploy/price-publisher -- \
  wget -qO- localhost:18100/health | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin)["printReplay"],indent=1))'
```

`submitted` / `accepted` / `rejectedByReason` is the panel. **Two things must be said out loud, and
they are both in that block rather than in anyone's notes:**

- **The side is invented.** TAQ trades carry no side and no NBBO survived our ingest, so buy/sell
  comes from the **tick rule** — an uptick is a buy, a downtick a sell. `sideRule` says `INVENTED`
  in as many words. The prices are real; the direction is an approximation, and saying so is the
  same discipline `asOf` exists for.
- **The size is invented too, and differently.** `quantity` is a flat 10. TAQ's own size is 54% odd
  lots and would have to be clamped against the order cap, so it would not have been real either.
  `quantityNote` says this on the wire.

**Replayed activity trades on its own accounts — 900001, 900002, 900003, shown in the blotter as
"TAQ Tape Replay N (ADR-072)".** That is what lets an operator tell the audience which fill was
theirs, and it is the same tag the members use to keep the replay out of every counter the proof
suite brackets its own work with.

**A `PRICE_COLLAR` rejection in that block is the demo, not a bug.** A real February print that
moved further from its window's median than the band allows is refused, live, on real data. Say it
before someone asks.

## The storyline (each jump ~1 min of setup, via `scripts/yu17/replay-jump.sh`)

1. **The open, for real.** `replay-jump.sh 2025-02-03` — the tape's first morning. AAPL ≈ 230,
   SPY ≈ 593, TSLA ≈ 386. GOOGL and FNMA visibly carry `source: previous-close` — they are
   *deliberately* synthetic (merged share classes in the store / OTC not in TAQ), and the wire says
   so instead of pretending.
2. **A real overnight gap.** Let the clock run across a session boundary (or jump to
   `2025-02-03 15:58` and wait ~2 min): the close at 16:00 ET and the next open at 09:30 are one
   tick apart, `asOf` jumps the night, and the discontinuity is Feb 3's actual close to Feb 4's
   actual open — ADR-069's rule checked against reality instead of asserted.
3. **The March selloff.** `replay-jump.sh 2025-03-11 14:30` — TSLA has fallen from 386 to 232,
   the collar band follows real repricing, and the books still work. Nothing about this day was
   invented.
4. **The end of the tape holds.** `replay-jump.sh end` — the reference freezes at Mar 31's close
   and `asOf` stops moving: a real price with an honestly ageing timestamp. It never loops (a
   fabricated seam) and never falls back to synthetic (a silent provenance change). Watch `asOf`
   stand still while the wall clock doesn't.
5. **The revert is real (ADR-068 rule 1).** Two Secrets now, and deleting only the first also
   stops the order flow — `print-replay.js` refuses to trade real prints against a synthetic collar
   reference, which is the one combination both ADRs forbid.
   ```bash
   kubectl --context kind-traderx-yu12-cluster -n traderx delete secret taq-replay-extract taq-print-sample
   kubectl --context kind-traderx-yu12-cluster -n traderx rollout restart deploy/price-publisher
   ```
   Equities walk again, `/health.taqReplay.error` says exactly why, and nothing else changes — no
   network, no key, no tape required.

   **If you are building a consumer, read this before you key on `error`.** Deleting the Secret does
   **not** clear the `taqReplay` block — it returns present, with no position and `error` set to
   `"no extract at …"`. So **this deliberate, rehearsed revert populates `error` exactly as a real
   fault does**, and a client keyed on that field alone renders the demo's own honest mode as a red
   breakage. Worse, a *corrupt* extract is structurally identical to an absent one — no `source`, no
   `days`, no `position` — so structure cannot separate them either; only the message text can.
   Distinguish the absence phrase explicitly and treat every other error as a fault, so an
   unrecognised message **alarms rather than going quiet**. Found the hard way 2026-08-26 by the UI
   lane, which shipped two wrong readings in a row before landing on this; see
   `issues/open/the-publisher-signals-absent-and-corrupt-tape-identically.md`. Return:
   `bash -c 'source scripts/yu15/lib-replay-epoch.sh; K="kubectl --context kind-traderx-yu12-cluster -n traderx"; fetch_replay_extract_secret; stamp_replay_epoch'`

**After the demo, restore the real anchor** (the jumps de-anchor the clock from the mint):
```bash
bash -c 'source scripts/yu15/lib-replay-epoch.sh; K="kubectl --context kind-traderx-yu12-cluster -n traderx"; stamp_replay_epoch'
```

## Traps

- **EOD closes during a demo**: the first close after any fresh epoch (or a big jump) legitimately
  SPIKE-flags the replayed names against the prior published closes — the tape moved, the database
  didn't. That is the gate working. Resolve by override-at-observed-close (what the yu06 proofs
  do), or don't close sessions mid-demo.
- **The Browser pane runs hidden tabs at 1 Hz** (memory: console-dev-server) — judge tick rates
  from a fronted tab only.
- **The blotter fills up fast.** At ~6 orders/s the demo accounts' own trades are a small minority
  of the rows within minutes. Filter by account before showing a blotter, or the operator's own fill
  is a needle. This is ADR-072's own stated consequence and it arrives sooner than it sounds.
- **Slowing the flow down needs no rebuild**: `PRINT_REPLAY_STRIDE=2` on the publisher halves the
  rate, `PRINT_REPLAY=0` stops it entirely. It can never be raised above what the sample was built
  for — that is a rebuild of the artifact.
- The front-end ticker shows the replayed prices already; until the UI lane lands the replay-clock
  widget, the *narrative* state (tape date, day N/40, held) lives on `/health.taqReplay` — keep a
  terminal with it visible.
