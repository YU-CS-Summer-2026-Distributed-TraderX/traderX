# Handoff: Next production-realism architecture decision (new state YU05) + incorporating 3TB of historical market data

## What this chat accomplished

This session (on `YU03-in-memory-risk-gateway`) finished and hardened item 1 of the
production-realism roadmap (see `HANDOFF-production-realism.md` for the original roadmap):

- Completed the in-memory risk gateway slice 1: two-tier Gateway-replica + authoritative BLP
  `decideAndReserve` decision, SEC 15c3-5 control baseline, sequenced control events, snapshot v3,
  `/risk/control/*` admin API, Prometheus metrics.
- Fixed two real bugs along the way (stale H2 `MODE=PostgreSQL`, a shadowed Hibernate naming
  override) that had been misdiagnosed as environmental flakiness.
- Closed out k8s `RISK_*` env plumbing, a binary (non-JSON) price-tick payload path
  (price-publisher → order-matcher, additive alongside the existing JSON feed the UI uses), a
  measured 5µs p99 latency CI gate, a Grafana dashboard for risk metrics, and UI surfacing of
  rejection reasons + `clientOrderId`.
- Set up an isolated staging Cloud Build trigger + Cloud Deploy pipeline for YU03
  (`order-matcher-yu03-staging-cicd` / `order-matcher-yu03-staging-pipeline`), so this branch can
  build and deploy for real without ever touching the production `traderx` namespace.
- **Another chat, in parallel, is building `YU04-durable-control-feeds`** (descendant of YU03) —
  the outbox→JetStream/watermarked-bootstrap durability work for the Gateway replica (ADR-019,
  FR-IMRG04/05/32/33/34). Don't duplicate that work; this handoff is about what comes *after* it.

## Branch / repo state

- **Current branch:** `YU03-in-memory-risk-gateway`, fully pushed, clean working tree.
- **`YU04-durable-control-feeds`** (or whatever it's actually named — confirm with the user/other
  chat) will exist as a sibling effort, parent `YU03-in-memory-risk-gateway`. Not part of this
  session's scope.
- Production is `YU02-lmax-kubernetes-blp-ha`, healthy, single-BLP mode by explicit user choice.
- **This new work should be its own state, `YU05-<name>`**, per the user — parent should almost
  certainly be `YU03-in-memory-risk-gateway` (same as YU04) unless the chosen direction genuinely
  depends on YU04's durable feeds landing first (see "Open questions").

## Goal for next chat

Two things, in order:

1. **Make an architecture decision**: given access to ~3TB of real historical market data (from the
   user's professor), what real-world production service(s) should TraderX incorporate next? This
   chat should evaluate options (see "Architecture / context" for candidates worth considering —
   not a prescribed answer, a starting menu) against the existing production-realism roadmap in
   `HANDOFF-production-realism.md` (post-trade/settlement, real auth, market data dissemination,
   regulatory reporting, ops hardening), and land on ONE concrete direction with the user.
2. **Package the chosen direction as a new spec-kit state, `YU05-<name>`**, descendant of YU03 (or
   YU04 if it turns out to depend on durable feeds), following the exact same pattern as YU03/YU04:
   full spec-pack (spec.md, plan.md, research.md, data-model.md, contracts, requirements deltas,
   architecture/runtime-topology docs, ADR(s), tasks.md), `pipeline/generate-state-YU05-*.sh` +
   `render-state-YU05-*.sh`, and — once the design is real — its own isolated staging Cloud Build
   trigger + Cloud Deploy pipeline (same pattern as YU03/YU04, never touches production).

## Key files

| Path | Why it matters |
|---|---|
| `HANDOFF-production-realism.md` | **The original roadmap this all descends from.** Items 2-6 (post-trade/settlement, real auth, market data dissemination, regulatory reporting, ops hardening) are still open — the 3TB dataset should be evaluated against these, not treated as a totally separate track. |
| `HANDOFF-durable-control-feeds.md` | What the *other* concurrent chat (YU04) is doing — read this to avoid overlap and to understand the current Gateway-replica bootstrap architecture, since some market-data-service candidates would plug into the same JetStream/replica infrastructure YU04 is building. |
| `specs/YU03-in-memory-risk-gateway/` | The current spec-pack shape to mirror exactly for YU05 (`spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/contract-delta.md`, `requirements/functional-delta.md`, `requirements/nonfunctional-delta.md`, `system/architecture.md`, `system/runtime-topology.md`, `system/adr-*.md`, `tasks.md`, `generation/generation-hook.md`, `generation/implementation-status.md`). |
| `price-publisher/` (canonical source is actually `templates/state-010-pricing-awareness-market-data-overlay/price-publisher/`, NOT the bare `price-publisher/` at repo root — see the generation-pipeline gotcha below) | Current synthetic random-walk price generator (~750-1500ms jittered ticks, 25% of symbols per cycle). This is the most obvious thing 3TB of *real* market data could replace or supplement — see candidate #1 below. |
| `pipeline/generate-state-YU03-in-memory-risk-gateway.sh` + `render-state-YU03-in-memory-risk-gateway.sh` | Template for the YU05 generate/render hook pair — same simple `overlay_dir()` pattern. |
| `cloudbuild-yu03-staging.yaml`, `clouddeploy-yu03-staging.yaml`, `cluster-addons/yu03-staging/` | Template for YU05's own isolated CI/CD, once there's a real implementation to deploy. |

## Architecture / context the next chat needs

**Current system shape (unchanged by this handoff):** LMAX BLP (`order-matcher`) = single-threaded
in-memory matching, journaled, snapshotted, single-BLP in production. MariaDB is an async
read-model projection, not source of truth. NATS for messaging (core NATS today; YU04 is adding
JetStream for durable control feeds). Supporting services: trade-processor, account-service,
position-service, price-publisher, reference-data, people-service, trade-service, web-front-end.
Deployed on GKE; CI/CD via Cloud Build → Cloud Deploy, one pipeline per active state now
(production + YU03-staging, soon YU04-staging).

**The 3TB market data question — candidate directions to evaluate (not a decision, a menu):**

1. **Realistic price feed / historical replay.** Replace or supplement `price-publisher`'s synthetic
   random walk with real historical tick/quote replay (configurable speed — real-time, accelerated,
   or a specific historical session like a flash-crash day). Highest "does this make the system
   actually realistic" payoff, and it's a natural extension of the binary tick-payload work already
   done this session (same NATS subject shape, just a real data source instead of `Math.random()`).
   Also the only candidate that would let the risk gateway's collar/limit logic be validated against
   *real* volatility regimes instead of a bounded synthetic band.
2. **Transaction Cost Analysis (TCA) service.** Post-trade analytics comparing each fill's price
   against arrival price / VWAP / TWAP benchmarks computed from the historical data. Real, common
   buy-side/sell-side service; extends roadmap item 2 (post-trade/settlement) and pairs naturally
   with the journal as the source of executed fills.
3. **Market surveillance / abuse detection.** Spoofing/layering/wash-trade pattern detection over
   reconstructed L2 order-book history. A real regulatory function (Reg SHO / MAR-style), but
   depends on roadmap item 4 (market data dissemination — L2 book publish) existing first, which it
   currently doesn't (order-matcher only emits last-trade prints today).
4. **Market data warehouse / analytics lake.** Ingest the 3TB into a queryable store (Parquet +
   DuckDB/ClickHouse/TimescaleDB, or similar) as shared infrastructure other services (TCA,
   surveillance, backtesting, ML calibration) would query. This is the "plumbing" option — valuable
   but not user-facing on its own; likely a *component* of whichever of 1-3 gets picked, not a
   standalone state.
5. **ML-based dynamic risk calibration.** Use historical per-symbol volatility to auto-tune the risk
   gateway's `RISK_PRICE_COLLAR_BPS`/credit-limit defaults instead of static config. Directly extends
   YU03; interesting but narrower in scope than 1-3.
6. **Reference-data / corporate-actions backfill.** Use historical data to validate/backfill
   splits, dividends, and symbol changes in `reference-data`. Smallest, most mechanical option.

Recommend evaluating in that order — #1 is the most natural next step given everything built this
session, #4 is probably a required sub-component of whichever gets picked rather than a state on its
own, and #3 has a real dependency gap (needs L2 dissemination first).

**Generation pipeline gotcha (hit repeatedly this session, will bite YU05 too if not checked):**
Several files' *real* generation source is not where it looks. Some (`price-publisher/src/main.js`)
are rebuilt from a chain of literal git patches (`specs/<state>/generation/patches/`) applied across
older states (004→009→009b→010→...) — editing a `runtime-overrides` copy of these does nothing. Some
(`web-front-end`'s files, the Grafana dashboards ConfigMap) are produced by a `cp -R` or aggregation
step inside a *specific ancestor state's own render script* (e.g. state 014's render script does
`cp -R` from its own `frontend-overrides`) that runs earlier in the chain than YU0x's overlay and is
never re-scanned. **The fix that always works:** copy the file from the currently-generated tree
(`generated/code/target-generated/<path>`, which reflects the real merged content) into
`specs/YU05-.../generation/runtime-overrides/<same path>`, since each state's own `overlay_dir()`
call applies *last*. **Always verify empirically** — add a one-line marker, regenerate
(`bash pipeline/generate-state.sh YU05-...`), grep the generated output for the marker — before
trusting that an override location is live.

**GCP/CI-CD context:** project `traderx-501015`, region `us-east1` for Cloud Deploy, `global` for
Cloud Build triggers. Service account `traderx-cicd@traderx-501015.iam.gserviceaccount.com` already
has the IAM a new pipeline needs. **Any change to a live Cloud Build trigger or Cloud Deploy
pipeline needs the user's explicit go-ahead** — a safety classifier enforced this repeatedly this
session and will again.

## Decisions already made (don't re-litigate)

- **Package as a new spec-kit state (YU05...), not inline edits** — established pattern for every
  roadmap item so far.
- **Keep new work off production** — build+verify in an isolated staging namespace/pipeline first,
  same as YU03/YU04, until the user explicitly says otherwise.
- **The production-realism roadmap order was already agreed** (see `HANDOFF-production-realism.md`)
  — post-trade/settlement, real auth, market data dissemination, regulatory reporting, ops
  hardening, in that order. The 3TB dataset is a new input to *how* to sequence/flesh out those
  items, not a reason to abandon the existing roadmap.
- **NATS→Aeron was already considered and rejected** for throughput reasons in an earlier session —
  not relevant to market-data ingestion, don't revisit.
- **Two-tier risk gateway design (Gateway replica + BLP authoritative decision) is locked in** —
  any new service should integrate with it, not bypass or duplicate it.

## Open questions / known issues

- **Which candidate direction (1-6 above) to build is genuinely undecided** — this is explicitly
  "the next big architecture decision" the user wants this chat to make, in consultation with them.
  Don't pick unilaterally without checking in.
- **Format/access of the professor's 3TB dataset is unknown** — next chat needs to ask the user:
  what exchange(s)/instruments, what granularity (trade prints vs. full L2/L3 book), what file
  format (FIX, ITCH-style binary, CSV/Parquet), and how it'll physically get onto the machine/GCS
  (3TB is a real transfer/storage decision — GCS bucket + streaming ingest vs. local disk staging).
- **Parent state for YU05**: probably YU03, but if the chosen direction needs durable control feeds
  (e.g. reference-data corporate-actions backfill would want YU04's outbox infra) it might need to
  be a YU04 descendant instead — decide once the direction is picked.
- **Whether a market-data warehouse (#4) is its own state or a shared component**: if the picked
  direction is TCA or surveillance, the warehouse piece might need to exist first as its own
  mini-deliverable inside the same state rather than a separate YU-numbered state — a scope call,
  not decided.

## Suggested first steps for next chat

1. Read `HANDOFF-production-realism.md` in full for the original roadmap and rationale.
2. Read `HANDOFF-durable-control-feeds.md` to understand what YU04 is doing in parallel (avoid
   overlap, understand the JetStream infrastructure being built that a market-data service could
   reuse).
3. Ask the user directly about the 3TB dataset's shape (exchange/instruments, granularity, format,
   access mechanism) — this materially changes which candidate direction is even feasible.
4. Walk through the 6 candidate directions above with the user, weighing them against the existing
   roadmap items, and land on one concrete YU05 scope.
5. Scaffold `specs/YU05-<name>/` (mirror YU03's spec-pack file list exactly), write the
   generate/render pipeline hook pair, confirm `bash pipeline/generate-state.sh YU05-<name>` exits 0
   before writing real implementation code.
6. Build the smallest meaningful vertical slice first (same discipline as YU03's slice-1 approach),
   verify with tests, then consider standing up the isolated staging CI/CD pipeline — with explicit
   user go-ahead before touching any live Cloud Build/Deploy resource.
