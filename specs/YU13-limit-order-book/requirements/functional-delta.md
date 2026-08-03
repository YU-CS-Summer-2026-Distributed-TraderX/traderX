# Functional Delta: YU13-limit-order-book (vs YU12-aeron-cluster)

Parent: `YU12-aeron-cluster`

The inherited surfaces keep their parent-state contracts: the three-member Aeron Raft cluster, the
`ClusteredService` hosting the deterministic engine, the REST and FIX order-entry surfaces, the
committed-egress pipeline, and the leader trade bridge. What changes sits behind those wire shapes
— orders are now matched by a genuine crossing limit-order book with price-time priority instead of
being auto-filled against a reference price — plus one added subject and one change in egress
volume, both noted below.

## Added

- A two-sided limit-order book per security; an accepted limit order that does not cross rests at
  its price level's FIFO tail.
- Price-time priority crossing: a marketable order fills against resting opposite-side orders
  best-price-first and FIFO within a level, at the resting order's price.
- Each match step fills `min(aggressor remaining, resting remaining)`, so partial fills leave the
  remainder in its earned queue position rather than re-queuing it.
- Both sides of every match receive an order update, a booked trade with its own trade sequence
  number, and a position update.
- The resting side's order update carries `FLAG_RESTING_UPDATE`, so gateway ack correlation can
  tell a counterparty update from a direct response.
- Market orders (no limit price) execute immediately against available depth and cancel any
  unfilled remainder in place, never resting at an undefined price.
- Risk validation of a market order prices it at the last trade price, falling back to the opposite
  best, and rejects `PRICE_MISSING` when neither exists.
- A leader-side `/orders` order-lifecycle bridge feeding the `orderbook` SQL projection and
  `GET /accounts/{id}/orders`, giving order state a home outside the cluster.
- Price admission before any risk reservation: an off-grid limit rejects `INVALID` and an
  out-of-band limit rejects `PRICE_COLLAR`, so fat-fingered prices fail deterministically.
- Array-indexed price levels on a fixed 0.001 grid inside a banded window, holding intrusive FIFO
  queues of pooled orders, giving O(1) add, cancel and match with a zero-allocation steady state.
- Global self-trade prevention by cancel-oldest: an aggressor meeting its own account's resting
  order cancels that order and continues into the liquidity behind it (ADR-057).
- The cancel carries `RiskReason.SELF_TRADE_PREVENTED`, appended to the enum and never inserted,
  because an inserted ordinal misdecodes every order row already snapshotted.
- That cancel is flagged an unsolicited resting update, so a client reads canceled plus
  resting-class 1 plus `SELF_TRADE_PREVENTED` as a venue-prevented self-trade.
- Atomic order replace in one sequenced command, over REST `POST /replace` and FIX
  `OrderCancelReplaceRequest` (`G`), with the order keeping its `orderRef` (ADR-058).
- Shape, band and risk on a replace are all evaluated before anything is mutated, so a rejected
  replace leaves the original order bit-identical, its reservation included.
- Queue priority survives a replace only on a strict size-down at an unchanged price; every reprice
  or size-up re-appends the order at its level's tail.
- O(1) unlink of an open resting order on cancel, and force-fill of an open order at the last trade
  price, falling back to its own limit price when nothing has printed yet.

## Changed

- Price ticks no longer trigger fills. A tick feeds risk price-freshness and seeds a security's
  mark only until its book first trades; after that the last trade price is the mark (ADR-051).
- The cluster snapshot moves to format 2, carrying book geometry in its header and a band anchor
  before each created book's order rows.
- Open rows travel in ascending-reference order so restore rebuilds every price level's exact FIFO
  and answers a post-restore sweep like a never-restarted member.
- Restore fails closed on an off-grid or out-of-band open row, and rejects a legacy format-1
  snapshot wholesale, since cross-format restore is not a supported flow.
- Every committed egress ack now carries a resting-update class byte, so gateway offer/ack and
  pipelined-batch accounting counts only direct responses and stays exact under two-sided flow.
- The booked-fill metric counts both sides of a cross, matching the two trades a single match
  actually books.
- The leader trade bridge publishes two `/trades` messages per cross, each keyed by `tradeSeq` and
  side, where the parent published one; `trade-processor` dedup absorbs it unchanged.
- The replicated duplicate-suppression window grows from 1,024 to 256Ki entries and is global
  across sessions, with its snapshot cost measured at 28 bytes per entry rather than assumed.

## Added later — tracing across consensus, and the KDB-X capture tap

Added after this state's original implementation; see the addendum in `spec.md`. Neither changes
the wire shapes, the replicated log, or what the state machine reads.

- One order produces one distributed trace spanning the gateway and the member: a root span for
  the residence the client experiences, the gateway's queue span, the consensus black box, and the
  member's commit and apply spans beneath it.
- Trace identity, the member's parent span and the head sampling verdict are **derived** on each
  tier from the client idempotency key the log already carries, not carried in the message. Adding
  a `traceparent` field would be a schema change, a member roll and a standing determinism risk
  taken on behalf of a debugging feature — and a resend carrying a fresh id would no longer be
  byte-identical, so replay would stop reproducing.
- The member derives that key before the sequenced generator overwrites `orderRef`, so both tiers
  hash identical input.
- A rejected order is force-sampled by both tiers independently, because "was it rejected" is
  likewise a committed, deterministic fact read off the same ack.
- A log line joins its trace by computing the id and carrying it in the line, so correlation needs
  no new log label and no new field in any message.
- Prometheus scrapes the cluster tier for the first time, per pod through the headless service, so
  role, applied sequence and `traderx_cluster_next_order_ref` reach a dashboard as per-member
  facts rather than a round-robin average.
- `KdbTapWriter`, the leader-side capture tap feeding the KDB-X session store, sits beside
  `TradeNatsPublisher` and `OrderNatsPublisher` in the same output-ring drain: leader-only,
  off-consensus, best-effort and non-blocking. The store it writes into is specified in the
  `YU07-historical-tick-store` pack.

The gateway half of both lands on every descendant, since this state's `ClusterGatewayMain` is the
operative copy throughout. The member half of each sits in `MatchingEngineClusteredService`, which
`YU13`, `YU14` and `YU15` each override — so a whole trace crossing consensus is live on generated
`YU15` only, and the tap is wired on generated `YU13` and `YU15` but not `YU14`. See the layer
coverage table in `generation/implementation-status.md`.
