# Demo runbook: the tape is the reference (ADR-070)

The pitch in one sentence: **"This is Apple on February 4th, 2025"** — a sentence the audience can
check — instead of "this is a random walk seeded at 200". Every price on the equity/ETF rows is a
real Feb–Mar 2025 print (a 195 s median of regular-hours trades), replayed on a clock anchored to
the epoch, with its provenance and true timestamp on the wire.

**Display rights, before anything else.** The recorded permission (ADR-070, context) covers *use*.
A live internal demo is use. **Do not record, stream, or publish demo output showing the prices**
until ADR-068 open question 1 (display rights) is settled — that question is still open.

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
5. **The revert is real (ADR-068 rule 1).**
   ```bash
   kubectl --context kind-traderx-yu12-cluster -n traderx delete secret taq-replay-extract
   kubectl --context kind-traderx-yu12-cluster -n traderx rollout restart deploy/price-publisher
   ```
   Equities walk again, `/health.taqReplay.error` says exactly why, and nothing else changes — no
   network, no key, no tape required. Return:
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
- The front-end ticker shows the replayed prices already; until the UI lane lands the replay-clock
  widget, the *narrative* state (tape date, day N/40, held) lives on `/health.taqReplay` — keep a
  terminal with it visible.
