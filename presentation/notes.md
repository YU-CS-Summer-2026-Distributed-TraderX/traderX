# TraderX-LMAX — Presentation Notes

Presenter companion for the state-by-state mentor/demo walkthrough. One deck per state
(`YU0x-slides.html`, self-contained HTML — open in a browser, arrow keys / click to advance).
Each section below: what the state *is*, a slide-by-slide guide, tidbits worth saying out loud,
honest caveats (so a sharp question doesn't catch you out), and a live-demo cheat-sheet.

**The through-line of the whole story:** the core is an exchange-grade matching engine (LMAX
Disruptor BLP — single-threaded, in-memory, event-sourced). Every state after YU02 adds a
*production-OMS* capability (risk, durable control, post-trade/compliance, EOD batch, tick store,
execution algos, ops hardening) **without touching the hot-path determinism** — new state is fed in
as sequenced events or lives at the edge, never as ad-hoc mutation of the engine.

Cluster for all live demos: kind `kind-traderx-state-014`, namespace `traderx`, UI/edge-proxy at
**http://localhost:8080** (use `localhost`, not `127.0.0.1` — both are allowed by CORS now, but
docs historically said 127.0.0.1 and that tripped a 403 once).

---

## YU02 — LMAX Kubernetes (the architectural pivot)

**What it is.** The order-matcher stops being a thin CRUD service doing a synchronous DB write per
order and *becomes the LMAX Business Logic Processor*: a single-threaded, in-memory, event-sourced
matching engine fed by a Disruptor ring buffer. MariaDB drops to an async read-model (CQRS), not
the source of truth. Plus durability (journal + snapshots, replay on restart) and two runtime modes
on Kubernetes (single-BLP for throughput; HA StatefulSet with leader election + NATS JetStream
warm-standby). Live at **https://yaakovseif.dev**.

**Slide guide.**
1. Title — the pivot: order-matcher → BLP.
2. Problem — synchronous DB-per-order is the throughput/latency ceiling.
3. BLP core — Disruptor ring, single writer, no lock contention, deterministic replay; DB becomes async read-model.
4. Durability — journal every input event, periodic snapshots, replay from last snapshot boundary, readiness-gated until replay done.
5. Kubernetes modes — single-BLP (1 replica, max throughput) vs HA (2 replicas, Lease leader election, JetStream replication, ~2–3s pod-death failover).
6. CI/CD — Cloud Build → Cloud Deploy, git push builds an image but **a human approval gates the cluster**.
7. Results (the bar chart) — the headline numbers, below.
8. Resilience — the lease-starvation bug story, below.
9. Takeaway — engine is already exchange-grade; the gap is at the edges and in OMS breadth (the rest of the states).

**Tidbits to mention.**
- **The bar chart is the whole point:** matching core alone ≈ **6,000,000 ops/s**; single-BLP
  end-to-end (REST → match → journal → DB) ≈ **42,000 booked/s**; HA end-to-end ≈ **22,000/s**. The
  core is 100–300× faster than the full pipeline — **the bottleneck is the edges (REST ingress +
  DB projector), never the engine.** That framing sets up every future state.
- **The lease-starvation war story** (great engineering-judgment anecdote): under CPU-saturating
  load the primary would *false-demote itself*. We ruled out GC (157ms max stop-the-world vs a 5s
  renewal window — not GC), root-caused it to the blocking Lease GET+PUT starving on the
  election thread, and fixed it with *demote-on-proof* election + a pod-GET fast path + synchronous
  admission fencing. Validated: 5 saturating runs, **zero** false-demotes; ~2–3s real-kill
  failover with exactly one promotion; a cgroup-freeze wedge test correctly demoted the frozen
  primary. Fix deployed to prod and propagated to all 8 states.
- CI/CD approval gate is a genuine control, not theatre — we validated it holds at
  `PENDING_APPROVAL` and never touches live pods until approved.

**Caveats / sharp-question defenses.**
- "Why is HA *slower* (22k vs 42k)?" — the follower ACK + NATS replication is synchronous on the
  commit path; that's the durability cost. It's a deliberate mode toggle, not a regression.
- Prod currently runs **single-BLP** (throughput), not HA — HA is failover-tested but not the live
  serving mode. Say so if asked which is live.
- "Is 22–42k good?" — for a JSON/REST + single-DB pipeline, yes; and it's edge-bound, so the
  levers (binary/SBE ingress, projector→DB decoupling) are known and itemized in the backlog.

**Live demo cheat-sheet.**
- Site is live: `curl -so /dev/null -w '%{http_code}\n' https://yaakovseif.dev` → 200.
- Place an order end-to-end via the UI → HTTP 201 (booked).
- (GKE cluster is usually scaled to 0 to save cost — spin nodes up first if demoing live on prod.)

---

## YU03 — In-Memory Risk Gateway

**What it is.** Pre-trade risk control on the hot path (SEC Rule 15c3-5, Market Access). YU02
matched any valid-ticker order; YU03 makes every order pass real risk checks first — **in memory,
zero synchronous DB/REST per order.** Two tiers: (1) an in-memory admission gateway that
pre-screens against event-fed local replicas, and (2) the BLP, which re-checks and reserves exact
exposure *authoritatively, in global sequence order*.

**Slide guide.**
1. Title — pre-trade risk, in memory, no per-order DB call.
2. Problem — YU02 had zero pre-trade control; the only "limit" was the order's own limit price.
3. Design (two tiers) — fast in-memory admission screen, then authoritative in-sequence BLP decision.
4. Controls (the money slide) — every control, enforced pre-trade, with the live reject codes.
5. Control plane — operators steer risk live (restrict / kill-switch / versioned / authenticated).
6. **Write path (mechanism) — "a control change is an event, not a mutation."** How control reaches the engine (see below). *This slide was added so the "how" is explicit, with a code snippet.*
7. Nearly free — every check is in-memory, event-fed; 0 synchronous DB calls per risk decision.
8. Audit — rejects are sequenced, journaled, replayable → the 15c3-5 audit story.
9. Takeaway — from a fast matcher to a risk-controlled OMS tier.

**The mechanism (slide 6) — say this precisely, it's the crux the mentor will probe.**
A control change does **two** things: it updates the tier-1 edge replica, *and* it sequences a
versioned `*_CONTROL` event **onto the same Disruptor input ring as orders**. The single-writer BLP
then applies it to authoritative `BlpRiskState` in global order. So:
- a control and the orders around it **can't race** (they're totally ordered on one ring);
- the whole decision is **journaled and replayable** — same event stream reconstructs the same
  accept/reject verdicts every replay (that's the audit guarantee);
- restricting a security emits **explicit sequenced CANCEL events** for resting orders (FR-IMRG24)
  — never a silent book mutation.
- Code to point at: `RiskControlController.java:57–58` (`replicas.applyAccount(...)` +
  `engine.submitAccountControl(...)`), `LmaxEngine.submitControl` → `inputRing.publish(seq)`,
  `MatchingEngine.java:152–155` (`case TYPE_*_CONTROL -> on*Control(e)`).

**Tidbits to mention.**
- Control baseline is a real reg: **SEC Rule 15c3-5** — pre-set credit/capital limits,
  erroneous-order checks, restrictions, kill switch, enforced *before* the order reaches the market.
- Live reject codes to show: UNKNOWN_ACCOUNT, PRICE_COLLAR (collar is 5000 bps = ±50%),
  ORDER_SIZE (cap 1,000,000), RESTRICTED, KILL_SWITCH. A valid order returns 201/NEW.
- The **two-tier split is the interesting design call:** the fast screen keeps the hot path free of
  DB/REST; the BLP still owns the authoritative reservation because *only* the single writer can
  reserve exact exposure deterministically in sequence order.

**Caveats / sharp-question defenses.**
- "Nearly free — did you benchmark it?" — **No, and don't claim a throughput number.** The claim is
  a *design* property: 0 synchronous DB/REST per decision, a handful of in-memory comparisons on
  the hot path (FR-IMRG01). (We deliberately softened slide 7 to remove an unmeasured "~same
  throughput as YU02" claim — a synthetic bench wasn't apples-to-apples because YU03 rejects/rests
  orders YU02 would fill.)
- "What updates the replica while it's running?" — **Nothing, in YU03.** It's a one-shot fetch at
  boot; a change after startup is invisible until the next restart, and there's no versioning to
  even detect staleness. **That gap is exactly what YU04 fixes** — good segue.
- "Rejects are journaled — *all* of them?" — **No, only authoritative BLP-tier rejects.** A stage-1
  gateway pre-screen reject is thrown at the HTTP edge (`OrderMatcherService.screen()` →
  `RiskRejectedException`, line 128) **before** anything enters the ring — not sequenced, not
  journaled. Whether an order is journaled depends on *which tier* rejects it, not the reject
  reason: an over-limit order the gateway catches is gone at the edge; a marginally-over-limit one
  its lagging replica passes enters the ring and the BLP rejects it (journaled). The audit record
  is "every authoritative risk decision + control/reservation event," **not** a log of every 422.
  For 15c3-5 that's the right boundary. (Slide 8 says exactly this.)

**Live demo cheat-sheet.** (`scripts/proofs/yu03-risk-proof.sh`, one readable line per step)
```bash
# separate terminal:
kubectl port-forward -n traderx deploy/order-matcher 18110:18110 --context kind-traderx-state-014
bash scripts/proofs/yu03-risk-proof.sh controls      # one order per reject control
bash scripts/proofs/yu03-risk-proof.sh restriction   # restrict BAC live → rejected → un-restrict → flows
bash scripts/proofs/yu03-risk-proof.sh killswitch    # engage → halted → disengage → flows
```
Show the journal too (rejects are journaled): the order-matcher journal at
`/var/lib/traderx-lmax/journal`.

---

## YU04 — Durable Control Feeds

**What it is.** YU03 built the risk engine; YU04 makes its **risk universe** (the accounts and
securities it screens against) a **live, durable, event-sourced feed** instead of a one-shot boot
fetch. Source services (`account-service`, `reference-data`) publish control deltas from a
**transactional outbox** to per-source JetStream streams; order-matcher's gateway replica bootstraps
from a **watermarked snapshot + buffered deltas** and then consumes live. Same event-sourcing
discipline the order book already had, now applied to control state.

**Slide guide.**
1. Title — the risk universe becomes a live, durable, event-sourced feed.
2. Problem — YU03's one-shot fetch: never updated while running; stale-until-restart; no versions to detect it.
3. Design (transactional outbox) — business record + outbox row commit in **one transaction**; two independent durable JetStream streams.
4. Design (watermarked bootstrap) — ADR-019: subscribe+buffer → snapshot (epoch/version/count/checksum) → install → apply buffered deltas → live; readiness gated per source.
5. **Into the BLP (mechanism)** — the feed lands in the engine, in sequence (see below).
6. Live proof (the money slide) — the two green kind runs.
7. Fault isolation — per-source epoch/version/checksum; a bad feed quarantines and re-bootstraps *itself only*; auto-recovers; dedupe by message id.
8. Cost of durability — all off the hot path; BLP decision path / journal / snapshot formats unchanged.
9. Takeaway — no risk-relevant state depends on a pod staying alive.

**The mechanism (slide 5) — the elegant part.** The durable-feed delta handler in
`ReplicaBootstrap` makes the **exact same two calls** as the operator control plane:
`replicas.applyAccount(...)` (edge replica) + `engine.submitAccountControl(...)` (onto the BLP
input ring). **One sequenced sink, two sources** — the live JetStream feed and the human control
plane converge. So a source-of-truth change flows: source → outbox (1 txn) → JetStream → subscriber
→ [edge replica + BLP event] → applied by the single writer, in global order, no restart.
Code to point at: `ReplicaBootstrap.java:68–69` (identical to `RiskControlController`'s calls).

**Tidbits to mention.**
- The **before/after in one line:** in YU03 a *restart was the cure* for staleness (it re-fetched);
  the disease was everything between restarts. YU04 **inverts the trust model** — the replica is
  continuously correct while up (proof 1), and a restart/disconnect is just a catch-up event, not a
  re-sync gamble (proof 2). That's a stronger claim than "survives restart," and it's the one the
  spec actually makes (FR-IMRG04/05).
- **Transactional outbox = the feed can never diverge from the source of truth** (publish and
  business write are the same DB commit). This is the textbook pattern, worth naming.
- First state to touch `account-service` and `reference-data` persistence — everything through YU03
  was order-matcher-only.

**Caveats / sharp-question defenses.**
- "Does reference-data flip the kill switch?" — **No.** The durable feed carries account/security
  **existence/identity** (the universe). Operator **policy/restriction/kill-switch** changes still
  come through the `/risk/control/*` plane. Both land on the same sequenced sink — that's the
  slide-5 point — but they're different *sources*.
- The offline-catch-up proof is **not** "the change would be lost in YU03" — a YU03 restart would
  have re-fetched it. The honest YU03 contrast is *live-window* staleness (no update path while
  running). (Both slide 2 and the proof script were corrected to say this.)
- Implementation gotcha (if screen-sharing the API): `/risk/control/snapshot` returns `securities`
  as an **array of `{ticker,...}` objects**, not a ticker-keyed map — the quickstart's jq example
  is wrong. (Cost us a false "timeout" in the first proof run.)
- Minor known issue (won't show unless you tail logs): order-matcher logs
  `Risk replica bootstrap complete` at INFO every ~1s — cosmetic poll-loop noise, tracked in
  `issues/HANDOFF-issue-replica-bootstrap-log-noise.md`.

**Sharp-question defense: "how is this deterministic if gateways are horizontally scalable and
reject in parallel?"** (a likely mentor question — worth rehearsing.)
- **Determinism doesn't come from the gateways — it comes only from the single-writer BLP.** The
  gateways are deliberately *outside* the deterministic path (ADR-018). A gateway can only
  cheap-reject or *pass*; a pass carries zero commitment — "never final acceptance." It reserves
  nothing and owns no authoritative state.
- The classic hazard — two parallel gateways both see headroom and both admit orders that jointly
  bust a limit — is the exact scenario ADR-018 answers: both orders land on the **one** BLP input
  ring, get global sequence numbers, and the single thread does `decideAndReserve()` *in order* —
  first reserves the headroom, second is authoritatively rejected. Gateways being wrong in parallel
  is harmless because the BLP **linearizes**. Disagreement is measured
  (`traderx_gateway_blp_mismatch_total`), not silently unsafe.
- **Mental model:** gateway = branch predictor / speculative execution (fast, may be wrong, never
  commits); BLP = the retire/commit stage (one stream, authoritative, durable). Determinism is a
  property of the commit stage only.
- **"Stateless"? Soft-stateful.** Each gateway holds in-memory event-fed replicas, scalable *like*
  a stateless service because that state is non-authoritative soft state, rebuilt on boot from
  YU04's durable feed. The nice connection: the feed makes replicas *eventually* consistent across
  gateways — and "eventually consistent, at different watermarks at any instant" is *precisely why
  you can't trust them to decide*. The feed keeps them converging; the BLP is the linearization
  point that makes convergence safe.
- **Honesty note:** current build has a **single** gateway folded into the order-matcher edge
  (ADR-018: "Single Gateway in slice 1"; multi-gateway is FR-IMRG25, deferred). The horizontal-scale
  story is *designed-for and BLP-safe by construction* but not yet run with N gateways — say so if
  asked whether you've actually exercised it.

**Live demo cheat-sheet.** (`scripts/bench/yu04-*.sh`)
```bash
# separate terminal:
kubectl port-forward -n traderx svc/reference-data 18085:18085 --context kind-traderx-state-014
bash scripts/proofs/yu04-live-delta.sh       # inject a security → in the replica ~1s later, no restart
bash scripts/proofs/yu04-offline-catchup.sh  # scale order-matcher to 0 → inject → back up → present after bootstrap
```
Both take an optional ticker arg (default is `Z`+timestamp so reruns don't collide).
**First-run-on-a-fresh-cluster note:** if `account-service`/`reference-data` crashloop, it's the
DB-init/reference-data-env spec layer — fixed in specs now, but a pre-existing cluster may need the
hot-patch (see `issues/HANDOFF-issue-spec-layer-propagation-gaps.md`).

---

## YU05 — Post-Trade Compliance

**What it is.** The back office. Four capabilities bundled into one state, unified by one idea:
**the order-matcher journal is the source of truth, so every compliance obligation is a
deterministic, read-only projection of it.** Settlement lifecycle, reconciliation (journal↔projection
integrity), reproducible regulatory export, TCA, and real JWT auth/entitlements. **Strictly
downstream of the BLP** — never on the admission path, never mutating journal/BLP state (NFR-PTC09).

**Slide guide.**
1. Title — the journal becomes the compliance backbone.
2. Problem — booked-and-forgotten / a read model you had to trust / no reproducible audit / shared-token stopgap.
3. The spine — one journal-sourced fill; settlement/recon/regulatory/TCA are views over it; read-only, off the hot path.
4. Settlement — Processing → Settled (T+N), with the live force proof.
5. Reconciliation (money slide) — journal↔projection; the orphan sweep (11 local / 7 journal / 4 seed orphans).
6. Regulatory (money slide) — pure function of (range, seed); byte-identical across calls.
7. TCA — arrival / TWAP / slippage-bps; honest "computes where price history exists".
8. Auth — HS256 JWT + two entitlement axes + the zero-latency memory-only admission gate.
9. Takeaway — compliance-grade OMS; hand-off to YU06 (EOD).

**Tidbits to mention.**
- The **reconciliation orphan sweep is the standout demo:** it fingerprints the 4 DB-seed trades as
  `ORPHAN_IN_PROJECTION` because they have no journal event behind them — a live, visible proof that
  the system can detect read-model drift. 11 local rows, 7 with journal provenance, 4 orphans.
- The **regulatory export reproducibility** (identical SHA across two calls) is the event-sourcing
  payoff made auditable — answer a regulator byte-for-byte from the journal, not a mutable projection.
- **Real regs behind each:** T+N settlement (DVP lifecycle), reconciliation (books-and-records),
  regulatory audit trail, TCA (best-execution / MiFID II), JWT+entitlements (access control).
- The **entitlement gate can sit on the admission path** (FR-PTC42) — a memory-only JWT-principal
  check before screening, zero synchronous lookup. Default off so the token-less UI still works.

**Caveats / sharp-question defenses.**
- **TCA benchmark is null unless the security has captured price history.** Only META (`trd-09b-4`,
  19 samples) showed a real benchmark (502.90) and slippage (-102.31 bps); IBM/MSFT replayed trades
  returned null. Demo TCA on META and frame it as "computes where price history exists", not "every
  trade gets a benchmark". Also `arrivalPrice` is null even on META — don't put arrival price on a
  slide as a shown value; the TWAP benchmark + slippage are the real outputs.
- "Is settlement real DVP/clearing?" — **No.** It's a T+N *lifecycle state machine* (Processing →
  Settled) on the trade row, with a manual force. Real DVP/netting/clearing is future scope (see
  project-production-direction). Say so plainly.
- The 4 recon orphans are the **seed trades, by design** — the feature working, not a data bug.
- Auth codes (the real matrix): cross-account no-admin → **401**; account-scoped foreign trade →
  **403**; no bearer → **401**; own-account → 200; admin sees all → 200.

### Slide 7 deep-dive — TCA, TWAP, bps, and the three numbers

This slide is dense with finance terms; here's every piece, plain-English, so you can field
questions without hand-waving.

**TCA = Transaction-Cost Analysis.** After a trade executes, TCA measures *how good the execution
was* by comparing the price you actually got against a benchmark price. It's the quantitative answer
to "did we execute well, or did we leave money on the table?" Real-world drivers: **best-execution
obligations** — a broker must be able to show clients and regulators it sought the best result
(MiFID II RTS 27/28 in the EU; FINRA/SEC best-ex in the US). In YU05 it's **read-side only**, in
trade-processor, off the trading hot path (FR-PTC30/31) — it never calls the admission path.

**The benchmark: TWAP = Time-Weighted Average Price.** The average market price of the security over
a time window, weighting each price by how long it was in effect (so a price that held for 10
minutes counts more than one that held for 10 seconds). It's the standard "what was the market
doing while this order worked?" reference. YU05 computes it from a **PriceHistoryStore** fed by the
existing `pricing.*` tick feed — no new data source, no BLP involvement (FR-PTC32). A fill is judged
against the TWAP: beat it and you got price improvement; miss it and you paid up. (TWAP is one
common benchmark; VWAP — volume-weighted — is another, and is what YU08's execution algos target.)

**bps = basis points.** 1 bps = 0.01% = one one-hundredth of one percent. So 100 bps = 1%. Finance
quotes small price/rate differences in bps to avoid "is that 0.1% or 10%?" ambiguity. −102.3 bps ≈
−1.02%.

**Slippage.** The gap between the execution price and the benchmark, expressed in bps. It answers
"how far off benchmark did we fill?" The **sign convention in this code** (verified in
`TcaService.java`): *positive = worse than benchmark, negative = better.* It's **side-aware** — for a
Buy, paying above benchmark is bad (positive); for a Sell, receiving below benchmark is bad. The
code computes `diff = exec − benchmark`, then negates it for a Sell, so "bad" is always positive
regardless of side.

**The three bubbles on the slide (all from META trade `trd-09b-4`, a Sell):**
- **−102.3 slippage bps** — the headline. Negative ⇒ **better than benchmark = price improvement**.
  This Sell filled ~1.02% *above* the TWAP, i.e. we sold higher than the market's time-weighted
  average — a good outcome for a seller.
- **502.90 TWAP benchmark vs 508.04 exec** — the two prices being compared. Benchmark (what the
  market averaged) = 502.90; actual fill = 508.04. Selling at 508.04 vs a 502.90 benchmark is the
  favorable gap that produces the −102 bps.
- **19 price-history samples** — how many captured ticks went into the TWAP for that window. It's a
  confidence signal: more samples = a more trustworthy benchmark. **If a security has 0 samples in
  range, the benchmark (and slippage) come back `null`** rather than a fabricated number — which is
  why IBM/MSFT replayed trades showed null and only META (19 samples) computed. Honest by
  construction: no history, no guess.

**The worked arithmetic (for a mentor who wants to see it):**
`diff = 508.041 − 502.896 = +5.145` → Sell, so negate → `−5.145` →
`−5.145 / 502.896 × 10,000 = −102.3 bps`. Negative ⇒ favorable.

**Caveat to state:** `arrivalPrice` is `null` even on this trade — arrival price (the market price at
the moment the order arrived) needs the order-arrival tick, which isn't captured for these replayed
trades. So the *shown* outputs are the TWAP benchmark and the slippage; don't present arrival price
as a live value.

**Live demo cheat-sheet.** (`scripts/bench/yu05-*.sh`, share `yu05-common.sh`)
```bash
# separate terminal — trade-processor port-forward (order-matcher is via edge-proxy :8080):
kubectl port-forward -n traderx deploy/trade-processor 18091:18091 --context kind-traderx-state-014
bash scripts/proofs/yu05-auth-entitlements.sh        # JWT + entitlement matrix
bash scripts/proofs/yu05-regulatory-reproducible.sh  # two calls → identical SHA
bash scripts/proofs/yu05-recon.sh                    # matched + full-history orphan sweep
bash scripts/proofs/yu05-settlement.sh               # book pair → Processing(T+1) → force → Settled
```
Live-shape notes (vs quickstart): dev-token returns a **raw JWT string**; `/regulatory/report` is a
**top-level array**; **no `GET /trades/{id}`** (settlement state read from the MariaDB projection via
`kubectl exec deploy/database`); **no blotter endpoint** (discover trade ids from the report/DB).

## YU06 — EOD Price Production & Overnight Batch Chain

**What it is.** The first end-of-day state. After the close: produce official closing prices (last
trade price), gate them on data quality (stale/spike/missing), persist a versioned immutable
snapshot, publish a durable `EOD_PRICES_READY` gate event, and drive a downstream consumer
(position-service EOD P&L). A fail-safe event chain (NFR-EOD07), strictly downstream of the BLP
(NFR-EOD03). Producer in trade-processor, consumer in position-service.

**Slide guide.**
1. Title — the first end-of-day state.
2. Problem — no official closes / no overnight batch / no data-quality sign-off.
3. The chain — close → gate → immutable versioned snapshot → durable EOD_PRICES_READY → P&L.
4. Versioned immutable snapshot — re-run = new version; override = new version, prior immutable.
5. Quality gate (money) — stale/spike/missing → blocked → override → publish (live QLTY output).
6. Durable chain (money) — publish → gate event → eod_position_pnl + eod.pnl.done; **offline catch-up**.
7. Consumer halt — an account with an unpriced holding is held back, not guessed.
8. Discipline — off the hot path, event-sourced, idempotent.
9. Takeaway — the overnight back-office backbone; hand to YU07.

**Tidbits to mention.**
- **Quality gate is the money demo**: publication is blocked while ANY instrument is unresolved —
  even with auto-publish on. Override (with a reason) resolves it as a new version; prior versions
  stay immutable. Real regs behind it: signed-off official closes feed overnight P&L/NAV/margin.
- **The durable chain + offline catch-up** (NFR-EOD02): the consumer marks positions only against
  the exact published version (never live ticks), so two jobs can't disagree; and a consumer that
  was down when the event fired replays the retained JetStream stream on reconnect. We saw this
  live — see the "found live" note below.
- **Consumer halt** (FR-EOD32): fail-safe — an account holding a security with no published close is
  held back (`halted`), not marked with a guess.

**Caveats / sharp-question defenses.**
- "Are these real closing prices / real batch jobs?" — closing price = last trade price from the
  live feed (FR-EOD02); the downstream job is a mark-to-close **EOD P&L** (`eod_position_pnl`). Real
  DVP/clearing and a full overnight grid (VaR etc.) are future scope. Say so.
- **Flags don't occur naturally on a healthy feed** — the demo drives them via `EOD_UNIVERSE`: add a
  priceless ticker (QLTY) → MISSING (quality-gate); exclude a held security (NVDA) → consumer halt.
- **A trade-processor restart wipes the in-memory PriceHistoryStore**, so the first close after a
  restart flags many securities MISSING until the feed re-warms. The proof scripts warm up (poll-
  close until the flag count settles) before running the actual proof. If a mentor sees a wall of
  MISSING right after a config change, that's the warm-up, not a bug.

**⚠ Bug found live (2026-07-15) — now fixed.** position-service's `EodPnlConsumer` had **no NATS
env**, so on kind it connected to `nats://localhost:4222` (IOException, never subscribed) and the
EOD P&L chain never ran — **zero `eod_position_pnl` rows for any version**. Same class as the YU04
reference-data DB-env gap. Fixed durably: a YU06-layer `position-service-deployment.yaml` override
adding `NATS_BROKER_HOST=nats-broker` (mirrors the trade-processor producer), committed on YU06–YU09.
Silver lining: hot-patching it live showed the consumer **replay the retained stream and catch up** —
which is exactly the durability property slide 6 claims. If bringing up a fresh YU06 cluster from an
older commit, this env may be missing — `kubectl set env deploy/position-service NATS_BROKER_HOST=nats-broker`.

**Live demo cheat-sheet.** (`scripts/bench/yu06-*.sh`; the flag scripts are self-contained via
`kubectl` — no port-forward — and reset `EOD_UNIVERSE` when done)
```bash
# versioning + chain use the trade-processor port-forward (yu05-common):
kubectl port-forward -n traderx deploy/trade-processor 18091:18091 --context kind-traderx-state-014
bash scripts/bench/yu06-versioning.sh      # close→v1, close→v2, v1 immutable
bash scripts/bench/yu06-chain-e2e.sh       # publish → eod_position_pnl + eod.pnl.done
# quality-gate + consumer-halt drive EOD_UNIVERSE + restart trade-processor (self-contained):
bash scripts/bench/yu06-quality-gate.sh    # QLTY MISSING → blocked → override → published
bash scripts/bench/yu06-consumer-halt.sh   # NVDA excluded → account 10031 held back
```
Live shapes: `/eod/session/close` returns `{sessionDate,version,status,instrumentCount,flaggedCount,
instruments:[{security,closingPrice,quality,flagged,...}]}`; quality ∈ OK/STALE/SPIKE/MISSING/
OVERRIDDEN; a flagged session stays `DRAFT` (blocked) even with `EOD_AUTO_PUBLISH=true`. Config knobs:
`EOD_STALENESS_SECONDS` (300), `EOD_MAX_MOVE_PCT` (20), `EOD_UNIVERSE` (empty ⇒ priced securities).

## YU07 — Historical Tick Store

**What it is.** A data-infrastructure state (not an OMS feature). A new `tick-store` component
(Python/DuckDB): captures TraderX's own live ticks (`pricing.*` + trades) off NATS into columnar
Parquet partitioned by source/date/symbol, AND normalizes real NYSE TAQ (Consolidated Quotes) into
the *same* schema — streamed from the zip, no disk extraction — so one DuckDB query spans both.
Passive subscriber, zero hot-path impact (NFR-TS01/02).

**Slide guide.**
1. Title — market data as a first-class, queryable asset.
2. Problem — prices were ephemeral; no history for VWAP/TCA/VaR; synthetic-only.
3. The component — live capture + TAQ normalize → one Parquet schema (proven libs: DuckDB, unzip).
4. Unified query (money) — one query reads source=live + source=taq uniformly; VWAP across the store.
5. Live capture proof — 105 real cluster ticks captured off pricing.*, zero hot-path impact.
6. Takeaway — the data foundation for YU08 VWAP, YU05 TCA, future VaR.

**Tidbits to mention.**
- **Demoed differently from the OMS states** — no HTTP proof scripts. Two dependency-free proofs: the
  `pytest` self-check (14 tests, uses real TAQ sample rows) and `yu07-unified-query.sh` (one DuckDB
  query over live + TAQ). Plus `yu07-live-capture.sh` against the running cluster.
- **The unified `source` field is the whole trick** — `read_parquet(..., hive_partitioning=true)`
  over `source={live,taq}` partitions; VWAP/return/spread recipes in `duckdb_query_examples.sql`.
- **Zip-streaming** (`unzip -p … | ingest_taq_quotes.py`) keeps peak disk at one output partition,
  not the multi-GB decompressed CSV — the way to ingest the professor's ~650GB TAQ corpus.
- Real TAQ data (Feb+March 2025, OneDrive) is the intended corpus; normalizers are general-purpose
  against any conforming TAQ file (FR-TS09), not scoped to one dataset.

**Caveats / sharp-question defenses.**
- **TAQ *trades* (CT) normalizer — now implemented** (`ingest_taq_trades.py`, FR-TS08): a Consolidated
  Trades CSV normalizes to `event_type='trade'`/`source='taq'`, so a VWAP query weights real TAQ prints
  the same as live trades (the unified-query demo now shows this — VWAP(IBM) spans live + TAQ trades).
  Honest caveat: **SC-TS06's real-file verification is still pending** — tested against a
  format-accurate CT sample, not the actual `taq_trades_*_csv.zip` (still a 0B OneDrive placeholder);
  the normalizer is general-purpose against any conforming CT file (FR-TS09) and needs no change to
  ingest it.
- **GCS write needs the HMAC secret** (`tick-store-gcs-hmac`) for `gs://traderx-501015-tick-store`.
  The demo proves the mechanism to a local dir without it (`TICKSTORE_OUT_DIR=/tmp/...`).
- Capture is a passive broadcast subscriber — killing it causes **no** back-pressure on any publisher
  (that's the point). Don't over-claim throughput; it's off the hot path by design.

**⚠ Bug found live (2026-07-15) — now fixed.** `capture.py` read the price fields at the top level,
but the live `pricing.*` message is the standard TraderX envelope
`{"topic":"pricing.IBM","payload":{"ticker","price","asOf",...}}` — so **every live tick KeyError'd
on `'asOf'` and was skipped (0 rows captured)**. The unit tests fed the flat inner shape, so they
never caught it. Fixed with `envelope_payload()` (unwrap; pass-through if already flat) + a
regression test; verified live (105 ticks, 20 symbols). Committed on YU07–YU09. Same class as the
YU06 NATS-env bug — an integration gap only visible against the live cluster.

**Live demo cheat-sheet.** (tick-store component + `scripts/bench/yu07-*.sh`)
```bash
# self-check (no cluster / GCS / TAQ file needed):
cd specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python3 -m pytest tests/ -q            # 14 passed

bash scripts/bench/yu07-unified-query.sh   # live + TAQ, one schema, one DuckDB query (dependency-free)

# live capture off the running cluster:
kubectl port-forward -n traderx svc/nats-broker 4222:4222 --context kind-traderx-state-014
bash scripts/bench/yu07-live-capture.sh 25 # ~105 real ticks → local Parquet → DuckDB readback
```
Live shapes: `pricing.*` payload is `{topic, payload:{ticker,price,openPrice,closePrice,asOf}}` (unwrap
it); subjects are `pricing.<SYM>` (JSON) and `pricing-tick-bin.<SYM>` (binary/SBE — capture uses the
JSON one). `macOS has no timeout` — the capture script uses background+SIGTERM instead.

## YU08 — Execution Algo Engine (TWAP/VWAP parent orders)

**Headline.** A large order was one market-moving print; YU08 adds a parent-order execution layer —
TWAP (equal time buckets) and VWAP (volume-weighted buckets) — in a new `execution-algo-engine`
service (:18120). Two things make it production-shaped: (1) **no privileged lane** — every child goes
through order-matcher's same `POST /orders` + YU03 risk gateway as a manual order (NFR-AE02); (2) it's
**event-sourced** — every parent transition appended to its own `TRADERX_ALGO_ENGINE` JetStream
stream before memory, replayed on restart (FR-AE07/08). VWAP is the **first consumer of YU07's tick
store** (`duckdb` source), with a synthetic U-curve fallback so it never blocks (FR-AE09).

**⚠ Two stale-artifact traps found live (both from `--skip-build`, both now cleared):**
1. **`tick-store` ImagePullBackOff** hung `start-state`. The only local `tick-store:state-yu07` image
   was the **amd64** registry copy; this kind node is **arm64** → `kind load` "succeeds" but
   containerd/CRI won't surface a wrong-arch (or bare-OCI) image, so kubelet fell back to pulling
   from docker.io → denied. **Not needed for YU08** (VWAP reads Parquet from GCS directly; YU07 data
   is durable in GCS) → scaled `deploy/tick-store` to 0, which makes it Available instantly and
   unblocks the rollout wait. To bring it back for a YU07 live demo, rebuild its image locally (arm64).
2. **`execution-algo-engine` "Running/Ready" but API dead.** `--skip-build` reused a **Jul-10 jar**
   predating the committed fix (679721c) for `OrderUpdateSubscriber` — that old build subscribed to
   the NATS subject `/accounts/*/orders`, which jnats rejects (`Subject wildcard improperly placed`,
   these subjects are one literal token, not `.`-delimited), crashing Spring's context so Tomcat never
   opened 18120. **It showed Ready anyway because the deployment has no readiness probe** (→ YU08
   slide-7 bullet + YU09 ops work). Fix was purely to rebuild: `./gradlew bootJar -x test` → `docker
   build` → `kind load` → restart. The correct code subscribes to catch-all `>` and filters client-side.

**Live demo cheat-sheet.** (proofs in `scripts/bench/yu08-*.sh`, self-managing port-forward on 18120)
```bash
# unit tests (no cluster):
cd specs/YU08-execution-algo-engine/generation/runtime-overrides/execution-algo-engine && ./gradlew test

bash scripts/bench/yu08-twap-e2e.sh        # TWAP → equal buckets → children via order-matcher → filled → COMPLETED
bash scripts/bench/yu08-vwap-e2e.sh        # VWAP [136,92,70,70,92,140] ≠ flat [100×6], then runs weighted order → COMPLETED
bash scripts/bench/yu08-crash-recovery.sh  # kill pod mid-run → replay from JetStream → 6 unique children, no re-submit
```
Verified live: TWAP 300→[100,100,100], children `ord-013-00xx`, filled, COMPLETED. VWAP U-curve.
Crash: 3/6 before kill → children kept → COMPLETED 6/6 unique. Perf (spec TD-AE01, measured):
VWAP `duckdb` full-store glob **~224s** (AAPL 22.4M trade rows) → scoped per-day paths + per-symbol
cache **~45s cold / 0.000s cached**. `ALGO_VOLUME_PROFILE_SOURCE=synthetic` by default; set `duckdb`
to hit YU07's GCS store. **Gotcha:** the engine has no readiness probe, so a fresh pod reports Ready
before Tomcat listens — the bench scripts respawn the port-forward until the API answers.

## YU09 — Ops Hardening (secrets · journal archival · stale-jar guard · DR runbook)

**Headline.** First state that adds no feature — it makes YU02–YU08 safe to operate. Four gaps:
(1) **Secrets, fail-closed** — DB + JWT/dev-token creds via `secretKeyRef` into `mariadb-credentials`
/ `auth-secrets`; Secrets are required (missing → `CreateContainerConfigError`, no dev-default
fallback; NFR-OH04). (2) **Bounded + durable journal** — rotate the active journal to an immutable
segment at each snapshot boundary, upload segments to GCS via HMAC; fail-safe (never delete an
unconfirmed segment, FR-OH23), off the journaler thread (NFR-OH02), off by default zero-cost
(NFR-OH01). (3) **Never ship a stale jar (FR-OH30)** — `gradlew --no-daemon clean bootJar` before
every `docker build` in the publish pipeline. (4) **DR runbook (FR-OH40)** — pod/node/zone/MariaDB
loss on the real single-zone topology + observed RPO/RTO.

**Bring-up gotchas (both hit live):**
1. **Secrets FIRST** or DB/order-matcher/trade-processor/account-service/position-service pods sit in
   `CreateContainerConfigError`. `kubectl create secret generic mariadb-credentials … auth-secrets …`
   (dev values in quickstart) before start-state; a `--recreate-cluster` wipes them.
2. **Local start-state does NOT build JVM jars.** All 6 JVM services use single-stage
   `COPY build/libs/*.jar` Dockerfiles; the *publish* pipeline builds jars (FR-OH30) but the local
   harness doesn't → fresh `generate` + bring-up fails `COPY build/libs: no such file` on the first
   JVM image. Fix: `for d in order-matcher account-service position-service trade-processor
   trade-service execution-algo-engine; do (cd generated/code/target-generated/$d && ./gradlew bootJar
   -x test); done` then re-run start-state. (Real follow-up: make those Dockerfiles multi-stage, or
   teach the local harness to bootJar first — the same fix FR-OH30 applied to publish.)

**⚠ Found live (both flagged, NOT YU09 regressions):**
- **FR-OH04 scope gap (fixed during prep):** SC-OH03's shipped test only scans the 5 rendered
  deployments, so `cluster-addons/yu03-staging/{database,order-matcher}.yaml` still held 5 literal
  `traderx` creds (+ literal `-ptraderx` in the DB probes). Closed: converted to `secretKeyRef`
  (mariadb-credentials), probes to `-p"$MARIADB_PASSWORD"`, and extended
  `scripts/provision-yu03-staging-secret.sh` to create `mariadb-credentials` in the staging ns.
  **Uncommitted tracked-file change; cluster-addons is shared across branches → propagate on commit.**
- **account-service `/account/control-snapshot` → HTTP 500 `Incorrect result size: expected 1,
  actual 0`** on the fresh DB → order-matcher risk-replica bootstrap keeps admission CLOSED (no live
  orders). A fresh-DB seed gap (account-service connects to its DB fine), not a YU09 change and not a
  blocker for the YU09 proofs — journal rotation is driven by the internal SNAPSHOT event on the
  input ring, independent of order admission. Root-cause separately if live order flow is wanted.

**Live demo cheat-sheet.** (proofs in `scripts/bench/yu09-*.sh`)
```bash
bash scripts/bench/yu09-no-literal-creds.sh   # 5 Deployments + STS: 0 literals, all secretKeyRef (SC-OH03)
bash scripts/bench/yu09-stale-jar-guard.sh    # gradlew clean bootJar (L2336) before docker build (L2340) (SC-OH06)
bash scripts/bench/yu09-dr-runbook.sh         # pod/node/zone/MariaDB + RPO/RTO sections present (FR-OH40)
bash scripts/bench/yu09-journal-rotation.sh   # LIVE: 20s snapshot → new immutable segment, active reset, fail-safe (SC-OH04)
```
Journal-rotation proof sets `SNAPSHOT_INTERVAL_MS=20000` + restarts order-matcher, watches the pod's
`/var/lib/traderx-lmax/journal` for a new `input-events-<epoch>.journal`; resets interval after (I
reset the live deploy to 300000 post-demo). Verified live: active journal 10.3MB → rotated to two
immutable segments, active reset to 1,280 bytes; archiver WARN keeps segments on disk (no GCS HMAC).

## YU10 — FIX Order-Entry Ingress (the counterparty front door)

**Headline.** YU02–YU09 built a hardened OMS whose *only* order-entry surface was a bespoke REST
API — nothing a real trading partner speaks. YU10 adds the industry front door: a standard **FIX
4.4** session, terminated by an **in-process QuickFIX/J acceptor inside the order-matcher** (port
`:18130`, no separate gateway, no internal hop), that translates each message onto the **same
multi-producer input ring REST uses** — same risk screen, same journal, same DB projection, same
recovery. Bit-identical to a REST order from the ring onward. The matching core is untouched.

**The four pillars (ADR-034…037):**
1. **In-process acceptor (ADR-034).** QuickFIX/J owns the hard session layer — logon, heartbeats,
   sequence numbers, resend windows, persistent stores — the reference engine, not a hand-rolled
   subset. The session thread enqueues onto the ring; the ExecutionReport handler is enqueue-only on
   the ring thread, so the **no-GC hot-path gates stay exact-zero with FIX active**.
2. **Fail-closed identity (ADR-036).** JWT rides in `Password(554)` at logon, resolved **once**
   through the *same* entitlement gate the REST path uses; `SenderCompID` must be in a committed
   allowlist bound to one account (kind demo mapping `BENCH01:11413`). Unmapped CompID or bad/absent
   token → rejected at logon, never reaches the application. A brand-new surface has no installed
   base to protect, so it starts **closed regardless of the REST flag**. Port is cluster-internal
   (TLS/mTLS is the recorded gate before any public exposure).
3. **Durable correlation ledger (ADR-035).** A `ClOrdID → internal orderRef` ledger on the PVC,
   joined on a field every event already carries — no wire-format or core change. Rehydrates on
   restart; a re-used `ClOrdID` is rejected as a duplicate, not re-executed.
4. **Async lifecycle + four-outcome honesty (ADR-037).** In: `D`/`F`/`H` (NewOrderSingle,
   OrderCancelRequest, OrderStatusRequest) → the same internal commands REST produces. Out:
   ExecutionReport (New/Fill/Canceled/Rejected, risk reason in `Text(58)`) + OrderCancelReject,
   async and ordered per order. A post-publish timeout sends **nothing** — the eventual
   ExecutionReport is the outcome, and `OrderStatusRequest` lets a counterparty recover any order's
   state. The journal stays the authoritative fill record.

**Live proof (measured, matched-methodology):**
- **5,213/s** FIX completed lifecycles on **one** session vs **3,479/s** REST in the same window
  (256 connections) — FIX ~1.5× on a single connection, dissolving REST's per-order round-trip
  ceiling with the async session model.
- **77,055** FIX orders in 10 s → the DB projection grew by **exactly** that (FIX/REST equivalence
  checked to the row: same blotter, risk state, journal, DB).
- **336,575-entry** correlation ledger rehydrated across a pod restart; re-used ClOrdIDs correctly
  rejected. **148 tests green**, zero-allocation gates exact-zero with FIX active.

**Framing honesty (slide-7 lesson):** the completed-lifecycle ceiling is the **shared
single-threaded output/report path** (the LMAX single-writer principle applied to *output*) — a
throughput tuning target, not a correctness gap. And the REST "ceiling" claim is design-true (two
internal experiments — async MVC, microbatching — both *regressed*), not a strawman.

## YU11 — Aeron + SBE BLP Replication — presenter knowledge base

*(This is the answer-the-questions material behind the slides, not a restatement of the bullets.
One-line headline: YU11 rebuilds only the HA replication leg on Aeron + SBE — ~49× faster,
zero-copy/zero-GC — and proves a wiped replica can rejoin across a leader change and promote again.)*

### Concepts & terms (to field questions)

- **Sidecar.** A Kubernetes pod can hold more than one container; they share the pod's network
  (reach each other on `localhost`) and can share volumes. The *main* container is the order-matcher
  JVM; a **sidecar** is a helper container beside it. Here the sidecar runs Aeron's transport so the
  JVM only talks to `localhost` and the sidecar owns the network + disk.
- **Archiving Media Driver.** Two Aeron pieces in that sidecar. **Media Driver** = Aeron's transport
  engine: it does the actual UDP send/receive and owns the shared-memory log buffers (the app never
  touches sockets — it hands messages to the driver over shared memory). **Archive** = Aeron's
  durable-log service: it *records* the stream to disk as it flows and can *replay* it from any
  position. So the sidecar both moves the bytes and records them for a recovering node to replay.
- **64-byte SBE.** Simple Binary Encoding (a FIX-community binary format). Each input event is a
  **fixed 64-byte record** with every field at a known offset (`inputSeq`, `eventTimeMillis`,
  `limitPx`, `priceTicks`, `orderRef`, `accountId`, `securityId`, `qty`, `leaderEpoch`…). Fixed size
  = no length-prefix/parse, fixed layout, and 64 B is one CPU cache line (whole message in one fetch).
- **`tryClaim` buffer + no intermediate copy (zero-copy).** Aeron owns the internal "log buffer" (the
  memory that ships over the wire). `offer(myBuf)` builds the message in *your* buffer then **copies**
  it into Aeron's = two writes. `tryClaim(len, claim)` instead **reserves a slice inside Aeron's own
  log buffer** and hands you a pointer; you SBE-encode the event **directly into that slice**, then
  `commit()`. The bytes are written **once, in their final on-the-wire location** — no staging buffer,
  no memcpy. That's what keeps the hot path allocation-free.
- **MDC (Multi-Destination-Cast).** Normally one publication → one receiver. MDC lets **one**
  publication fan out to several destinations (`control-mode=manual` = app adds them explicitly:
  local Archive `:40127`, peer follower `:40123`). Because it's *one* publication, every destination
  gets the **identical** stream with the **same session id and byte position** — the single shared
  position is exactly what makes recovery's `ReplayMerge` line up (see slide-5 deep-dive).
- **Durable ACK.** The follower ACKs a replicated event **only after it's forced to the follower's
  journal** (`journaledSeq` advances) — "acknowledged" never means "still only in the follower's
  memory" (SC-AR04). That's the difference between a real durable replica and a queued one.

### The "replaces only the replication leg" line, decoded

The order-matcher has several data paths; one — the **replication leg** — ships every input event to
the standby and waits for its **ACK** (a second durable copy = what HA *is*). YU11 changes **only that
leg's transport**: **`BLP_REPLICATION_TRANSPORT=nats|aeron`** picks it with one env var (no code
change); **NATS-authoritative shadow comparison** lets both run at once with NATS as source-of-truth
and Aeron in parallel so you diff them byte-for-byte before trusting Aeron; **one-value rollback** =
flip the var back to `nats` to revert, no rebuild. **Matching / risk / journal / CQRS untouched** =
the matching engine, pre-trade risk screen, on-disk journal, and CQRS read-model projection are all
unchanged — only *how a copy reaches the standby* changed. Low-risk by construction: one pipe, flag-
selected, reversible, everything correctness-critical held constant.

### The two throughput numbers (keep them distinct — common confusion)

| Number | What it measures | Ceiling it exposes |
|---|---|---|
| **520,520 events/s** | *just the replication pipe* — primary SBE-encode → publish over Aeron MDC → follower durably journals + ACKs, on the isolated `AeronReplicationPhase0Test` (prod MDC-UDP, mean of 3×30 s, ±2%) | the transport itself |
| **25,149 booked/s** | *the whole order lifecycle* on GKE — REST → match → journal → DB projection | the **edges** (REST ingress, DB projector) |

`520,520` is **not** booked orders/s. Its *job* is to prove the replication pipe is no longer the
ceiling — it can move ~20× more events/s than the full system can feed it, so HA stops stealing
throughput. (Under NATS at 10,561/s the pipe *was* near the system ceiling — that was the "HA tax.")
Same-day journaling control ran 2.72M/s = further headroom. Transport threads allocate exact-zero
(retained Aeron gate: 250k warm-up + two 1M-event exact-zero windows); 178 tests + `noGcTest` green.

### Slide 5 deep-dive — cross-epoch recovery

- **Epoch** = one term of leadership; it increments on every leadership change. Each epoch's Aeron
  **recording** (Archive's on-disk copy of the stream) starts when that leader took over — so a later
  epoch's recording does **not** contain earlier epochs' history (that lives on the dead pod's disk).
- **The hard problem.** A **replacement** pod boots with an **empty disk** (fresh PVC) and needs the
  *entire* history, but the current leader's recording only starts partway through. Replaying "newest
  recording from its start" yields a node that *looks* healthy while silently missing everything
  before the last leadership change. Naively that means **one-shot failover** — no fresh replacement
  can ever safely join after the first promotion.
- **The fix — a boundary-stamped snapshot bundle.** The promoted primary packages a **snapshot** (full
  engine state: book, counters, risk) + short journal tail, **stamped with an exact boundary**
  `(leaderEpoch, inputSeq, Archive position, session)` and a **checksum** (corrupt bundle → rejected,
  not silently loaded). The joiner: (1) installs it **crash-safely** (dies mid-install → doesn't come
  up half-loaded); (2) replays **zero local tail** — it *has* the snapshot, so nothing extra to apply;
  (3) **`ReplayMerge`** — Aeron replays recorded history from the boundary then seamlessly merges into
  the still-advancing **live** stream, no gap, no double-apply (works *only* because MDC gave the
  Archive recording and live stream the same session+position); (4) reaches **Ready with matching
  order/read-model hashes on both nodes** — independent hashes are equal = bit-identical replica,
  not "roughly caught up."
- **Live trace decoded.** `empty PVC → bundle at marker 10492 / Archive pos 77568 → snapshot(@64) →
  2/2 Ready → primary force-deleted → follower promoted at epoch 5 → replacement rejoined`: a wiped
  node installed a bundle complete at input-seq 10492 / byte-pos 77568, loaded snapshot at pos 64,
  both pods `2/2 Ready`, primary `kill -9`'d, the bundle-recovered node **promoted** (epoch→5), and
  yet another empty replacement joined — rejoin **and** a second failover. One-shot limit gone.
- **Why the journey line matters (the honest part).** *Split-publication dead-end*: the first design
  used two separate publications (Archive, peer) → two **independent** byte-positions that drift, so
  `ReplayMerge` can't align "pos 384 in the Archive" with "pos 512 live" — they were never one stream
  (8 patches couldn't fix a structural mismatch). *MDC pivot*: collapse to one MDC publication →
  Archive and peer share one session+position → replay and live are the same ruler → merge converges.
  *Lineage-continuous sequencing*: the business sequence is counted **across the whole leader lineage**
  (epoch 4 ends 158 → epoch 5 starts 159), not reset per boot or tied to the in-memory ring — which is
  what lets a node cut its journal at an exact boundary and *prove* "I have everything through 158, new
  stream starts 159, no gap."

### Slide 6 deep-dive — the `nextOrderRef` bug

- **What it is.** Every new order gets a unique ID from a monotonic counter (`ord-013-0008`). A reused
  order ID is a serious correctness failure — two different orders under one identifier corrupts audit,
  settlement, and the client's own books.
- **The contract it violated.** The engine's safety model: *state is rebuilt by replaying events*, so
  anything that must survive a crash has to be **part of the replayed/snapshotted state**. The counter
  was a plain atomic **outside** that state, and two rebuild paths never touch it: (1) **journal replay
  on boot** re-applies `ORDER_NEW` to refill the book but doesn't advance a counter it isn't derived
  from; (2) **follower injection** — a warm standby applies the primary's replicated `ORDER_NEW`s, same
  thing.
- **The collision.** After recovery/promotion the book holds orders up to #12 but the counter still
  reads "next = 8" → it hands out **#8 again**. The diagnostic `12 orders warm, nextRef 8` is exactly
  that: 12 orders present, counter at 8 (should be ≥13). Hidden until now because other paths kept the
  counter roughly in step; the **zero-tail bundle install** (slide 5) stripped those away and surfaced
  it cleanly.
- **The fix (principle).** Put the generator **inside** the replicated, snapshotted state, and on
  recovery **assert it exceeds every ID ever issued** — not merely every ID still in memory (terminal
  orders get evicted, so "max retained" is unsafe). Generalizes to the slide's closing line: **snapshot
  completeness** must cover *counters, ID generators, idempotency keys, risk reservations, symbol
  identity, control versions* — every generator of future output, not just the visible book.

### Honest boundaries (do NOT overclaim)

- **Failover here is still Lease-based on the k8s control plane** — default target 3 s p95; fast-witness
  (30–60 ms) was **not** measured end-to-end; under kind (no fast-witness) promotion took ~17 s. YU11
  did *not* make failover fast.
- Correct HA rested on a lot of **bespoke machinery** (Lease election, fast-witness CAS on NATS KV,
  bundle transfer, journal-cut bootstrap, lineage sequencing) — worth naming as the real cost.
- **Nothing pushed.** GKE ran `83049c8` (pre-recovery-slices) for the 25,149 parity number; the
  cross-epoch recovery was proven on the dedicated kind cluster.

## YU12 — Aeron Cluster BLP Consensus — presenter knowledge base

*(One-line headline: YU12 replaces YU11's hand-built HA with Raft consensus — the matching engine
runs as a replicated state machine across 3 members, elects leaders off the k8s control plane in
sub-second, makes split-brain structurally impossible, and dissolves the ID-reuse bug by
construction.)*

### Concepts & terms (to field questions)

- **Aeron Cluster / `ClusteredService`.** Aeron's framework for running a **deterministic state
  machine replicated across N members** by Raft consensus. Your business logic implements the
  `ClusteredService` interface (callbacks: `onSessionMessage`, `onTakeSnapshot`, `onRoleChange`,
  `onTimerEvent`…); the framework's **Consensus Module** handles election, log replication, and
  commit ordering, and the **Archive** stores the log + snapshots. Here the inherited
  `MatchingEngine` runs **unchanged** as the clustered service.
- **Raft.** The consensus algorithm underneath. Members elect a **leader** by majority vote; the
  leader appends inputs to a **replicated log**; an entry is **committed** once a majority have it;
  committed entries are applied in the same order on every member. Two consequences we lean on: (1)
  a **partition minority can't win a majority vote**, so it can't elect a leader or extend the log —
  *split-brain is structurally impossible*, no witness/fencing needed; (2) election happens **inside
  the cluster**, not via the k8s control plane.
- **Consensus-log ingress.** Every input (orders, cancels, price ticks, control updates) enters
  **only** as a committed log message — no side channels. Each member then **applies the identical
  committed sequence single-threaded** through the same engine + two-tier risk. Determinism +
  identical input order ⇒ identical state on every member.
- **`onTakeSnapshot` bound to a log position.** A snapshot captures the *complete* deterministic
  state at *exactly one* applied log position; recovery loads the newest snapshot and resumes
  applying the log strictly after that position (re-applies nothing, skips nothing).
- **Empty-disk rejoin as a primitive.** A replacement member with a wiped volume rejoins by pulling
  the latest snapshot + replaying the log tail from the cluster — the framework does it. This is the
  thing YU11 hand-built in five slices; in the cluster it's built in.
- **`emptyDir` vs PVC (a deliberate choice).** Members run on `emptyDir` (node-local scratch), not a
  persistent volume. Rationale: **Raft quorum IS the durability** for member loss (a lost member
  rejoins from the majority), and `emptyDir` gives fast rejoin with no PVC detach/reattach minutes.
  The trade: a *simultaneous whole-cluster* loss forfeits the un-snapshotted tail — so periodic
  snapshots + (optionally) a PVC are the belt-and-suspenders for all-three-at-once loss.

### The two failover numbers (say both, honestly)

- **System-facing: 653–716 ms across 5 kills** — leader `kill -9` to the new leader logging its
  role change. Measured node-clock-precise: the harness `kubectl exec`s into the leader, prints a
  millisecond timestamp, and kills the JVM in one shot (no pod-delete API latency, no poll latency,
  NTP-synced). This is the honest headline: **consistent sub-1s, off the control plane, 6× better
  than the 12 s Aeron default.** ~35 leader kills total, always Raft-internal, zero ID reuse.
- **Client-observed: ~200 ms best, but a bimodal ~0.85–1.6 s floor.** Do **not** claim consistent
  client-<1s. That floor is the *test client's own* reconnect churn — it cycles endpoints and burns
  a 1 s connect timeout on the dead one before trying the live leader. It's a client artifact with a
  known fix (native `AeronCluster.newLeaderEvent` leader-tracking), not a cluster property. NFR-AC03
  ("client failover <1000ms") is therefore met best-case, not at the consistent floor — say so.
- The tuning journey (good story): Aeron defaults **12.0 s** → tuned 100/1000/500 **~2.0 s** →
  final 100/400/200 **653–716 ms**. Earlier "sub-1s impossible" readings were all measurement
  artifacts (API-proxy read skew, killing so fast you lost quorum, the client reconnect churn, PVC
  detach minutes) — each root-caused, which is *why* `emptyDir` + node-clock measurement exist.
- **The FIX/REST gateway survives the leader change.** REST + FIX terminate on a gateway tier (one
  owner-thread cluster client), not in the BLP — so a leader kill doesn't drop the counterparty
  session; the next REST POST is served first try. (This is the decoupling YU10's in-process
  acceptor couldn't give.)

### Throughput (past the bar; consensus was never the bottleneck)

- **Pipelined gateway: 28,860–35,714 submit/s committed, 45,684–135,834 booked/s** — three stable
  runs, zero failures, zero reuse. Meets + exceeds NFR-AC02 (≥ 25,149 booked/s). `booked/s` is read
  from the engine's **authoritative trades counter** (gateway fill counters under-count by design
  when egress drops).
- **The apply path had headroom:** Raft sustained an 18.6k/s cascade and replayed a 740k-event log
  in seconds. The ceiling was the gateway's **one-order-at-a-time** submit (per-order committed-ack
  ~1.2 ms ⇒ ~800–1,100/s single-order). Pipelining (offer the whole batch into the log, count acks
  as they stream back FIFO) took a 200-order batch from ~250 ms → **38 ms**. Same lesson as every
  prior state: **per-order ingress is the ceiling, not the BLP.**

### The four flood bugs (the credibility slide — know the poison pill cold)

Only a real flood surfaces these; all are **deterministic-replication** bugs (identical on every
replica, survive restart), root-caused from SIGQUIT thread dumps and fixed for good.
1. **Output-ring self-deadlock — the poison pill.** The service thread is **both** producer (the
   engine emits output during `apply`) and consumer (it drains the output ring after `apply`). A
   price tick that mass-executes the resting book produced a cascade larger than the ring, so
   `RingBuffer.next()` **parked forever *inside* apply** — on all three members. Because the trigger
   is a *committed log entry*, it wedged **replay** too: a rolling restart came back **leaderless**,
   every member stuck at the same log position. Fixed with a backpressure-drain claim in a YU12
   `OutputPublisher` override (`tryNext` + drain-inline-and-retry): an unbounded cascade now flows
   through a bounded ring. The poisoned 1.4M-event log then replayed clean, releasing ~1.27M trapped
   fills.
2. **Egress emission throttled the state machine.** An undeliverable ack retried 1000× (~1 s each)
   collapsed apply to ~1 event/s — one slow client starved everyone. Now 20 attempts, sub-ms; slow
   clients get **drops**, never the state machine's time.
3. **Rolling-restart tail loss (`emptyDir`).** k8s readiness didn't gate on catch-up, so a
   `rollout restart` could kill members before replication finished and lose the un-snapshotted
   tail. Fix: `/ready` returns 200 **only when applied is within `CLUSTER_READY_MAX_LAG` (5000) of
   the furthest-ahead peer**. Proven across a rollout mid-flood: no state regression (trades
   9.18M → 35.14M monotonic), 0 reuse.
4. **Gateway self-eviction under load.** All 8 HTTP threads parked on batch futures starved the
   readiness probe → k8s pulled the gateway from the Service mid-bench. Pool now 64 + heap 1g +
   CPU 2.

### Honest boundaries (do NOT overclaim)

- **Three members on two nodes today** (C2_CPUS quota = 8). Pod-kill failover is fully proven;
  true one-member-per-node node-fault tolerance needs a quota bump (8 → 12).
- **Snapshots are periodic (60 s).** A snapshot barrier stalls the service thread ~8 s each; at a
  30 s interval that was ~25% tax, so the interval moved to 60 s. The real fix is async snapshotting.
- **`emptyDir`** forfeits the un-snapshotted tail on a *simultaneous whole-cluster* loss (Raft
  quorum covers member loss, not all-three-at-once). PVC is the optional belt-and-suspenders.
- **Nothing pushed; `risk.entitlement.enforced` stays false.** GKE ran the cluster live
  (`traderx-lmax`, blp-pool=2 c2-standard-4); kind cluster `traderx-yu12-cluster` idle locally.

## Standing presentation lessons (apply to every state)

- **No unmeasured performance numbers on a slide** — claim design properties (0 DB calls, in-seq,
  journaled) unless you actually benched it. (The YU03 slide-7 lesson.)
- **Draw the contrast against the real prior behavior, not a strawman** — the mechanism was always
  right; twice the *comparison* overclaimed (YU03 throughput, YU04 "lost on restart"). Check the
  previous state's actual behavior before saying "X would fail in YUn-1."
- **Verify API response shapes against the live endpoint, not the docs** before scripting a demo.

---

## Guide — running & demoing each state (YU02–YU12)

Copy-paste reference. All on kind (`kind-traderx-state-014`, ns `traderx`, UI **http://localhost:8080**).
Bring-up runs from each state's own worktree root; `generate-state` writes the `start-state` script under
`generated/code/target-generated/scripts/`. Add `--skip-build` on reruns when code is unchanged (but mind
the stale-image traps below), `--recreate-cluster` to wipe a conflicting leftover cluster (this also wipes
Secrets — re-create them for YU09). Demo scripts are collected under `scripts/demo/yuXX-*.sh` in every
worktree. Use `localhost`, never `127.0.0.1`.

### YU02 — LMAX Kubernetes

**Bring-up**
```bash
bash pipeline/generate-state.sh YU02-lmax-kubernetes
bash generated/code/target-generated/scripts/start-state-YU02-lmax-kubernetes-generated.sh \
  --provider kind --without-sail
```
**Demo/proof.** No `yu02-*.sh` script — YU02's demo is the HA slide deck + the load-test `.mjs` bench (see
`scripts/bench/`, and the `bench-compare` flow) plus the live prod site. Quick liveness / failover:
```bash
curl -so /dev/null -w '%{http_code}\n' https://yaakovseif.dev          # 200 (prod, single-BLP)
kubectl delete pod order-matcher-0 -n traderx                          # HA/STS: journal replay
kubectl logs -f order-matcher-0 -n traderx                             # watch "LIVE RECOVERY [journal]"
```
**What to look for / explain**
- The gap: a synchronous DB-write-per-order caps throughput. Mechanism: order-matcher *becomes* the LMAX
  BLP (Disruptor ring, single writer, journal + snapshots), DB drops to an async CQRS read-model.
- Evidence: the bar chart — core ≈ 6M ops/s, single-BLP end-to-end ≈ 42k booked/s, HA ≈ 22k/s. Point:
  the bottleneck is the edges (REST + DB projector), never the engine — the framing for every later state.
- Resilience story: lease-starvation false-demote root-caused and fixed (demote-on-proof + pod-GET fast
  path); 5 saturating runs, zero false-demotes, ~2–3s real-kill failover.

### YU03 — In-Memory Risk Gateway

**Bring-up**
```bash
bash pipeline/generate-state.sh YU03-in-memory-risk-gateway
bash generated/code/target-generated/scripts/start-state-YU03-in-memory-risk-gateway-generated.sh \
  --provider kind --without-sail
```
**Demo/proof** (separate terminal for the port-forward the script expects)
```bash
kubectl port-forward -n traderx deploy/order-matcher 18110:18110 --context kind-traderx-state-014
bash scripts/demo/yu03-risk-proof.sh controls      # one order per reject control
bash scripts/demo/yu03-risk-proof.sh restriction   # restrict BAC live → REJECTED → un-restrict → NEW
bash scripts/demo/yu03-risk-proof.sh killswitch    # engage → halted → disengage → flows
```
**What to look for / explain**
- The gap: YU02 matched any valid-ticker order; the only "limit" was the order's own price. SEC 15c3-5
  wants pre-trade credit/size/restriction/kill-switch checks *before* the market.
- Mechanism: two tiers — a fast in-memory edge screen, then the authoritative in-sequence BLP decision. A
  control change is a sequenced `*_CONTROL` event on the *same* ring as orders, so control and orders can't
  race and every verdict is journaled/replayable.
- Evidence: per-control reject reasons print live (UNKNOWN_ACCOUNT, PRICE_COLLAR, ORDER_SIZE, RESTRICTED,
  KILL_SWITCH); a valid order returns NEW. Restrict/un-restrict and engage/disengage flip the verdict live.
- Say honestly: "nearly free" is a *design* property (0 sync DB/REST per decision), not a benched number;
  only authoritative BLP-tier rejects are journaled (edge pre-screen rejects die at the HTTP edge).

### YU04 — Durable Control Feeds

**Bring-up**
```bash
bash pipeline/generate-state.sh YU04-durable-control-feeds
bash generated/code/target-generated/scripts/start-state-YU04-durable-control-feeds-generated.sh \
  --provider kind --without-sail
```
First run on a fresh cluster: if `account-service`/`reference-data` crashloop, it's the DB-init/reference-data
env spec-layer gap — fixed in specs now, but a pre-existing cluster may need the hot-patch
(`issues/HANDOFF-issue-spec-layer-propagation-gaps.md`).

**Demo/proof**
```bash
kubectl port-forward -n traderx svc/reference-data 18085:18085 --context kind-traderx-state-014
bash scripts/demo/yu04-live-delta.sh        # inject a security → present in the replica ~1s later, no restart
bash scripts/demo/yu04-offline-catchup.sh   # scale order-matcher→0 → inject → back up → present after bootstrap
```
Both take an optional ticker arg (default `Z`+timestamp so reruns don't collide).

**What to look for / explain**
- The gap: YU03's replica was a one-shot boot fetch — stale-until-restart while running, no versioning to
  even detect it. YU04 makes the risk universe a live, durable, event-sourced feed.
- Mechanism: source services publish deltas from a transactional outbox (business row + outbox row in one
  txn) to per-source JetStream streams; the replica bootstraps from a watermarked snapshot + buffered
  deltas, then consumes live. The delta handler makes the *same two calls* as the operator control plane —
  one sequenced sink, two sources.
- Evidence: proof 1 — injected ticker appears in the replica within a poll interval, `✔ no restart`. Proof 2
  — change published while the pod is down is caught up on reconnect (`✔ durable catch-up, no re-push`).
- Honesty: it's a single gateway folded into the edge (multi-gateway is deferred); determinism comes only
  from the single-writer BLP linearizing on one ring, never from the gateways.

### YU05 — Post-Trade Compliance

**Bring-up**
```bash
bash pipeline/generate-state.sh YU05-post-trade-compliance
bash generated/code/target-generated/scripts/start-state-YU05-post-trade-compliance-generated.sh \
  --provider kind --without-sail
```
**Demo/proof** (scripts share `yu05-common.sh`; order-matcher is via edge-proxy :8080)
```bash
kubectl port-forward -n traderx deploy/trade-processor 18091:18091 --context kind-traderx-state-014
bash scripts/demo/yu05-auth-entitlements.sh        # JWT + entitlement matrix
bash scripts/demo/yu05-regulatory-reproducible.sh  # two calls → identical SHA
bash scripts/demo/yu05-recon.sh                    # matched + full-history orphan sweep
bash scripts/demo/yu05-settlement.sh               # book pair → Processing(T+1) → force → Settled
```
**What to look for / explain**
- The gap: booked-and-forgotten, a read-model you had to trust, no reproducible audit, shared-token stopgap.
  Mechanism: the journal is the source of truth, so every obligation is a read-only projection of it,
  strictly downstream of the BLP.
- Evidence — recon orphan sweep: 11 local rows, 7 with journal provenance, **4 orphans** = the DB-seed
  trades correctly fingerprinted as `ORPHAN_IN_PROJECTION` (drift detection working, not a bug).
- Evidence — regulatory export: two calls over the same range return a byte-identical SHA (`✔` reproducible
  from the journal, not the projection).
- Evidence — settlement: a booked pair starts `Processing` T+1, force advances it to `Settled`. Auth codes:
  admin 200, scoped own-account 200, scoped foreign 403, no-admin cross-account / no-bearer 401.
- Say honestly: settlement is a T+N lifecycle state machine, not real DVP/clearing; TCA computes only where
  price history exists (META `trd-09b-4`, 19 samples → −102.3 bps; IBM/MSFT null) — don't slide arrival price.

### YU06 — EOD Price Production

**Bring-up**
```bash
bash pipeline/generate-state.sh YU06-eod-price-production
bash generated/code/target-generated/scripts/start-state-YU06-eod-price-production-generated.sh \
  --provider kind --without-sail
```
Gotcha: an older-commit cluster may miss position-service's NATS env → zero `eod_position_pnl` rows. Fixed
durably (YU06-layer override adds `NATS_BROKER_HOST=nats-broker`); if a fresh bring-up shows no P&L rows,
`kubectl set env deploy/position-service NATS_BROKER_HOST=nats-broker`.

**Demo/proof** (versioning + chain use the trade-processor port-forward; the flag scripts are self-contained
via kubectl and reset `EOD_UNIVERSE` when done)
```bash
kubectl port-forward -n traderx deploy/trade-processor 18091:18091 --context kind-traderx-state-014
bash scripts/demo/yu06-versioning.sh      # close→v1, close→v2, v1 immutable
bash scripts/demo/yu06-chain-e2e.sh       # publish → eod_position_pnl rows + eod.pnl.done
bash scripts/demo/yu06-quality-gate.sh    # QLTY MISSING → blocked → override → published
bash scripts/demo/yu06-consumer-halt.sh   # NVDA excluded → account 10031 held back
```
**What to look for / explain**
- The gap: no official closes, no overnight batch, no data-quality sign-off. Mechanism: close → quality gate
  → immutable versioned snapshot → durable `EOD_PRICES_READY` → position-service EOD P&L; off the hot path,
  idempotent, versioned.
- Evidence — quality gate (the money demo): a MISSING price blocks publication even with auto-publish on;
  operator override (with reason) resolves it as a *new* version, prior versions stay immutable.
- Evidence — durable chain: the consumer marks positions only against the exact published version; a consumer
  that was down replays the retained stream on reconnect (we saw this catching up live during the NATS-env fix).
- Evidence — consumer halt: account 10031's unpriced NVDA leg is held back (`halted`), not guessed.
- Warm-up caveat: a trade-processor restart wipes the in-memory PriceHistoryStore, so the first post-restart
  close flags everything MISSING; the flag scripts poll-close until it settles before proving — that wall of
  MISSING is the warm-up, not a bug.

### YU07 — Historical Tick Store

**Bring-up**
```bash
bash pipeline/generate-state.sh YU07-historical-tick-store
bash generated/code/target-generated/scripts/start-state-YU07-historical-tick-store-generated.sh \
  --provider kind --without-sail
```
`tick-store` needs the `tick-store-gcs-hmac` Secret to write to GCS; the demos below prove the mechanism to a
local dir without it. Stale-image trap: the only local `tick-store:state-yu07` image can be the amd64 copy on
an arm64 kind node → ImagePullBackOff; rebuild it locally (arm64) for a live YU07 demo.

**Demo/proof**
```bash
# self-check (no cluster / GCS / TAQ file needed):
cd specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python3 -m pytest tests/ -q                 # 14 passed
bash scripts/demo/yu07-unified-query.sh     # live + TAQ, one schema, one DuckDB query (dependency-free)
# live capture off the running cluster:
kubectl port-forward -n traderx svc/nats-broker 4222:4222 --context kind-traderx-state-014
bash scripts/demo/yu07-live-capture.sh 25   # ~105 real ticks → local Parquet → DuckDB readback
```
**What to look for / explain**
- The gap: prices were ephemeral — no history for VWAP/TCA/VaR. Mechanism: a passive NATS subscriber captures
  live `pricing.*` + trades into columnar Parquet, and TAQ files normalize into the *same* schema, so one
  DuckDB query spans both by a `source` field. Zero hot-path impact (killing capture back-pressures no one).
- Evidence — unified query (the money demo): live rows + normalized TAQ CQ/CT rows in one schema, one query
  (`✔ live + TAQ, one schema, one query`); VWAP(IBM) weights TAQ trades like live trades.
- Evidence — live capture: ~105 real cluster ticks across ~20 symbols captured to the unified schema `✔`.
- Say honestly: demoed differently from the OMS states (no HTTP proofs); real TAQ CT verification (SC-TS06) is
  still pending against the actual zip — tested against a format-accurate sample. Fixed live: `capture.py` was
  KeyError'ing on the `pricing.*` envelope (0 rows) — now unwraps `payload`.

### YU08 — Execution Algo Engine

**Bring-up**
```bash
bash pipeline/generate-state.sh YU08-execution-algo-engine
bash generated/code/target-generated/scripts/start-state-YU08-execution-algo-engine-generated.sh \
  --provider kind --without-sail
```
Two stale-artifact traps (both from `--skip-build`): (1) `tick-store` ImagePullBackOff (wrong-arch image)
hangs the rollout — not needed for YU08, `kubectl scale deploy/tick-store --replicas=0` to unblock; (2) a
stale `execution-algo-engine` jar boots "Ready" (no readiness probe) but the API is dead — rebuild
(`./gradlew bootJar -x test` → `docker build` → `kind load` → restart), don't `--skip-build`.

**Demo/proof** (scripts self-manage the :18120 port-forward, respawning it until the API answers)
```bash
# unit tests (no cluster):
cd specs/YU08-execution-algo-engine/generation/runtime-overrides/execution-algo-engine && ./gradlew test
bash scripts/demo/yu08-twap-e2e.sh        # TWAP → equal buckets → children via order-matcher → COMPLETED
bash scripts/demo/yu08-vwap-e2e.sh        # VWAP profile ≠ flat split, then runs the weighted order → COMPLETED
bash scripts/demo/yu08-crash-recovery.sh  # kill pod mid-run → replay from JetStream → unique children, no re-submit
```
**What to look for / explain**
- The gap: a large order was one market-moving print. Mechanism: a parent-order execution layer (TWAP equal
  time buckets, VWAP volume-weighted) — no privileged lane (every child goes through order-matcher's shared
  `POST /orders` + YU03 risk gateway), event-sourced to its own `TRADERX_ALGO_ENGINE` stream before memory.
- Evidence — TWAP: 300→[100,100,100], children `ord-013-00xx`, filled, `COMPLETED`. VWAP: buckets follow the
  U-curve `[136,92,70,70,92,140]` (≠ flat [100×6]), then the weighted order runs to `COMPLETED`.
- Evidence — crash recovery: kill mid-run (e.g. 3/6 sent), the replacement pod replays the stream → 6 unique
  children, no re-submit, `COMPLETED 6/6` (`✔ pre-kill children kept`).
- Say honestly: VWAP's first consumer of YU07's tick store (`ALGO_VOLUME_PROFILE_SOURCE=duckdb`, synthetic
  fallback so it never blocks); measured perf full-store glob ~224s → scoped paths + per-symbol cache ~45s
  cold / 0.000s cached. The missing readiness probe (→ YU09 ops work) is why the scripts respawn the forward.

### YU09 — Ops Hardening

**Bring-up.** Two gotchas, both must happen first:
```bash
# 1) Secrets FIRST — missing → CreateContainerConfigError (no dev fallback). --recreate-cluster wipes them.
kubectl create namespace traderx --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic mariadb-credentials -n traderx \
  --from-literal=username=traderx --from-literal=password=traderx --from-literal=root-password=traderx
kubectl create secret generic auth-secrets -n traderx \
  --from-literal=jwt-secret=dev-jwt-shared-secret --from-literal=dev-token-master-secret=dev-token-master-secret

# 2) the local harness does NOT build JVM jars → single-stage COPY build/libs/*.jar fails on the first image.
bash pipeline/generate-state.sh YU09-ops-hardening
for d in order-matcher account-service position-service trade-processor trade-service execution-algo-engine; do
  (cd generated/code/target-generated/$d && ./gradlew bootJar -x test); done

bash generated/code/target-generated/scripts/start-state-YU09-ops-hardening-generated.sh \
  --provider kind --without-sail
```
**Demo/proof** (three need no cluster; journal-rotation is live and drives order-matcher directly via kubectl)
```bash
bash scripts/demo/yu09-no-literal-creds.sh   # 5 Deployments + STS: 0 literal creds, all secretKeyRef (SC-OH03)
bash scripts/demo/yu09-stale-jar-guard.sh    # gradlew clean bootJar precedes docker build in publish path (SC-OH06)
bash scripts/demo/yu09-dr-runbook.sh         # pod/node/zone/MariaDB loss + RPO/RTO sections present (FR-OH40)
bash scripts/demo/yu09-journal-rotation.sh   # LIVE: 20s snapshot → new immutable segment, active reset (SC-OH04)
```
**What to look for / explain**
- The framing: first state that adds no feature — it makes YU02–YU08 safe to operate. Four gaps closed:
  secrets fail-closed, bounded+durable journal, no-stale-jar publish, DR runbook.
- Evidence — no literal creds: the 5 rendered Deployments + production STS show 0 literal cred values, each
  resolving via `secretKeyRef` into `mariadb-credentials`/`auth-secrets` `✔`.
- Evidence — stale-jar guard: `gradlew --no-daemon clean bootJar` runs *before* `docker build` in the publish
  pipeline and is pinned by the test-state regression check — structurally impossible to ship yesterday's jar.
- Evidence — journal rotation (live): set `SNAPSHOT_INTERVAL_MS=20000`, watch the pod's journal dir — the
  active journal (10.3MB) rotates to immutable `input-events-<epoch>.journal` segments and the active file
  resets (to 1,280 B); no GCS HMAC → archiver WARN keeps segments on disk (FR-OH23 fail-safe). Reset the
  interval to 300000 after.
- Say honestly (flagged, not YU09 regressions): the FR-OH04 scope gap in `cluster-addons/yu03-staging/*` was
  closed during prep (shared file → propagate on commit); a fresh-DB `/account/control-snapshot` 500 keeps
  admission CLOSED but doesn't block the rotation proof (driven by the internal SNAPSHOT event).

### YU10 — FIX Order-Entry Ingress

**Bring-up.** The local harness builds jars, so no manual bootJar step (unlike YU09):
```bash
bash pipeline/generate-state.sh YU10-fix-ingress
bash generated/code/target-generated/scripts/start-state-YU10-fix-ingress-generated.sh \
  --provider kind --without-sail
# Wait for order-matcher Ready — the FIX acceptor (:18130) only listens after readiness passes
# (logon before that is refused, exactly like HTTP). Mint a JWT with the SAME dev-token infra as REST:
FIX_JWT=$(curl -s -X POST http://localhost:8080/order-matcher/auth/dev-token \
  -H "Content-Type: application/json" -d '{"user":"user01","accountId":11413}')   # maps to BENCH01:11413
```
**Demo/proof**
```bash
FIX_JWT="$FIX_JWT" bash scripts/proofs/yu10-fix-session.sh            # logon → D→ER → cancel → status →
                                                                    #   duplicate-ClOrdID rejected; verifies DB row
FIX_JWT=not-a-jwt bash scripts/proofs/yu10-fix-session.sh            # fail-closed: logon rejected (bad token)
FIX_COMP_ID=NOBODY FIX_JWT="$FIX_JWT" bash scripts/proofs/yu10-fix-session.sh   # fail-closed: unmapped CompID
kubectl delete pod -n traderx -l app=order-matcher                 # then --resume: resend window reconciles,
FIX_JWT="$FIX_JWT" bash scripts/proofs/yu10-fix-session.sh --resume  #   OrderStatusRequest recovers, dup not re-executed
FIX_JWT="$FIX_JWT" SIDES=alternate node scripts/bench/load/fix-load.mjs --secs 60   # throughput (alternate sides < risk caps)
bash scripts/bench/fix-scaling-curve.sh                            # FIX-vs-REST scaling curve
```
**What to look for / explain**
- The framing: a hardened OMS nobody could *connect* to (REST-only) now speaks the sell-side's
  native protocol — in-process, so a FIX order is bit-identical to a REST order past the front door.
- Fail-closed identity: garbage/absent token or unmapped `SenderCompID` never reaches the app —
  rejected at logon by the *same* entitlement gate REST uses; starts closed regardless of the REST flag.
- FIX/REST equivalence: an order over FIX lands in the same blotter, risk state, journal, and DB
  projection (`yu10-fix-session.sh` checks the DB row as its final step; the 77,055-in-10s bench grew
  the projection by exactly that count).
- Durable correlation: the `ClOrdID → orderRef` ledger rehydrates across a pod kill; a same-ClOrdID
  retry after `--resume` is answered as a duplicate, not re-executed.
- Say honestly: the completed-lifecycle throughput ceiling is the shared single-threaded output/report
  path (single-writer applied to output) — a tuning target, not a correctness gap; no-GC gates stay
  exact-zero with FIX active.

### YU11 — Aeron + SBE BLP Replication

**What to demo.** YU11 is an infrastructure state — the "demo" is the transport A/B bench and the
crash→rejoin→2nd-failover HA proof, not a REST/FIX feature. The headline is the matched-methodology
number and the honest recovery story; keep the live HA cycle for when the host is calm (kind Lease
flap under load is the epoch-churn driver — see the recap's environmental findings).
```bash
# Transport A/B (the 49× headline) — matched line-for-line to the NATS Phase-0 harness:
bash scripts/bench/load/run-aeron-replication-phase0.sh          # Aeron MDC ~520,520/s vs File-NATS ~10,561/s
# Full raw record (params, provenance, confidence, unmeasured list):
#   scripts/bench/results/yu11-transport-2026-07-17.md
# Bring-up (two-pod MDC pair on the dedicated kind profile; flag selects the leg):
BLP_REPLICATION_TRANSPORT=aeron \
  bash generated/code/target-generated/scripts/start-state-YU11-aeron-replication-generated.sh \
  --provider kind --without-sail
```
**What to look for / explain**
- The gap: HA rode File-NATS at ~10.6k events/s — the replication leg, not the ~6M ops/s core, was
  the throughput tax; and an ACK meant "queued," not "journaled on the follower."
- The mechanism: one Archiving Media Driver sidecar/pod; 64-byte SBE encoded zero-copy in `tryClaim`;
  one manual-MDC publication fans to local Archive (:40127) + peer (:40123) sharing one session/
  position; durable ACK only after `journaledSeq` advances.
- The A/B honesty: 520,520 vs 10,561 events/s = ~49× (±2%, 3×30s); E2E on GKE Aeron HA = single-BLP
  parity 25,149 booked/s (+149% vs NATS-HA era); transport threads exact-zero, noGcTest green.
- Cross-epoch recovery (the hard part, tell the journey): split-publication dead-end → MDC pivot →
  lineage-continuous sequencing → checksummed snapshot-bundle transfer; proven empty-PVC rejoin +
  promotion at epoch 5 + second replacement, hashes matching. Closes "one-shot failover."
- The bug it surfaced (the bridge to YU12): `nextOrderRef` reuse (`ord-013-0008`) because the ID
  generator lived outside replicated state → YU12 snapshot-completeness requirement.
- Say honestly / do NOT overclaim: failover here is still k8s-plane Lease (3 s p95 target;
  fast-witness 30–60 ms NOT measured e2e; ~17 s promotion under kind). Sub-second failover is YU12,
  not YU11. Nothing pushed; GKE ran `83049c8` (pre-recovery-slices) for the parity number.

### YU12 — Aeron Cluster BLP Consensus

**What to demo.** The cluster is an infra state — the strongest live demos are the **single-member
snapshot/restart no-reuse proof** (no cloud needed) and, if the GKE cluster is up, the **off-plane
failover + pipelined flood**. The `aeron-cluster-live-ops` skill has the live GKE playbook.
```bash
# Single-member cluster proof (in-process; round-trips orders through the consensus log, snapshots,
# restarts from disk, asserts strict no-ID-reuse) — the cleanest correctness demo, no cluster:
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.*'
# Full regression + zero-alloc gates (incl. the cluster service-thread exact-zero gate, NFR-AC01):
./gradlew test && ./gradlew --no-daemon noGcTest
# State checks (markers, generated architecture doc):
TRADERX_SKIP_GENERATE=1 bash scripts/test-state-YU12-aeron-cluster.sh
```
**GKE (yaakov runs the GCP/kubectl — working convention).** Ordered deploy + bench commands live in
`docs/handoff/GKE-yu12-deploy-bench.md` (project `traderx-501015`, cluster `traderx-lmax`, zone
`us-east1-b`, image `.../traderx/cluster-node:yu12`, bench label `aeron-cluster`). blp-pool needs 3
nodes for one-member-per-node anti-affinity; it's usually scaled to 0 — check first.
**What to look for / explain**
- The framing: YU11 proved the transport + recovery but with a tower of hand-built HA; YU12 makes
  election/log/empty-disk-recovery Raft **primitives** and split-brain **structurally impossible**.
- No ID reuse *by construction*: the orderRef (and every future-output generator) is replicated
  cluster state in the log + snapshot; recovery asserts it exceeds every ID ever issued. 0 reuse
  across ~35 kills.
- Failover: **653–716 ms system-facing** (node-clock-precise), off the k8s control plane, 6× the
  Aeron default. Say the client-observed ~0.85–1.6 s floor is a **test-client reconnect artifact**,
  not the cluster (best ~200 ms). FIX/REST gateway survives the leader change.
- Throughput: pipelined **45,684–135,834 booked/s** past the 25,149 bar; consensus wasn't the
  bottleneck (18.6k/s cascade, 740k-event replay in seconds) — per-order ingress was.
- The flood bugs (great engineering story): the **output-ring poison pill** (producer==consumer,
  cascade > ring → parked inside apply → wedged replay → leaderless restart; fixed with a
  backpressure-drain claim), plus egress throttle, rolling-restart tail loss, gateway self-eviction.
- Honest boundaries: 3-on-2 nodes (quota); periodic 60 s snapshots (barrier stall); `emptyDir`
  forfeits the tail only on simultaneous whole-cluster loss; nothing pushed; entitlement stays false.
