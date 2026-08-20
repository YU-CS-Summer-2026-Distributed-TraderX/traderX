# OTC bookings are committed through consensus but absent from the regulatory report

**Found 2026-08-19 by the coordinator**, while checking whether the UI lane's claim that swaps are
exempt from the "does this preset produce a print" test also meant they had a durable read surface.

## Measured, positively — not inferred from absence

Booked one swap through the live gateway on the `kind-traderx-yu12-cluster` rig:

```
POST /swaps  {clientOrderId: coord-readpath-probe-1, accountId: 22214, payReceive: Receive,
              notional: 10000000, fixedRate: 0.042, ...}
-> {"contractId":"SW-19864","sequence":19864,"booked":true}
```

Then pulled the regulatory report over a range that brackets it:

```
GET /regulatory/report?fromSeq=19800&toSeq=19900   (admin JWT)
-> zero events at inputSeq 19864, zero occurrences of "SW-19864"
```

A full-journal pull (`fromSeq=1&toSeq=99999`, 979 KB, 5,268 events) carries exactly six kinds:

```
2585 ORDER_ACCEPTED   2507 ORDER_CANCELED   80 TRADE_BOOKED
  52 ORDER_FILLED       28 ORDER_PARTIALLY_FILLED   16 ORDER_REJECTED
```

No swap kind, no swaption kind, no `SW-` or `SWPT-` identifier anywhere in the whole report.

The booking is real: it consumed consensus sequence 19864 and returned `booked:true`. So this is not
"the swap did not happen" — it is "the audit projection does not enumerate it."

## Where a booked contract IS visible

Only in the **EOD risk extract contracts artifact** — a separate CSV from the netted positions
artifact, one row per contract (`contractId, accountId, payReceive, notional, fixedRate, …`),
rendered byte-identically by all three members at a consensus sequence. That is what
`scripts/proofs/yu17-swap-netting.sh` step 6 asserts against.

Not in: the DB (there is no swap or contract table — `SHOW TABLES` on `eod-price-db` lists only the
order/trade/position/EOD tables), the regulatory report, or any GET route. `/swaps` and `/swaptions`
are POST-only booking ingress; `ClusterGatewayMain` registers no read context for either.

## Why the position grain is NOT the fix

Deliberate design, and `yu17-swap-netting.sh` step 7 exists to protect it: a receive-fixed and a
pay-fixed at equal notional net to **zero** at position grain, destroying both rates. Swaps are
carried at contract grain precisely so that cannot happen. Any proposal to surface OTC by giving it
a position row reintroduces the bug that proof was written to catch.

## The open question

Whether the regulatory report is *supposed* to cover OTC. It is a YU05-era projection over the
order lifecycle (FR-PTC04/05/10/20/21), written before an instrument class existed that has no
orders at all. Two readings:

- **In scope** — a regulatory audit surface that silently omits an entire instrument class is the
  defect, and the projection needs an OTC event kind.
- **Out of scope** — the contracts artifact is the OTC audit surface by design, and the gap is only
  that nothing says so.

Either way the current state is that a reader with admin credentials cannot enumerate booked OTC
contracts between EOD cuts. Someone deciding this should decide it, rather than it staying decided
by omission.

## The class this belongs to

Additive event type, consumer that enumerates the old kinds — the same shape as
`issues/` "additive NATS payload vs strict consumer", where an additive field was fatal to a typed
subscriber. Here the consumer does not crash; it just renders a shorter list, which is quieter and
therefore worse. **A projection that enumerates kinds needs a test that fails when a new kind is
added**, otherwise every future instrument class is silently absent from it too.

## Rig delta

`SW-19864` on account 22214 (10mm receive-fixed 4.2%, 2026-08-17 → 2031-08-17) is left booked on the
rig by this investigation. Harmless — it is the same shape the netting proof books — but it is one
extra contract in the next EOD contracts artifact.

## Related content gap in the same surface: a rejection carries no reason

Measured 2026-08-19. `ORDER_REJECTED` events in the regulatory report carry exactly these fields:

```
accountId, inputSeq, kind, orderId, price, quantity, security, side, timestampMillis, tradeId
```

There is **no `riskReason`**. The engine decides rejections with a specific reason
(`UNKNOWN_ACCOUNT`, `PRICE_COLLAR`, `ORDER_SIZE`, `RESTRICTED`, `PRICE_MISSING`, …) and returns it on
the order path, but the audit projection drops it.

Consequence, hit while triaging real rejections: from the audit surface you can see **that** an order
was refused and never **why**. Distinguishing "the risk gate refused an unknown account" from "the
price collar refused a good account" required reconstructing it from the accepted/rejected price
ranges per security — an inference, not a record.

For a surface whose purpose is a reproducible regulatory audit, "refused, reason not recorded" is a
weaker claim than the surface implies. Same decision as the OTC question above: either the reason
belongs in the projection, or the report's scope should say plainly that it is an order-lifecycle
trace and not a decision record.
