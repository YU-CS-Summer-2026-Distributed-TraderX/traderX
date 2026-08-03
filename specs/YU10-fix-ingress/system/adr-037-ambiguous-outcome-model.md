# ADR-037: Four-outcome admission model; status requests reconcile ambiguity

**Status**: Accepted · **State**: YU10-fix-ingress

## Context

Once a command is published to the input ring, its fate is decided by the sequenced pipeline
(journaler → risk screen → matcher) — a subsequent gateway timeout does not prove rejection; the
order may exist. The REST gateway exhibits the same window (publish, then bounded acknowledgement
wait). A FIX front door must not translate that ambiguity into a definitive answer.

## Decision

Admission outcomes are partitioned into exactly four cases (FR-FIX12):

| # | Case | Answer |
|---|---|---|
| 1 | Pre-publish failure (ring claim timeout, ledger unavailable) | Session-level reject; no order exists, provably — nothing was published. |
| 2 | Malformed / unsupported message | Session `Reject (35=3)`. |
| 3 | Application rejection (risk screen, unknown symbol) | `ExecutionReport` with `OrdStatus=Rejected`, reason in `Text(58)`. |
| 4 | Post-publish timeout / no timely report | **No reject is sent.** The eventual ExecutionReport is the outcome; the counterparty reconciles by `OrderStatusRequest` or a same-`ClOrdID` retry, which duplicate detection answers deterministically (the original outcome, not a second execution). |

`OrderStatusRequest (35=H)` is in the v1 message set specifically as the pull-based
reconciliation mechanism for case 4 and for the store's crash window (TD-FIX01): a counterparty
can always converge its view of any order it submitted, from the server's authoritative state.

## Alternatives considered

- **BusinessMessageReject on gateway timeout**: rejected — it can assert "not accepted" for an
  order the sequenced pipeline goes on to execute, the worst possible external statement.
- **Synchronous per-order acknowledgement (block the session until the report)**: rejected — it
  re-creates the REST round-trip this state exists to remove and turns backpressure into
  head-of-line blocking for the whole session.

## Consequences

- Client integration guidance is explicit: treat silence after publish as pending, reconcile
  with H, retry with the same ClOrdID (never a fresh one) if delivery is in doubt.
- The duplicate-ClOrdID rule (FR-FIX10) is load-bearing for safety, not just hygiene — it is
  what makes retry idempotent.
