# Feature Specification: In-Memory Risk Gateway (state YU03)

**State id**: `YU03-in-memory-risk-gateway`
**Parent state**: `YU02-lmax-kubernetes` (renders onto `014-fdc3-intent-interoperability`)
**Created**: 2026-07-06
**Status**: Slice 1 implemented (see `generation/implementation-status.md` for what is deferred)
**Input**: Forward-port of the pre-k8s `in-memory-risk-gateway` branch's spec pack
(`specs/in-memory-risk-gateway/` on that branch, parent `009b`), re-based as a delta over the
`YU02-lmax-kubernetes` runtime (HA replication, k8s Lease leader election, MariaDB projection,
snapshot+journal-tail recovery).

This state adds the pre-trade admission tier the system was missing: before it, an order with a
valid ticker simply matched and filled — no credit, buying-power, exposure, entitlement,
restriction, or kill-switch control existed anywhere. It keeps the two-tier model of the original
design (ADR-018) and the SEC Rule 15c3-5 Market Access control baseline:

1. **In-process Gateway replica screening** — preliminary, allocation-light validation against
   event-fed local state, with NO synchronous REST/DB lookup on the admission path.
2. **Authoritative deterministic BLP decision** — the single-writer BLP checks and *reserves*
   exact aggregate exposure in global sequence order before an order becomes executable. Rejected
   commands stay journaled for audit/replay but never enter the book or move a position.

Decision-relevant control changes (account status, security status, restrictions, risk policy,
kill switch) enter the same global input sequence as commands and prices (ADR-020), so snapshot +
journal replay reproduces every original acceptance or rejection without querying external state.

The SEC Market Access Rule is a control-requirements baseline only; this state does not claim
regulatory compliance.

## Requirements

The `IMRG` requirement namespace (`FR-IMRGxx`, `NFR-IMRGxx`) is inherited verbatim from the
original spec (readable on the `in-memory-risk-gateway` branch). This state implements the
slice-1 subset and records per-requirement status in `requirements/functional-delta.md` /
`requirements/nonfunctional-delta.md`. Headline coverage:

- **Implemented**: FR-IMRG01/02(partial)/06/07/08/09/10/11/12/13/14/15/16/17/18(partial)/19/20/
  21/22/23/24/26/27/40/41/42/43(partial), NFR-IMRG02(code discipline)/03/04/05/06.
- **Deferred** (later commits of this roadmap item or later roadmap items): durable
  account-service/reference-data control feeds with watermarked snapshot bootstrap
  (FR-IMRG04/05/32/33/34), entitlements (needs the real-auth roadmap item; FR-IMRG02's
  entitlement replica, FR-IMRG30's full authn), a separately deployed Gateway tier (FR-IMRG25
  multi-gateway concurrency), SBE contract, order expiry, Grafana dashboard/alert assets
  (NFR-IMRG08), perf-profile acceptance runs (NFR-IMRG01/13).

## Forward-port adaptations (deviations from the stale-branch design)

These are deliberate changes made because the `YU02-lmax-kubernetes` base diverged from the original
`009b` base; do not "fix" them back:

1. **No wire-format change.** The stale branch introduced a 96-byte versioned journal record
   with CRC and legacy upcasters. This branch keeps the existing 64-byte record used by BOTH the
   journal and the NATS JetStream replication stream, because snapshot recovery is keyed to
   journal *byte offsets* and the replication record already carries the ring sequence at the
   old pad. New data rides slots unused by each event type (`InputEvent`'s documented
   type-discriminated payload contract): the idempotency key and control version reuse the
   `priceTicks` slot; control booleans reuse `side`; policy limits reuse `qty`/`limitPx`.
   Pre-state journals replay unchanged (zeros decode as "no key").
2. **Control event type ids renumbered** (7–10): this branch had already journaled
   `TYPE_SNAPSHOT = 6`, which the stale branch used for `TYPE_ACCOUNT_CONTROL`.
3. **Reservations ride the order entry** (`ReservationHolder` on the pooled `RestingOrder`)
   instead of dense orderRef-indexed arrays: orderRef is monotonic/unbounded here (bounded
   terminal retention + array doubling), so the original arrays would exhaust at pool size and
   permanently CAPACITY-reject. Aggregates are rebuilt from open orders at snapshot restore, so
   per-order and aggregate reservation state cannot disagree.
4. **SymbolTable stays the security-id authority.** The stale replica assigned its own ids; here
   ids persist in `symbols.tab` across restarts, so the replica aligns to the SymbolTable at
   startup (`alignSecurityIds`) or replayed journals would disagree with replica state.
5. **Bootstrap through the journal.** The account/security universe (account-service `GET
   /account/`, reference-data `GET /stocks` — the full S&P 500 set) is fetched once at startup
   (cold path, PRIMARY only, fail-closed until complete) and each record is sequenced as a
   versioned control event — so BLP control state remains a pure function of snapshot+journal
   (FR-IMRG22), never of a live fetch. This stands in for the durable JetStream control feeds
   until they are ported.
6. **Snapshot format v3** extends the existing single `snapshot.dat` (order rows gain
   riskReason + live reservation; new policy/account/security/idempotency sections) rather than
   adding the stale branch's separate risk snapshot files. v1/v2 snapshots still load.
7. **Market trades are no longer fire-and-forget**: `POST /trades` now blocks for the BLP's
   sequenced accept/reject (FR-IMRG20) and returns a stable 422/503 rejection body. This and the
   optional `clientOrderId` field are the only intentional admission API deltas.

## Slice-1 behavioral contract

- Order/trade admission first passes Gateway screening (replica-local; rejects with a stable
  `RiskRejectionBody`: 422, or 503 when control state is stale/not-ready), then the BLP's
  ordered authoritative pipeline: kill switch → account known/enabled → (entitlement, when fed)
  → security known/enabled → restriction → quantity/size → price present/fresh → notional →
  credit limit (reserve) → position limit → concentration.
- Accepted orders reserve `quantity × limitPx` against the account until filled (converted,
  pro-rata) or cancelled/evicted (released, exactly once, never negative).
- Duplicate `clientOrderId` retries return the original decision without creating or reserving
  a second order. The key is optional in slice 1; absent = no retry mapping.
- Gateway pass is never final; when Gateway and BLP disagree, the BLP wins and the disagreement
  is counted (`traderx_gateway_blp_mismatch_total`).
- `/risk/control/{account,security,policy,restriction}` (token + operator headers) administers
  versioned controls; restricting a security cancels its resting orders via sequenced CANCEL
  events. `/risk/control/snapshot` exposes the replica image.
- Everything above replays deterministically from snapshot + journal (FR-IMRG22); recovery
  re-aligns the edge replica's policy view from the recovered BLP state.

## Out of scope for this state (unchanged from the original spec)

No output-disruptor redesign; no synchronous remote risk microservice on the command path; no
VaR/margin analytics; no generic fail-open mode for risk-increasing orders; no compliance
certification.
