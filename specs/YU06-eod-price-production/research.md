# Research: YU06 — EOD Price Production + Overnight Batch Chain

## Decision 1 — where the EOD price service lives: trade-processor (no new microservice)

The EOD price producer needs four things, and **trade-processor already has all four** from prior
states:

- **A live source of the last price per instrument** — YU05 added `PriceHistoryStore` +
  `PriceTickHandler` in trade-processor, subscribing to price-publisher's existing `pricing.*` NATS
  feed (the same feed the Angular ticker consumes). Per YU05's research, order-matcher emits
  **last-trade prints** on this feed, so "closing price = last trade price" is literally the most
  recent sample per ticker in `PriceHistoryStore` — no new data source, no new subscription, no BLP
  hot-path event.
- **MariaDB access** for the versioned snapshot tables (existing `TradeRepository`/datasource).
- **Real JWT auth** for the admin-gated session/override/publish endpoints (`JwtAuthenticator`,
  `AuthController`, `POST /auth/dev-token` — all from YU05).
- **A scheduler and controller surface** (`@Scheduled` sweeps + admin controllers already exist for
  settlement/recon).

Standing up a separate `eod-price-service` microservice (new Gradle module, Dockerfile, k8s
Deployment, DB wiring, auth, NATS config) would duplicate every one of those for zero capability
gain. **Rejected.** The producer is a handful of new classes inside trade-processor.

## Decision 2 — where the first consumer lives: position-service (genuinely event-gated)

The point of the gate event is that a *separate* downstream job is unblocked by it. The natural
first job is **EOD position marks / P&L**, which needs positions × closing prices. Positions live in
**position-service** (its own MariaDB tables, `PositionRepository`). So the consumer belongs there.

position-service today is a plain MariaDB CRUD service with **zero messaging wiring** — so the
durable JetStream subscriber is genuinely net-new code here. That is accepted, not avoided: a
cross-process, durable-event-gated consumer *is* the feature. Putting the consumer back in
trade-processor (so it subscribes to its own event in-process) would be a weaker demonstration and
would still need position data it doesn't own. The added surface in position-service is the NATS
client dependency, a `PubSubConfig` (mirroring trade-processor's), and the consumer + P&L classes.

## Decision 3 — closing price definition: last trade price (ADR-026)

v1 uses the last trade print per instrument (most recent `PriceHistoryStore` sample at//before
session close). Alternatives considered:

- **Last mid** — needs live bid/ask book state at close; order-matcher only emits last-trade
  prints, so there is no book-depth feed to take a mid from. Would require L2 dissemination that
  doesn't exist yet (explicitly deferred out of YU05). Rejected for v1.
- **Mini closing auction in the BLP** — the realistic method, but it is real matching-engine
  (hot-path) work. Kept as a stretch goal, not v1 scope. The snapshot/event/consumer contract does
  not change when the price *source* is later upgraded — only what feeds the per-instrument closing
  price does.

## Decision 4 — versioned immutable snapshot, not "read latest at job start" (ADR-026)

Every consumer must read the *same* prices or downstream numbers are irreconcilable.
A mutable "current EOD price" row fails this: two jobs reading it around a correction see different
values. So the snapshot is **append-only and versioned**:

- `eod_price_session (session_date, version, status, ...)` — one header row per produced version.
- `eod_price_snapshot (session_date, version, security, closing_price, quality, ...)` — the priced
  instruments for that version.

A correction never updates a published row; it produces `version + 1`. The `EOD_PRICES_READY` event
carries `(session_date, version)` so every consumer reads exactly the version that was gated. This
is the same immutability discipline YU05 used for journal-sourced reports (reproducible by
construction), applied to prices.

## Decision 5 — orchestration is a NATS event chain, not a workflow engine (ADR-027)

For TraderX a small JetStream event chain is the right size:
`session-close → (produce/quality/publish) → eod.prices.ready → position-service marks → eod.pnl.done`.
Each stage publishes a durable `*_DONE`-style event the next stage subscribes to; ops watches the
chain via per-stage metrics + timestamps. A full workflow engine (Airflow/Control-M) would dwarf the
system it orchestrates — **not introduced.** Plain k8s Jobs with an ordering controller were also
considered; the event chain is lazier (no new controller, reuses the JetStream infra YU04 already
stood up) and lets each job simply subscribe to the prior job's completion event.

## Decision 6 — durability: reuse YU04's JetStream publish pattern (ADR-027)

`EOD_PRICES_READY` and `eod.pnl.done` must survive a consumer being down/restarted at close time —
an overnight batch that boots after the event fired must still see it. YU04 already solved durable
publish for control feeds (`JetStreamControlFeedPublisher`, stream + durable consumer, outbox
atomicity). YU06 reuses that pattern: a `TRADERX_EOD` JetStream stream with file storage, and the
position-service consumer binds a **durable** pull/push subscription so redelivery covers the
restart case. The producer publishes the event only *after* the snapshot rows are committed, so the
event can never reference an unwritten version (the ordering YU04's outbox also guarantees).

## Decision 7 — fail-safe halt-and-alert at both ends (ADR-028)

- **Producer side**: `publish` refuses (409) if any instrument in the latest version is unresolved
  `STALE`/`SPIKE`/`MISSING`; no event is emitted. The operator must override (→ new version) or
  explicitly accept. A session-close with an all-clean snapshot auto-publishes.
- **Consumer side**: when marking an account, a held security that is `MISSING` or still
  quality-flagged in the snapshot halts *that account's* marking, increments
  `eod_pnl_halted_total{account}`, and logs an alert — it is never marked with a guessed or stale
  price. Other accounts continue (one bad instrument does not fail the whole batch, but no account
  is ever silently mispriced).

## Generation pipeline gotcha — shared-file overrides vs. ancestors (must verify empirically)

Generation overlays **full-file** overrides per state, last-wins, not diffs. YU06's overrides must
not silently clobber ancestor changes to any shared file:

- **`trade-processor/src/main/resources/application.properties`** — overridden by YU02 (DB driver)
  and YU05 (auth/tca/recon config). YU06 adds `eod.*` config here, so YU06's copy must start from
  YU05's current version and *append*, never replace.
- **`trade-processor/src/main/java/.../TradeProcessorApplication.java`** and **`PubSubConfig.java`**
  — YU05 overrides both. If YU06 touches them (e.g. to register the EOD scheduler/publisher bean),
  YU06's copy must include YU05's content.
- **`position-service/.../application.properties`** and **`build.gradle`** — overridden by YU01/YU02.
  YU06 adds the NATS client dep + `eod.*` consumer config, so it must start from the latest ancestor
  copy.
- **`kubernetes-runtime/manifests/base/database-init-configmap.yaml`** — the **real runtime schema**
  (not `database/initialSchema.sql`, which is dead — confirmed in YU05 research). The new
  `eod_price_session` / `eod_price_snapshot` / `eod_position_pnl` tables go in this ConfigMap. YU06's
  copy must start from YU05's version (which already added `settlementdate`) and add the tables.

**Verification protocol (run before finishing):** regenerate YU06, then grep the generated output
for a marker from *every* ancestor that touches each shared file (e.g. `settlementdate` from YU05,
the YU04 control-feed property names, the YU02 MariaDB driver) alongside YU06's own markers. If any
ancestor marker is missing, the override clobbered it — merge by hand (`git merge-file`) and
regenerate.
