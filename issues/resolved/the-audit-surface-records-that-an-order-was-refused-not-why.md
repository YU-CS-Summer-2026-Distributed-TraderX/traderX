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

---

## Resolution — shipped 2026-08-23, verified on the cluster kind rig

**The `riskReason` arm was taken, on EVERY row, with `ACCEPTED` on the non-rejections.** Not the
`@JsonInclude(NON_NULL)` arm, and not the "it is a trace, not a decision record" arm.

### The assessment above was re-verified before it was acted on, and it held

Layers sorted by YU number, not by `find` order:

| claim | operative layer | line |
|---|---|---|
| `OutputEvent.riskReason` exists | **YU13** (not YU01/02/03, which also carry the file) | `public byte riskReason;` |
| `OutputPublisher.emitRequestRejected` populates it | **YU12** | `e.riskReason = riskReasonOrdinal;` |
| `ClusterRecon` never mentions it | **YU17** | zero hits |

So it really was a pure rendering omission: read-side only, no emit-side change, no wire change, no
snapshot implication, **no fresh epoch required** for correctness (one was minted anyway, to keep the
verification off another lane's 61,277-order book).

### Why `ACCEPTED` and not `NONE` or an empty string

The choice looks like a placeholder question and is not one. **`RiskReason` ordinal 0 IS `ACCEPTED`**,
and `OutputPublisher` writes `e.riskReason = 0` on the accepted paths (lines 127, 244, 274). So a
non-rejection row renders **the engine's own byte**, decoded the same way a rejection's is — there is
no branch, no default, and nothing synthesized at the read side.

`NONE` and `""` are both values the enum does not have, so both would have to be invented in the
projection, and `""` in particular is indistinguishable from *"the reason byte could not be read"* —
the one state the column must be able to say out loud. The `NON_NULL` arm was rejected for the same
reason plus a second one: see below.

### The column is NOT gated on `ORDER_REJECTED`, and that is the point

The issue was written as "why was an order *rejected*". That framing is too narrow, and following it
would have needed a second widening within weeks:

- **`SELF_TRADE_PREVENTED` already rides a `ORDER_CANCELED` row today** (YU13/ADR-057) — the venue
  removed a resting order, and the reason byte was already there, already dropped.
- **ADR-069 decision 7 adds a second cancel cause**: a resting order cancelled by the session
  transition at the open. A trader whose order vanished deserves to see that the session did it.

Rendering the byte on every row gets both for free, and is why the `NON_NULL` arm was the wrong
shape rather than merely the cheaper one: "present only on rejections" is a rule that was already
false when it was proposed.

### What changed

| file | operative layer | change |
|---|---|---|
| `ClusterRecon.java` | YU17 | `AuditRow` gains an 11th component `riskReason`; `auditRow` maps `out.riskReason` through the new `reasonName`; `otcAuditRow` renders `ACCEPTED` |
| `AuditRecord.java` | YU05 | mirror component + mapping in `fromEvent`, so the two tiers' field names still match |
| `ClusterReconAuditReasonTest.java` | YU17 | **new** — pins discrimination, the `ACCEPTED` choice, the cancel case, the ordinal guard |
| `AuditLogHandlerTest.java` | YU05 | two tests, same properties, through the full event → record path |

`AuditLogHandler` itself needed **no** change — it delegates to `AuditRecord.fromEvent`. So the
propagation surface was two main files, not the three the assessment predicted.

**The ordinal is bounds-checked in both tiers.** It arrives off the wire and out of snapshots, so a
build that appends a reason this one does not know yields an out-of-range byte; `regulatoryReport`
renders the whole range in one pass, so throwing would blank every row over one byte of one of them.
Out-of-range renders `UNKNOWN_<n>`, the same shape `kindName` already uses.

### Verified — and the verification was built to be able to fail

The failure this surface exists to fix is *two causes rendering the same string*, which a naive test
passes. So every assertion is on the **difference**, never on non-emptiness, and the mapping was
mutated to `return "REJECTED";` in both tiers to confirm the tests go red: **6 of 6 failed**, all four
new cluster-tier tests and both new Spring-tier ones. Restored, the full hosted suite is **417 tests,
0 failures, 4 skipped**, across 82 suites.

On the rig — `kind-traderx-yu12-cluster`, fresh epoch on `traderx/cluster-node:yu17-auditreason`,
members + gateway + risk-extract + feed-adapter all repinned, fixtures reseeded:

```
POST /orders acct 999123 (never sequenced)      -> {"orderRef":13,"kind":2,"reason":"UNKNOWN_ACCOUNT"}
POST /orders acct 22214  IBM @ 9000 (seed 200)  -> {"orderRef":14,"kind":2,"reason":"PRICE_COLLAR"}
POST /orders acct 22214  IBM @ 200              -> {"orderRef":15,"kind":1}

GET /regulatory/report?fromSeq=3166&toSeq=3172
  keys: accountId inputSeq kind orderId price quantity riskReason security side timestampMillis tradeId
  ORDER_REJECTED seq=3167 acct=999123 px=200.0  riskReason='UNKNOWN_ACCOUNT'
  ORDER_REJECTED seq=3171 acct=22214  px=9000.0 riskReason='PRICE_COLLAR'
  ORDER_ACCEPTED seq=3172 acct=22214  px=200.0  riskReason='ACCEPTED'
```

The risk gate refusing an unknown account and the band refusing a good one are now **two different
strings on the surface**, which is the whole ask. The cancel arm was driven too — rest a Sell 7 @ 201
and aggress the same account:

```
  ORDER_ACCEPTED  seq=3312 riskReason='ACCEPTED'
  ORDER_ACCEPTED  seq=3313 riskReason='ACCEPTED'
  ORDER_CANCELED  seq=3313 riskReason='SELF_TRADE_PREVENTED'
```

`scripts/proofs/yu05-regulatory-reproducible.sh` still **PASS**es against the widened row (it
re-hashes per run; no baseline hash went stale).

### What this did NOT close

[[a-refused-otc-booking-leaves-no-audit-trace]] is untouched, and that was **measured on the same
rig after the change**, not assumed:

```
POST /swaps acct 999123 -> {"booked":false,"reason":"UNKNOWN_ACCOUNT"}
POST /swaps acct 22214  -> {"contractId":"SW-3655","booked":true}
GET  /regulatory/report over the bracketing range -> 1 row
  SWAP_BOOKED SW-3655 riskReason='ACCEPTED'
```

One row for the booking that succeeded, **zero for the one that was refused**. It is a genuinely
different mechanism: a refused booking grows no replicated state, so the shadow replay has nothing to
observe and there is no row to hang a reason on. It still needs the emit-side change or the second
read-side tap that file describes. Left open, deliberately.

### Residual

- The rig verification is a **one-off**, not a committed proof script. The discrimination property is
  pinned in CI by the unit tests at both tiers; nothing in `scripts/proofs/` asserts it end to end.
  `yu05-regulatory-reproducible.sh` checks reproducibility, not content, and was not widened.
- The report's ~200k record ceiling is unchanged. One more short string per row costs bytes, not
  records.
