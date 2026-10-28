# ADR-093: Order status can find an order by the client's key

Status: ACCEPTED 2026-10-28. yaakov approved option (a) "with the digest-exclusion test and the read model →
gateways → members roll order; not for any roll yet". Implemented on `traderX-risk-integration` and not rolled
anywhere. Builds on ADR-090 (FR-XOD-03, FR-XOD-14) and ADR-091. Requirements: FR-XOD-15..17 in the
[exactly-once dedupe pack](../maintenance/exactly-once-dedupe/spec.md).

## Context

On an ADR-090 sequenced stream, a resend more than W (256) behind the stream's next is answered REJECTED/DUPLICATE
with no ref. The order executed once; only its ref is lost. FR-XOD-03 tells the client to treat that answer as the
receipt and to reconcile the order by order status. No status path can find it today:

- **Read model.** The trade-processor's `OrderView` holds `(orderRef, side, quantity, remaining, status,
  security, filled)`. The `/orders` egress carries no client key, because `OutputEvent` has none. So
  `GET /accounts/{id}/orders` can only list the account's orders, and the client has nothing exact to match its
  key against.
- **FIX.** `OrderStatusRequest` resolves an order by `OrderID(37)`, or by `ClOrdID` through the gateway's in-memory
  `refByClOrdId`. That map gets an entry only when that gateway saw the ref, which is exactly what did not happen.
  Since FR-XOD-14 such a resend is answered `35=j EXACTLY_ONCE_COMMITTED_REF_UNKNOWN`, "query its status", and the
  query then answers "unknown order".
- **Binary.** There is no status request.

How often it happens: at 400K/s an open-loop stream runs about 347/s × 12–42 s (4K–15K requests) ahead of a resend
after a failover (ADR-090 §(c)). Most post-failover resends of committed orders land past W.

## Options

### (a) Carry the client key on the order's first lifecycle event (proposed)

- **Engine (egress only).** The `ORDER_ACCEPTED` that books an order gets the input's `clientOrderKey`: the client's
  u64, which for a sequenced stream is `streamId:u32 | seq:u32`. Later events of the same order (fills, cancels,
  replaces) do not carry it, so no resting-order state and no snapshot field are needed. The key is in hand only
  while the NEW is applied, and the read model indexes it once.
  - Payload: `"clientOrderKey"` on the `/orders` projection event (`OrderNatsPublisher.projectionEvent`), set only
    on the event that books the order.
  - The shadow replay (`ClusterRecon`) produces the same field from the same log, so a recon and the live feed
    still agree.
  - Replicated state, the snapshot format and the matching path do not change. Only the leader publishes, so
    this is a payload change of the leader-side bridge.
- **Read model.** `OrderUpdate` and `OrderView` keep `clientOrderKey → orderRef` per account for every order they
  retain. `GET /accounts/{id}/orders?clientOrderKey=<u64>` takes the client's RAW key and returns that order in
  the list's usual shape. An empty list means no order the read model holds was booked under that key.
  - The engine holds the key account-scoped: the gateway mixes the account in (`PendingOrder.scopeKey`,
    ENGINE-REVIEW G1). The read model applies the same function. A shared test vector pins it in both modules.
- **FIX.** `OrderStatusRequest` for a ClOrdID that is not in `refByClOrdId` derives the session's key, the one
  the gateway submits, and looks it up in the read model by key.
- **Binary.** A client queries the REST path with `streamId:u32 | seq:u32`. A binary status frame is not proposed.

Cost: about 25 B per booked order on `/orders`, and one map entry per retained order in `OrderView`. The answer is
exact for any resend delay, because it depends on the read model's retention and not on W.

### (b) W = 2,048 (ADR-090's recorded alternative)

- The engine gives the original ref back for resends up to 2,048 behind next: about 6 s of stream at 347/s.
- Cost: about 40 MB presized at 2,048 streams (84 MB at the default 8,192), and up to about 33 MB of snapshot for
  filled rings at 2,016 streams. The leader's snapshot halt grows with it, and ADR-090 exists to keep that halt in
  the 14–18.5 ms class. It must be measured on hxb against W = 256 before it is adopted.
- It narrows the gap but does not close it. Resends 12–42 s late at 400K/s still land past 2,048 and get the
  ref-less answer, so (a) or some other lookup is still needed for those.

### (c) Neither

The client reconciles by matching account, security, side and quantity in the account's order list. That is
inexact whenever two orders look alike, and the load generator's orders all look alike.

## Decision

Option (a). W stays 256. Option (b) remains an independent latency trade, taken only if hxb shows its halt cost
is acceptable.

As built:
- **Engine.** `OutputEvent.clientOrderKey` is written by `OutputPublisher.writeOrderUpdate`, the one writer of
  every order-lifecycle slot, so a reused slot never keeps a key. Only the booking emits pass the input's key:
  the four `FLAG_CREATE` sites in `MatchingEngine`, and the pre-open queued booking in
  `MatchingEngineClusteredService.emitQueuedAck`, which uses the queued row's key.
  - A replace books its new order under the replace request's own key.
  - The projection writes the key as an unsigned decimal string.
  - The recon replay projects the same bytes, because it reads the same `OutputEvent` through the same
    `projectionEvent`.
- **Read model.** `orderbook.clientorderkey VARCHAR(20)`, indexed on `(accountid, clientorderkey)`, in both the
  initial schema and `900-migrations.sql`. Like `traceId`, the key is kept from the booking across later updates:
  - in `OrderView.apply`;
  - in `OrderProjectionService.apply`;
  - in `OrderFeedHandler.preserveTraceId`.

  It is excluded from `digest` and from the three state comparisons that already exclude `traceId`
  (`sameLegacyState`, `EventRecoveryService`, `ProjectionTransitionService`).
- **FIX.** `OrderStatusSource.ordersByKey` is a default method, so other implementations are unaffected. The
  acceptor falls back to it when neither `OrderID` nor its local `refByClOrdId` names the order. It derives the
  key as the submit path does: `clientOrderKey(CompID SOH ClOrdID)`.

## Migration: every typed subscriber tolerates the field before any producer emits it

Subscribers of `/orders`, and how each would take a new field today:

| Subscriber | Parse | Absent field | New field today |
|---|---|---|---|
| trade-processor `OrderFeedHandler` → `OrderUpdate` (Jackson bean) | `@JsonIgnoreProperties(ignoreUnknown = true)` | fine | ignored: safe |
| trade-processor `OrderProjectionService.digest` (`eventDigest` per row) | `valueToTree(OrderUpdate)` minus `traceId`, `filledQuantity` | — | once the bean HAS the field, it enters the digest |
| trade-processor `EventRecoveryService.requireRetainedOrderMatches` | compares retained events with rows by digest | — | as above |
| order-matcher FIX gateway fill feed (`FixGatewayAcceptor`, `JSON.readTree`) | tree | fine | ignored: safe |
| `ClusterRecon.projectionEvents` (recon side) | produces events, does not consume them | — | — |

The trap is the digest, not deserialization. An `OrderUpdate` that gains `clientOrderKey` serializes it, even as
null, so the digest of an event retained before the change would differ from its stored `eventDigest`. Recovery
would then reject retained history. The rule follows the additive-payload lesson recorded in this project (10,812
silent drops from one additive field): **the consumer is changed first and must be indifferent to the field,
both present and absent**.

Roll order:

1. **Read model first** (trade-processor):
   - `OrderUpdate` gains `clientOrderKey`.
   - `digest` removes it, as it removes `traceId`. The key identifies the client's request; it is not economic
     event identity.
   - `OrderView` indexes it when present.
   - The REST `?clientOrderKey=` lookup answers 404 until producers emit it.

   Test: a retained event without the field and the same event with it produce the same digest. A digest test
   fails if the field is ever left in.
2. **Gateways** (FIX status lookup by key; a gateway without it answers "unknown order", as today).
3. **Members** (the leader's `/orders` projection emits the field).
   - This changes egress only, not replicated state, so a mixed-version window yields a feed where some booking
     events carry the key and some do not. Step 1 makes both safe.
   - The project rule still stands: a member roll takes a snapshot barrier and a whole-cluster restart
     (ADR-087). The members could roll by ordinal, but nothing in this change requires it.
4. Clients use the lookup.

## Consequences

- FR-XOD-03's "reconcile by order status" becomes exact. A ref-less DUPLICATE plus a key lookup gives back the
  original order's ref and state, for as long as the read model retains the order.
- Orders booked before step 3 have no key in the read model. A resend of one of those past W stays
  unresolvable, as it is today.
- Requirements: FR-XOD-15 (the booking event carries the key, with recon parity), FR-XOD-16 (the read model
  keeps and looks up by key, and the key never enters the digest), FR-XOD-17 (FIX status falls back to the key).

## Evidence

Tests on an empty-root render. Each fails with its piece of the change switched off. Suites:
- order-matcher 857 run, 6 skipped, 0 failed;
- trade-processor 144 unit tests;
- trade-processor 82 integration tests, 4 skipped, 0 failed. These include `SchemaMatchesShippedDdlIT`, which
  upgrades a YU17-shaped `orderbook` through `900-migrations.sql`. That IT caught the index first placed ahead of
  the column it needs.
- **`ClientOrderKeyEgressTest`.**
  - Only the booking ORDER_ACCEPTED carries the key: 2^64−1 projected as `"18446744073709551615"`. The resting
    order's fill and the aggressor's fill carry none.
  - A pre-open queued booking carries it too.
  - The same log through `ClusterRecon.applyAndProject` projects byte-identical events.
  - It fails with the booking sites passing 0: "the buy's booking expected -1 but was 0".
- **`ClientOrderKeyDigestTest`.**
  - An event shaped as before the change digests to `37582cbd…25b8`, the value the pre-change trade-processor
    computed, so retained history still verifies.
  - The same event with the key digests the same.
  - An unknown future field is ignored.
  - With the exclusion removed, the pre-change event digests to `491b489…`, which is the trap.
- **`OrderViewTest`.**
  - The key finds the order after a key-less fill update, and the key leaves with the order on eviction.
  - The scope vector, also pinned in `ClientKeyScopeVectorTest` on the gateway side.
- **`ScopedOrderControllerTest`.** Lookup by raw key on the SQL path (scoped to the active run) and on the memory
  path, an empty list for no order, and 400 for a malformed key.
- **`FixGatewayFillReportTest`.**
  - A booking event carrying the key is read by the fill feed as before.
  - An OrderStatusRequest for a ClOrdID this gateway never saw is answered by key (`ord-42`, FILLED). It fails
    on the previous acceptor, which answered "unknown order".
