# 06 — RESULT: kdb captures the live TraderX flow

> Second half of brief 06. The first half (KDB-X reading the TAQ Parquet corpus natively) landed
> 2026-07-27; kdb held real NYSE data but had never seen a TraderX order. It has now.

## What shipped

| | |
|---|---|
| **Capture** | `KdbTapWriter` — leader-side, off-consensus tap beside `TradeNatsPublisher` / `OrderNatsPublisher`, gated on `KDB_TAP_DIR` |
| **Schema** | `txOrder` / `txTrade` in `txstore.q` — deliberately *not* `quote` / `trade`, which are the TAQ tape |
| **Playback** | `.tx.session[]` + `.tx.replay[…]` — analytical replay of a captured session |
| **Gate** | `txselfcheck.q`, 18 checks over a fixture the cluster itself wrote |
| **Branches** | YU13, YU14, YU15 (each layer's service override wired and verified in its generated tree) |

## The naming, which is the point of the deliverable

| table | what it is | source |
|---|---|---|
| `quote`, `trade` | NYSE TAQ tape — what the **market** did | TAQ ingest, `tickstore.q` |
| `txOrder`, `txTrade` | **our** engine's order lifecycle and executions | `KdbTapWriter`, `txstore.q` |

Both load side by side. A tape print and an engine execution are different objects; one `trade`
table holding both is how a VWAP silently answers a question nobody asked. `txTrade` rows carry an
`account`, tape trades never do — that is asserted in the gate.

Same discipline for the two "journals" and the two "playbacks": the **Aeron Archive is
authoritative** (consensus, recovery, hot path) and is untouched by any of this. The kdb capture is
a tickerplant log — analytical, off-consensus, best-effort. Delete the whole capture directory and
the cluster still recovers byte-identically; delete the Archive and it does not.

## Proof

**On kind, 3-member cluster, real REST orders** (2026-07-27). Two resting sells, two crossing buys,
booked through consensus:

- leader `order-matcher-cluster-2`: **9 order events + 4 executions** captured
- both followers: **header-only files, zero rows** — the leader-only guard, observed rather than argued
- the cluster's own counter said `trades: 4`; the capture holds exactly 4
- KDB-X loaded the pulled capture directly: fill VWAP **150.1444** over 360 shares, price-time
  priority visible in the rows (the 150.15 buy filled at the resting 150.10), partial fills on both
  sides, `.tx.replay` walked all 13 rows

**In the suite, real Aeron cluster** — `AeronClusterSpikeTest.leaderTapCapturesTheAppliedSessionForKdb`
drives a cross through actual consensus and asserts the capture against
`service.engine().tradeCounter()`. The q gate's fixture is the output of that test, so its expected
values trace back to the engine's own counters rather than to kdb agreeing with kdb. Verified
falsifiable: delete one side of the cross from the fixture and the gate exits 1.

`KdbTapWriterTest` (6) pins the CSV column contract, the drop signal, and the unregistered-symbol
fallback. Full YU13 order-matcher suite: **288 tests, 0 failures**.

## The three traps, and what was done about each

**1. Never a kdb write in the apply path.** The service thread allocates one record and does a
non-blocking SPSC offer; a daemon thread does every file-system call — no flush, no fsync, no
`open()` on the apply thread. A stalled disk fills the queue and drops. With `KDB_TAP_DIR` unset the
whole tap is one null check per output event, the same shape as the two NATS bridges.

**2. Output-ring self-deadlock.** Nothing was added to the ring or to its drain contract: the tap
reads what `drainOutputs` already walks, inside the existing loop, alongside the two proven bridges.
No new emission point, no second consumer, `OutputPublisher`'s drain-and-retry untouched.

**3. Visible drop signal.** First drop and every 10,000th WARN; totals printed on stop. Two
corrections came out of the live run and are worth keeping: an *empty* ticker counts as unregistered
(captured as `#<id>`, never skipped — skipping is the silent thinning this tap exists to prevent),
and `.tx.gaps[]` is **not** a loss signal — control events consume consensus sequence numbers
without producing a captured row, so gaps are expected around seeding. The tap's counter is the
verdict; the gaps view is a question.

## Cost to the hot path

Structurally zero when disabled (one null check). Enabled, it adds one small allocation and one
queue offer per captured output event on the apply thread. **That is a timing claim and it is not
measured here** — kind on a contended box cannot support a latency number. Measure on GKE with
`LATENCY_DECOMP=1`, tap on vs off, per [[project_latency_thread]]'s method.

## Notes for whoever runs this next

- `POST /orders` on the cluster gateway reads **`ticker`**; `/orders/batch` and `/trades` read
  `security`. Sending `security` to `/orders` does not 400 — the gateway registers the *empty*
  symbol, burns a security id, and every order is then rejected `UNKNOWN_SECURITY`. That cost a
  chunk of this session; the `run-state-kind` skill's example payload uses `security` and is
  correct only for the non-cluster states. Worth either accepting both fields or rejecting a blank
  ticker outright.
- An Aeron cluster on a kind box shared with a second kind cluster does not merely get slow: it
  stops applying entirely (`applied: -1`, snapshot count climbing, gateway offers unanswered) while
  looking healthy — 3/3 Running, `/ready` 200. Check `applied` on `/health` before believing a rig.
- `KDB_CAPTURE_FIXTURE_DIR=<kdb dir>/fixtures/session-yu13 ./gradlew test --tests '*Spike*'`
  regenerates the q gate's fixture from a real cluster run.
