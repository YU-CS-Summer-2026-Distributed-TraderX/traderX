# HANDOFF-OSFF-2 — Real limit-order-book matching engine (YU13)

> One of the OSFF-NY direction handoffs (OSFF-1..4), created 2026-07-20. **This is work 2 — the
> centerpiece net-new build for the Nov 4–5 talk.** Decision made with yaakov: replace the current
> price-triggered auto-fill matcher with a genuine crossing limit-order book. A real CLOB is the one
> thing a finance audience reads instantly, and "deterministic, zero-GC crossing engine on Raft
> consensus" is the strongest stage narrative. Likely a new state, **YU13**. Self-contained for a
> fresh chat.
> **Home:** `traderX-YU12-aeron-cluster` worktree, `docs/handoff/` — beside the YU12 recaps; the
> `HANDOFF-issue-yu12-*.md` docs it references are in that worktree's `issues/`. Untracked working note.

## The gap

The current matcher is NOT a real book. Per `RECAP-yu12-full-arc.md` §5 and
`RECAP-2026-07-20-yu12-bridge-bench-session.md` §1: it is **price-triggered auto-fill against the
security's last market price** (`MatchingEngine.java:613`, `isInTheMoney` against `lastPxBySecurity`)
— a BUY fills at `mark ≤ limit`, a SELL at `mark ≥ limit`, **with no opposing resting order**. Worse,
`FILL_FULL_THRESHOLD = 100` (`MatchingEngineClusteredService.java:50`) half-fills any order with
`remaining ≥ 100` and leaves the rest resting, so at the bench default `QTY=500` the book grows
unbounded. In front of a finance crowd — and under a risk engine marking positions — "it's not
actually a matching book" is a credibility hole.

## What "done" looks like (scope it TIGHT)

**In scope (this is what makes it real enough):**
- Two-sided book, **price-time priority**, genuine crossing (a marketable BUY matches resting SELLs
  at their price, best-price-first, FIFO within a level).
- Limit orders, market orders, cancel. Partial fills. Book depth per side.

**Out of scope — resist these (ponytail line):**
- Iceberg / pegged / hidden orders, exotic time-in-force, auction phases, multi-venue. Advanced
  order types are a *separate* deferred idea (`HANDOFF-idea-advanced-order-types.md`) — do not
  smuggle them in. Limit + market + cancel is credible; twelve order types is gold-plating nobody
  demos in 30 minutes.

## The three invariants that cannot break

The book lives inside the Aeron Cluster `ClusteredService`, on the hot path. The whole YU11/YU12
discipline still applies:

1. **Determinism.** No wall-clock in matching. The **consensus-log order IS the time priority** —
   which is perfect, it's already the deterministic input sequence (ADR-044/045: the consensus log
   is the ONLY input). Every member must compute an identical book from the same log.
2. **Zero-alloc / zero-GC.** Pre-allocated order pool; array-indexed-by-price-tick levels with
   intrusive linked lists per level (the same array-indexed pattern the professor's own deck used
   for NBBO). `noGcTest` + the allocation gates must stay green. Watch the output-ring self-deadlock
   class that bit YU12 (service thread is producer AND consumer of the output ring; a fill cascade
   larger than the ring parks apply forever and wedges REPLAY on all members) — the
   `OutputPublisher` inline drain-and-retry override must survive the rewrite.
3. **Snapshot completeness.** The snapshot must now serialize the **entire resting book, both
   sides** — not just counters/idempotency. This is a new snapshot shape. Run the
   `traderx-snapshot-completeness-audit` skill against it before trusting failover (ADR-046 already
   requires every future-output generator be captured; the book is now one of them).

## Re-prove everything on the NEW engine (don't reuse YU12 auto-fill numbers)

- Throughput — real crossing does more work per order than auto-fill; measure honestly, expect it to
  move. Baseline to clear is still NFR-AC02 = 25,149 booked/s.
- Clean leader-kill failover (node-clock) + **no orderRef reuse** across the failover — the
  nextOrderRef bug class is structurally gone in YU12; confirm the new book keeps it that way.
- `traderx-ha-recovery-proof` (kind): crash → promote → replacement rejoin (empty-disk) → second
  crash, 0 reuse, book identical on all members after.

## Latency track — how fast the engine ITSELF can go (system/transport latency is OSFF-3)

**Don't conflate three different latencies** (this is the framing for the talk, so the number is honest):

| What | Realistic floor | Notes |
|---|---|---|
| The match itself (in-memory book op, one order) | **tens–low-hundreds of ns** | The engine's own number. Achievable — you're most of the way with zero-GC + Disruptor + SBE + the array-indexed book. **This doc's scope.** |
| Wire-to-wire (NIC→decision→NIC) in software | **~1–10 µs** | Transport/OS, not the engine — **OSFF-3's scope.** |
| Wire-to-wire in hardware (FPGA/ASIC) | **~30–250 ns** | NOT acquirable; out of scope. **Never claim ns wire-to-wire in software.** |

Engine-internal levers (all commodity, all free):
- **Book data structure** (already an invariant above): array-indexed-by-tick + intrusive linked lists per level → O(1) add/cancel/match, zero-alloc, cache-friendly; hot best-bid/best-ask pointers. No tree/map on the hot path.
- **JVM warmup / JIT:** AOT or GraalVM native-image (or Azul Zing + ReadyNow) to kill JIT warmup and deopt cliffs — a top source of tail-latency spikes. Keep the hot path monomorphic; `@Contended` padding against false sharing (Disruptor already does this).
- **Zero-GC kept religiously** (you have it); Epsilon (no-op GC) for the hot benchmark.

Measurement is an acceptance gate, not an afterthought:
- **HdrHistogram**, report **p50 / p99 / p99.9 / p99.99 / max in nanoseconds** for the match op. The mean is meaningless — **tail latency is the number.**
- Load generator that does **not** back off under pressure (coordinated-omission trap — Gil Tene). A backing-off generator hides your tail.

**Acceptance addition:** per-order match-latency histogram (ns, full percentiles) captured on the new engine. Honest talk line to carry: *"the match is nanoseconds, wire-to-wire is microseconds in software, nanoseconds only in silicon."*

## Dependencies & sequence

- Comes after **OSFF-1** (have a working spine as insurance before cutting the matcher).
- Feeds **OSFF-3** (real TAQ order flow only makes sense against a real crossing book) and gives the
  risk engine (**OSFF-4**) real marks to price against.

## Open questions

- Price representation: integer ticks (already `priceTicks` in the SBE layer) — confirm the tick
  size / instrument scaling is enough for the TAQ price range OSFF-3 will feed.
- Matching-time market-data role: with a real crossing book, `lastPxBySecurity` becomes an *output*
  (last trade price) rather than the *trigger*. Decide whether market orders that don't cross rest,
  reject, or fill at a reference — keep it simple (rest or reject) for v1.
- Bench/canary flow must now be genuinely two-sided and marketable so orders actually cross
  (`LIMIT=150 SIDES=alternate` + a dedicated real account; rejects still consume orderRefs).

## Carry-forward from OSFF-1 (do NOT re-hit this wall)

OSFF-1 uncovered that the engine **silently rejects** any market trade whose account isn't
control-enabled / security isn't enabled / security has no price tick (`BlpRiskState.java:227`), and
`/trades` is **fire-and-forget** (200 + vanish, no error anywhere). The new crossing book routes
through the **same gate**. Before testing the new book on kind: **seed the real accounts + reference
universe first** (the `seed-real-accounts` control events, or the bench `/seed`), or every order books
nothing with no signal and you'll chase a phantom bug for a session. See OSFF-1 STATUS. Consider
giving the new book a **synchronous reject reason** on the `/trades` path while you're in there — it
closes the silent-rejection landmine for the demo and for OSFF-4's feed.

## Start condition (OK to run in parallel with OSFF-1's GKE verify)

OSFF-1's fix is committed (`01478e4`); only its GKE apply/verify remains, which is deploy-only and
off this state's path. Scaffold YU13 on YU12's current tip (inherits the spine fix) in its **own
worktree**, and build/prove on kind. No need to wait for the GKE acceptance artifacts.

## STATUS (2026-07-21) — OSFF-2 COMPLETE. Built, reconciled, and proven live on kind AND GKE.

Everything below this line was written progressively; the summary here is authoritative.

| Item | Result |
|---|---|
| Crossing engine + snapshot format 2 | ✅ built, `test` 227/0, noGc + all four allocation gates |
| Reconciliation with the YU12 lane | ✅ `0c9cbd7` — see the (now-historical) section below |
| T-LOB14 kind bench + HA proof | ✅ incl. a falsifiable price-priority fixture |
| T-LOB15 **GKE like-for-like throughput** | ✅ **62–75k booked/s vs the 25,149 bar — 2.5–3×, zero failures** |
| Engine match latency, full tail | ✅ p50 167 ns insert / 542 ns cross; p99 < 2 µs |
| Wire-to-wire REST latency (GKE) | ✅ p50 1.42 ms @100/s, coordinated-omission-safe |
| **Failover, node-clock** | ✅ **median ~200 ms, worst ~450 ms — bimodality eliminated** |

16 commits on `YU13-limit-order-book`, **none pushed**. GKE runs the fixed gateway.

Two structural wins worth carrying into the talk: the **book stays bounded** (open orders return to
0 — the parent's `FILL_FULL_THRESHOLD` half-fill growth is structurally gone), and **no divergence
after ~4.5M booked trades** (identical trade counter and book digest on all three members).

### The bimodal-failover bug — root-caused and fixed (2026-07-21)

Failover was bimodal (fast ~83–182 ms / slow ~673–1316 ms) with identical code and kill command.
**It was never Raft.** Root cause was one word in the gateway's reconnect:

```java
private void connectCycling() {
    int attempt = 0;   // <-- LOCAL: resets every call, so every reconnect starts at endpoint 0
```

With `GATEWAY_INGRESS_ENDPOINTS` ordered `0,1,2`, killing **member 0** made the gateway block on the
dead endpoint's connect timeout before trying a live one. Killing m1/m2 hit a live endpoint
immediately — that was the "fast mode" all along.

**The diagnostic that proved it (reusable):** run `/orders` and `/ready` as *independent probes at the
same cadence*. `/ready` is gateway-local — 200 only while the gateway's cluster session is up, and it
never touches the leader. Both probes failed for the same window (m2: 41 ms vs 201 ms; m0: 1270 ms vs
1316 ms), which rules out consensus entirely and localises the outage to the gateway re-establishing.

**Fix:** the first reconnect attempt hands Aeron the complete member list so the cluster client
resolves the leader itself; single-endpoint cycling remains a fallback with a rotating start that
*persists across reconnects*, so a dead endpoint is never retried first twice.

| | before | after |
|---|---|---|
| kill m1/m2 | 83, 141, 182, 201 ms | 162, 204 ms |
| kill **m0** | 673, 848, 1316 ms | 222, 445 ms |
| worst observed | 1316 ms | **445 ms** |

**Why this mattered more than the metric:** member 0 is the first pod of the StatefulSet, so the slow
path was the one most likely to be hit in real operation — node drain, rolling update, zone event. It
also explains why the first three samples looked like a regression against YU12: they happened to
catch m0 kills.

### Historical detail below (kept for the reasoning; superseded by the summary above)

## STATUS (2026-07-20) — YU13 built & JVM-proven; ONE reconciliation required before T-LOB14

Built on branch `YU13-limit-order-book` (6 commits, unpushed, worktree `traderX-YU13-limit-order-book`):
`LimitBook` — array-indexed price levels on a 0.001 grid inside a banded per-security window,
intrusive doubly-linked FIFO queues of pooled orders, per-side occupancy bitmaps for O(1) best-price.
Crossing is best-price-first, FIFO within level, **at the resting order's price**; both sides emit
order update + booked trade + position; market orders fill-then-cancel-remainder (never rest);
off-grid → `INVALID`, out-of-band → `PRICE_COLLAR`; ticks seed the mark only until the first trade.
**Time priority = consensus-log order** (no clocks added). Snapshot **format 2** serializes the whole
resting book (geometry + per-security band anchors + ascending-ref open rows rebuilding each level's
exact FIFO), failing closed on off-grid/out-of-band/legacy rows.

JVM green: `test` **227/0**, `noGcTest` (Epsilon), all four allocation gates; crossing correctness,
snapshot round-trip + price-time-priority preservation, replay determinism, and the four inherited
suites reworked to crossing semantics. Match latency **p50 167 ns** resting insert / **583 ns** limit
cross. `generate-state.sh YU13-limit-order-book` exits 0.

**Open: T-LOB14** — live kind bench (two-sided marketable flow vs the 25,149 booked/s NFR-AC02
baseline) + kind HA recovery proof on the crossing engine. Needs a running cluster.
Also: `ThreeMemberClusterTest` is environmentally flaky under sustained load (passes solo in ~40 s) —
re-run solo before suspecting a crossing regression.

### Reconciliation with the parallel YU12 lane — REQUIRED, and a plain cherry-pick is WRONG

YU13 was cut at `01478e4`. YU12 has since added two commits YU13 does **not** have:
- **`3394186`** — gateway `/trades` made synchronous: 200 booked / **422 + RiskReason** on reject /
  **504** on no committed decision.
- **`2f5a813`** — seed-job retry+timeout hardening, recon env-name fix (`RECON_POLL_INTERVAL_MS`),
  gateway replicas=3.

**Trap 1 — byte-21 collision (silent corruption; git will NOT flag it).** Both lanes claimed byte 21
of the 24-byte egress ack:

| Lane | writes byte 21 as | gateway reads it as |
|---|---|---|
| YU12 `3394186` | `ackBuffer.putByte(21, out.riskReason)` | RiskReason ordinal → `lastTradeAck` |
| YU13 `2589ff1` | `putByte(21, FLAG_RESTING_UPDATE ? 1 : 0)` | `restingUpdate` boolean |

Ack layout is `appliedSeq 0–7, orderRef 8–11, kind 12, tradeSeq 13–20, byte 21` → **bytes 22–23 are
free.** **RESOLUTION: `restingClass` stays at 21** (YU13's engine is the going-forward one); **move
`riskReason` to byte 22**, updating the service writer and the gateway reader together. Left unmerged,
any non-zero RiskReason reads as `restingUpdate=true` and every resting update reads as a bogus reject.

**Trap 2 — layer shadowing.** `3394186` patches
`specs/YU12-aeron-cluster/.../ClusterGatewayMain.java`, but YU13 carries its **own**
`specs/YU13-limit-order-book/.../ClusterGatewayMain.java` + `MatchingEngineClusteredService.java`,
which win at generation (last-wins overlay). **Verified: YU13's gateway copy has 0 occurrences of
`lastTradeAck`.** A cherry-pick therefore lands the fix in a **dead, overridden layer** — it must be
**hand-merged into YU13's own copies**. (Classic `propagate-spec-fix` step-4 case.)

**Preserve the semantics on merge:** `422` = business reject (terminal); `504` = no committed decision
within timeout (ambiguous, retryable). **Never map a failover timeout to 422** — a confident false
negative is worse on stage than the silent vanish it replaced.

`2f5a813` should cherry-pick straight (YU13 doesn't appear to shadow those manifests) — but grep every
`specs/*/` layer for same-named files first before trusting it.

**After the merge, re-run the hot-path gates** (`test`, `noGcTest`, all four allocation gates) — the
ack layout is hot-path, not a docs merge.

### Acceptance gap still open
Latency was reported as **p50 only**. The latency track above requires **p50 / p99 / p99.9 / p99.99 /
max** — tail is the number that matters for the talk, and a p50-only slide invites exactly the
question we can't answer. Capture the full histogram.

## First steps for the chat that picks this up

1. Read both YU12 recaps + this doc; skim `MatchingEngine.java` + `MatchingEngineClusteredService.java`
   to see exactly what the price-triggered path does today (what you're replacing).
2. Run `new-yu-state` to scaffold **YU13** (parent on YU12 `YU12-aeron-cluster`, same-named branch,
   commit-but-never-push). Spec pack: functional reqs + acceptance criteria + the three invariants
   above as explicit gates.
3. Build the book behind the existing SBE ingress; keep the `OutputPublisher` override; extend the
   snapshot; run the completeness audit + HA proof + a fresh honest bench before declaring it done.
