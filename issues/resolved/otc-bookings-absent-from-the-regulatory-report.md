# OTC bookings are committed through consensus but absent from the regulatory report

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

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

## DECIDED 2026-08-21 by yaakov: IN SCOPE — the projection gains an OTC event kind

The open question above is answered: a regulatory audit surface that silently omits an entire
instrument class is the defect, not an acceptable boundary. The YU05-era projection was written
before an instrument class existed that has no orders at all; that is an explanation, not a
justification.

Work: the regulatory projection gains an OTC booking event so a reader with admin credentials can
enumerate booked OTC contracts BETWEEN EOD cuts, alongside orders. The contracts artifact remains,
but it stops being the only way to see them.

---

## Resolution — shipped 2026-08-23, verified on the cluster kind rig

`ClusterRecon` now projects booked OTC contracts onto `/regulatory/report` alongside orders. New
kinds `SWAP_BOOKED` and `SWAPTION_BOOKED`, one row per booked contract.

### The question that decided the shape: read-side, but not the obvious read-side

**Nothing is emitted.** `MatchingEngineClusteredService.onSwapBook` answers a booking with a DIRECT
egress ack and never offers to the output ring, so there is no `OutputEvent` for the projection to
have been filtering out — a fix framed as "the projection learns a seventh kind" would have had
nothing to learn.

It is a read-side change anyway, for a different reason: `regulatoryReport` replays the whole log
through a **shadow** `MatchingEngineClusteredService`, and that shadow was already APPLYING every
booking and rebuilding the contract store in full. The projection was reading the shadow's output
ring and never its contract store. So the change is: notice, around the same `onSessionMessage` the
replay already makes, that the shadow's contract store grew, and render what it grew by.

Consequences, all of them the cheap ones:

- **No emit-side change.** The deterministic core is untouched; the live apply path is byte-for-byte
  what it was. No mixed-version divergence, so **no scale-to-zero / wipe / fresh epoch was needed** —
  members were rolled in place and the epoch survived (see the proof below, which relies on it).
- **No snapshot change.** `SNAPSHOT_FORMAT` stays at 7 and `MIN_READABLE_SNAPSHOT_FORMAT` at 3, and
  the bump question is genuinely answered rather than skipped: nothing in `ClusterRecon` is
  replicated state, no record type was added, and the contract tuple did not gain a column. The
  booking TIME, the one value an audit row needs that the tuple does not carry, is taken from the
  cluster timestamp of the applying message during replay — which is why no column was needed.
- **No gateway change.** `/regulatory/*` is already proxied; the gateway was left on `:yu17-jsrebind`
  throughout and served the new rows unchanged.

### Contract grain, held

One row per booked contract, carrying its own direction and its own rate. Nothing was given a
position row. `scripts/proofs/yu17-swap-netting.sh` passes end to end, step 7 included.

Measured on the rig — the offsetting pair a position grain would collapse to a single zero-quantity
row with both rates destroyed:

```
{"kind":"SWAP_BOOKED","inputSeq":510,"orderId":"SW-510",…,"side":"RECEIVE_FIXED","quantity":50000000,"price":0.041500}
{"kind":"SWAP_BOOKED","inputSeq":511,"orderId":"SW-511",…,"side":"PAY_FIXED",    "quantity":50000000,"price":0.042800}
```

### The row, and why it reuses the existing ten columns

`AuditRow` was NOT widened. A new component would have appeared as a null on every one of the
thousands of order rows — a shape change to a surface whose whole claim is reproducibility — to
carry terms the contracts artifact already publishes in full. The mapping:

| column | OTC meaning |
|---|---|
| `kind` | `SWAP_BOOKED` / `SWAPTION_BOOKED` |
| `inputSeq` | the consensus sequence the booking landed at — the same number an order row reports, so ONE `fromSeq`/`toSeq` range selects both |
| `orderId` | `SW-<seq>` / `SWPT-<seq>`, the contract id |
| `tradeId` | `null` — a booking books no trade |
| `accountId`, `security` | the account; the convention name (`USD-SOFR-1Y-ACT360`) |
| `side` | `PAY_FIXED` / `RECEIVE_FIXED` |
| `quantity` | the notional, per contract, unnetted |
| `price` | the fixed rate — the STRIKE for a swaption |
| `timestampMillis` | cluster time of the applying message |

`orderId`, `side` and `price` render **verbatim** as `SwapContractCsv` renders them, so the report
and the contracts artifact are string-comparable rather than two vocabularies to reconcile. Verified
field for field on the rig against the same two contracts (`SW-521`/`SW-522`) in both surfaces.

**Not carried:** effective/maturity dates, the option wrapper (expiry, exercise style), float index,
day count, counterparty and netting set. Those stay in the contracts artifact. Deliberate and stated
here rather than left to be discovered.

### Proved on the rig — the before/after is the same epoch

`kind-traderx-yu12-cluster`, members rolled `:yu17-jsrebind` → `:yu17-otcaudit` in place.

1. **Before.** Booked `SW-508` (10mm receive-fixed 4.2%, acct 22214) and `SWPT-509` (25mm payer
   Bermudan, acct 42422) through the live gateway, both `booked:true`.
   `GET /regulatory/report?fromSeq=500&toSeq=520` → **18 rows, three kinds, zero at inputSeq 508/509,
   zero occurrences of `SW-`/`SWPT-`.** The issue's measurement, reproduced on the current epoch.
2. **After.** Members rolled, **same epoch, same log, same two contracts** — they were booked by the
   PRE-change build and never re-sent. Same query → **20 rows**, `SWAP_BOOKED=1 SWAPTION_BOOKED=1`,
   `"orderId":"SW-508"` and `"orderId":"SWPT-509"` present. The row is derived from the committed
   log, not from anything the new code emitted at booking time.
3. Range filtering both ways: `500..507` → 18 rows, neither contract; `508..508` → exactly one row.
4. `yu05-regulatory-reproducible.sh -v 400 511` → **rc=0**, byte-identical across two calls,
   22 records including the OTC rows, which interleave in committed-log order.

### The check that discriminates

`ClusterReconOtcTest` (8 tests) drives `ClusterRecon.applyAndProject` — the replay's own seam — with
a real service. Detonated (the projection made to ignore the shadow's contract store, i.e. the defect
as measured), **6 of 8 fail** and the rest of the module stays green. The 2 that still pass are
honestly non-discriminating for the wiring and are there for other reasons:
`aRefusedBookingProducesNoRow` asserts an ABSENCE, and
`anUnknownConventionIsNamedOpaquelyNotResolvedAndNotFatal` calls the renderer directly.

`anOtcBookingProducesNoOutputEventAtAll` is the negative control for the whole framing: it pins that
a booking emits nothing on the output ring, so any future check written against `out.kind` is
vacuous for OTC.

### `regulatoryMax` — asked, and the answer is "no silent loss, but it was sized for orders"

`REGULATORY_MAX_RECORDS` (200k) REFUSES past the bound; it does not truncate. So OTC rows share the
budget and push a wide report closer to its ceiling, but they **cannot silently evict orders to make
room** — the failure mode is a loud `IllegalStateException`, not a shorter answer. The ceiling itself
was sized for order events alone and has **not** been resized. At a handful of bookings a day that is
nowhere near close, but the number now means "records", not "order records".

### Files

- `specs/YU17-otc-rates/…/cluster/ClusterRecon.java` — NEW YU17 override layer, shadowing the YU15
  copy. It had to be a new layer: the YU15 copy cannot reference the contract store, which does not
  exist on that state. Confirmed operative by md5 against `generated/` after `generate-state.sh`.
- `specs/YU17-otc-rates/…/cluster/SwapContractCsv.java` — `RATE_SCALE` private → package-private, so
  both surfaces render the rate from one constant.
- `specs/YU17-otc-rates/…/test/…/cluster/ClusterReconOtcTest.java` — NEW.

## Still open, spun out rather than left inside this file

- A **refused** OTC booking is invisible on this surface, where a refused ORDER is not. It leaves no
  replicated state, so projecting it needs an emit-side hook that was not decided here. It is the
  same decision as the rejection-reason gap below.
  → [[a-refused-otc-booking-leaves-no-audit-trace]]
- ~~The rejection-reason gap this file documented under *"Related content gap in the same surface"*
  is **still undecided** and was deliberately NOT implemented.~~ **Decided and shipped 2026-08-23**:
  every row now carries a `riskReason`, `ACCEPTED` on the non-rejections, and it is not gated on
  `ORDER_REJECTED` (a cancel carries one too). Verified on the rig — an unknown account and a
  good account outside the band render as two different strings.
  → [[the-audit-surface-records-that-an-order-was-refused-not-why]]
