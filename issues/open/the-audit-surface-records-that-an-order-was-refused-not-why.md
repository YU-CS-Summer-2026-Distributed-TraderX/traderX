# The regulatory report records THAT an order was refused, never why — and the reason is already in hand

**Split out of [[otc-bookings-absent-from-the-regulatory-report]] 2026-08-23.** That file documented
this gap under *"Related content gap in the same surface"* and said it needed the same decision the
OTC half got. yaakov decided only the OTC half, so this stays open. Recording it here so a live gap
does not sit inside a resolved file.

## The gap (measured 2026-08-19, unchanged)

`ORDER_REJECTED` rows on `/regulatory/report` carry exactly:

```
accountId, inputSeq, kind, orderId, price, quantity, security, side, timestampMillis, tradeId
```

There is **no `riskReason`**. From the audit surface you can see that an order was refused and never
whether the risk gate refused an unknown account or the price collar refused a good one.
Distinguishing those required reconstructing it from accepted/rejected price ranges per security —
an inference, not a record.

## Assessment, done 2026-08-23 while implementing the OTC half

**It is cheap, and cheaper than the OTC half was.** Confirmed by reading the emit path, not assumed:

- `OutputEvent.riskReason` **already exists** (`OutputEvent.java:59`, "RiskReason ordinal (order
  lifecycle + trade decision kinds)") and is **already populated** for rejections —
  `OutputPublisher.emitRequestRejected` sets `e.riskReason = riskReasonOrdinal` before publishing.
- The shadow replay drains those same events through the same publisher, so the reason is **already
  in `ClusterRecon.auditRow`'s hand** on every rejection it renders. It is dropped on the floor.
- Confirmed live on the rig the same day: `POST /orders` on an unknown account →
  `{"orderRef":11,"kind":2,"reason":"UNKNOWN_ACCOUNT"}` (422). The engine decides it, the ack carries
  it, the projection discards it.

So unlike the OTC half — which needed a second source (the shadow's contract store) because nothing
was emitted at all — this is a **pure rendering omission in one method**. Read-side only. No
emit-side change, no snapshot implication, no wire change, no fresh epoch.

### What it would touch

1. `ClusterRecon.auditRow` — one field, mapping `out.riskReason` through `RiskReason.values()[…]`.
2. `ClusterRecon.AuditRow` — and this is the only real design question. **It needs a new component**,
   because unlike the OTC row there is no spare column to reuse: every one of the ten is already
   carrying an order's own value on a rejection.
3. `specs/YU05-post-trade-compliance/…/reporting/AuditLogHandler.java` and the Spring tier's
   `AuditRecord` — `ClusterRecon`'s javadoc states the two tiers' field names mirror each other
   "so a proof written against one tier reads the other without being told which it is talking to".
   Changing one and not the other breaks that, so this is **not** a tip-only change: it needs
   propagation, which the coordinator holds.
4. `scripts/proofs/yu05-regulatory-reproducible.sh` hashes the export; a new field changes the hash.
   That is fine (it re-hashes per run) but any recorded baseline hash goes stale.

### The decision that is actually being asked

Widening `AuditRow` puts a new key on **every** row — ~5,268 of them in a full-journal pull, of which
16 were rejections. Three options, in increasing cost:

- **`riskReason` present on every row** (`ACCEPTED` for the rest). Honest, uniform, and the shape a
  regulator reading a decision record would expect. Changes the JSON shape of every existing row.
- **`@JsonInclude(NON_NULL)`, so only rejections carry it.** Existing rows are byte-identical; the
  key simply appears where there is a reason. Cheapest for consumers, slightly less uniform.
- **Say plainly in the report's own contract that it is an order-lifecycle TRACE and not a decision
  record**, and leave the reason to the ack. This is the "out of scope" arm and it is still available
  — the OTC decision does not force this one.

The OTC half chose *not* to widen, but that reasoning does not carry over: it declined because the
terms it was dropping are published in full by the EOD contracts artifact. **A rejection reason is
published nowhere durable at all** — it exists only in the synchronous ack, which nobody retains.
That is the asymmetry the decider should weigh.

## Related

- [[otc-bookings-absent-from-the-regulatory-report]] — the half that was decided and shipped.
- [[a-refused-otc-booking-leaves-no-audit-trace]] — the same question for OTC, and strictly harder:
  there the reason is not even emitted.
