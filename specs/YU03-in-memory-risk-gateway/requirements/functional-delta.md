# Functional Delta: YU03-in-memory-risk-gateway (vs YU02-lmax-kubernetes)

Everything YU02 provides stays as it was: the same Disruptor ring topology, the journal-before-BLP
durability gate, the matching policy, the output handlers, the NATS subjects, the MariaDB read model
as a pure projection, and the whole build and deploy harness. What this state adds is the pre-trade
admission tier the system was missing — before it, an order with a valid ticker simply matched and
filled, with no credit, buying-power, exposure, restriction, or kill-switch control anywhere. Nearly
all of it lives inside `order-matcher` as runtime overrides, the exceptions being a rejection-reason
field on the order ticket and a provisioned Grafana dashboard. This first slice bootstraps its risk
state from a one-shot fetch sequenced through the journal, with control versions assigned internally
at the single control plane; durable feeds carrying per-source epochs and gap invalidation, and
entitlement screening, belong to later states. Running more than one Gateway concurrently and
quarantining invalid control updates also stay out of scope, and the startup and degraded-mode
matrix is only partly written — the fail-closed startup path itself is implemented. Requirement ids
(`FR-IMRG*`) are inherited verbatim from the original pre-Kubernetes `in-memory-risk-gateway`
design, readable via `git show in-memory-risk-gateway:specs/in-memory-risk-gateway/spec.md`.

## Added

- An in-process Gateway replica that screens every order, batch, and market trade against account
  status, security status, restrictions, kill switch, size, notional, price freshness, and price
  collar — reading only memory, so admission adds no REST or database call to the hot path.
- An authoritative decision in the single-writer BLP that repeats every mutable and aggregate check
  in global sequence order, so the edge screen is preliminary and the BLP always wins a disagreement.
- A fixed decision precedence — kill switch, account, security, restriction, quantity, price
  presence and freshness, notional, credit limit, position limit, concentration — so two identical
  orders always reject for the same reason.
- Checking and reserving as one single-threaded BLP operation before book entry, so aggregate
  exposure cannot overshoot a limit between the check and the reservation.
- Exposure reservation on accept: an order reserves `quantity × limitPx` against its account,
  consumed pro-rata as it fills and released on cancel, exactly once and never negative.
- Versioned control events for accounts, securities, policy, and restrictions travelling the same
  journaled, replicated global sequence as orders and prices.
- Losing the control plane leaves the command path running on installed local state — no fallback
  lookup, and no erasure of the state a replica already holds.
- Replay from snapshot plus journal reproducing every past acceptance and rejection identically,
  with no live external lookup, so an admission decision can be re-derived for audit.
- A rejected command stays journaled for audit yet never enters the book, moves a position, or
  emits anything market-facing beyond its own status or correlation ack.
- A token-authenticated `/risk/control/*` administration API that records the calling operator, and
  that cancels a restricted security's resting orders through sequenced `CANCEL` events.
- An optional `clientOrderId` idempotency key: a repeat submission returns the original decision
  instead of booking and reserving twice. Retention is bounded and evicts oldest-first.
- Stable reason codes on rejections, carried on the order lifecycle event, the trade-decision ack,
  the REST rejection body, and the order ticket in the UI, so a trader sees why rather than silence.
- Preallocated, bounded risk tables that reject with `CAPACITY` rather than growing, plus a bounded
  metric set (readiness, rejections by reason, decision latency, reserved notional) and a Grafana
  dashboard.

## Changed

- `POST /trades` now blocks for the sequenced BLP decision, answering `422` on rejection and `503`
  when the replica's control state is stale — previously it booked fire-and-forget with no risk
  check.
- Security ids come from reference data through the control plane; order flow can no longer mint
  symbol-table entries, and an unknown ticker rejects at the edge with `UNKNOWN_SECURITY`.
- Prices are held as fixed-point values with a source time, so a missing price is distinguishable
  from a genuine zero and the BLP never substitutes zero for one it does not have.
- Snapshots move to format v3, adding policy, per-account exposure, per-security control and prices,
  idempotency, and per-order reservations; v1 and v2 snapshots still load.
- A replica that has not finished its startup bootstrap fails closed, rejecting admission with `503
  CONTROL_STATE_STALE` instead of admitting unscreened flow.
- The output ring gains exactly two event kinds, the market-trade decision acks
  `KIND_TRADE_ACCEPTED` and `KIND_TRADE_REJECTED`, and the BLP still writes nothing outside that
  ring.
