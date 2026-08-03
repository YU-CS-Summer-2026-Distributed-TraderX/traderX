# Feature Pack: YU13-limit-order-book

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `architecture`
Lineage role: `optional`
Previous state: `YU12-aeron-cluster`

This pack replaces the matcher's price-triggered auto-fill policy with a genuine crossing
limit-order book. Each security carries a two-sided book with price-time priority: a marketable
order executes against resting opposite-side orders best-price-first, FIFO within a price level,
at the resting order's price. Partial fills leave remainders in queue position; market orders
execute immediately and cancel any unfilled remainder; the security's last price becomes an
output of matching — the last trade price — rather than the fill trigger. The book lives inside
the inherited Aeron Cluster `ClusteredService` unchanged in discipline: deterministic
(consensus-log order is the time priority), zero-allocation on the hot path, and serialized
completely into the cluster snapshot.

Primary intent:

- match orders by genuine crossing — two-sided per-security books, price-time priority, execution
  at the resting price, partial fills on both sides — driven exclusively by sequenced order flow,
- represent price levels as array-indexed tick slots with intrusive doubly-linked FIFO queues of
  pooled orders, so add, cancel, and match are O(1) and allocation-free in steady state,
- admit limit prices on a fixed 0.001 grid inside a banded per-security price window, rejecting
  off-grid (INVALID) and out-of-band (PRICE_COLLAR) prices deterministically before reservation,
- carry the book completely through the cluster snapshot — band geometry, per-security band
  anchors, and open rows whose ascending-reference order reproduces exact per-level FIFO,
- keep gateway ack correlation exact under counterparty interleaving by classing every egress
  ack as a direct response or a resting-order update.

Core artifacts:

- `generation/runtime-overrides/order-matcher/` — `LimitBook`, the crossing `MatchingEngine`,
  format-2 cluster snapshot, resting-class egress acks, gateway correlation
- `system/adr-049` … `adr-051` — crossing book, banded price-level arrays, last-trade-price output
- `system/architecture.model.json` — generated architecture flow for the crossing-book topology

Target runtime behavior:

- an accepted limit order rests in its security's book at its price level's FIFO tail unless it
  crosses; crossing executes at the resting order's price, best level first, oldest order first,
- both sides of every match receive their order update, booked trade, and position update through
  the inherited output-event pipeline; the trade bridge, read model, and UI feeds see real
  two-sided executions,
- market orders never rest: they execute against available depth and cancel the remainder,
- every member computes an identical book from the identical committed log, and a member restored
  from snapshot answers the next crossing order exactly as a never-restarted member.

## Added later — tracing across consensus, and the KDB-X capture tap

Added after this state's original implementation; specified in the addendum in `spec.md` and
decided in `system/adr-060`. Both instrument the clustered `order-matcher` and its gateway, which
is where this state's code already lives, and neither changes the wire shapes or the replicated log.

- `generation/runtime-overrides/order-matcher/.../cluster/OrderTrace.java` — trace identity, the
  member's parent span and the head sampling verdict, all derived on both tiers from the client
  idempotency key the log already carries. No trace context enters any sequenced message.
- `.../cluster/SpanSink.java` — the asynchronous sink: a producer copies a fixed record into a
  pre-allocated ring buffer and returns, a full ring drops and counts, and one daemon thread does
  every format, batch and HTTP call. Off unless `OTEL_TRACES=1`.
- `.../cluster/KdbTapWriter.java` — the leader-side, off-consensus capture tap feeding the KDB-X
  session store, which is specified in the `YU07-historical-tick-store` pack.
- `generation/runtime-overrides/kubernetes-runtime/manifests/base/observability-*` — the cluster
  tier's first Prometheus scrape, per pod through the headless service, plus its datasources and
  dashboards.
