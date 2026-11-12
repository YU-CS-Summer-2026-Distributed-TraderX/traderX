# ADR-100: The order-reference generator fails closed at 2^31-1

Status: implemented on `traderX-risk-integration` 2026-10-30 (codeX review §13); not deployed. Amended 2026-10-31
(codeX pass 3 §15): the ceiling is checked AFTER deduplication, so a committed request recovers its answer with no
ref and only a genuinely new request is refused (§1a). Amended 2026-11-11 by
[ADR-105](adr-105-64-bit-order-references.md): refs are 64-bit, from 2^32 + 1, and this decision's refusal now guards
the ceiling 2^53 - 1 (decision 1's "not widen" is superseded; its fail-closed branch is kept). Requirements:
[engine-review hardening pack](../maintenance/engine-review-hardening/spec.md), FR-ERH-F13. Base: ADR-046 (the
generator is replicated state advanced by the committed message), ADR-072 (operator vs replayed-tape refs), the
FR-DRS-33 recon note on ticket bookings.

## Context

`MatchingEngineClusteredService` issues each ORDER_NEW its reference as `(int) nextOrderRef++`: a `long` generator
cast to the `int` the wire carries (SBE input, egress ack bytes 8..11, snapshot order rows, `<epoch>-<ref>` on the
NATS read model). Nothing checked the cast. From 2^31 the issued refs went negative; at 2^32 a real order was issued
ref 0, which is the reserved "no order" reference every `/trades` ticket booking (TYPE_TRADE_NEW) carries and which
the FR-DRS-33 recon excluded by the suffix `'%-0'`; at 2^32+1 ref 1 was issued again while idempotency and the read
model still held the original. Duplicate retries and session refusals consume refs too (by design, ADR-046/069), so
at 430K/s the ceiling is ~1.4 h to 2^31 and ~2.8 h to 2^32 inside one epoch. codeX's repro set `nextOrderRef` to
2^32 and watched two trades carry refs [1, 0].

## Decision

1. **Fail closed at `MAX_ORDER_REF = Integer.MAX_VALUE`, not widen.** Widening to a 64-bit ref changes the SBE
   schema, the ack layout, every snapshot order row and tuple (a format bump), the FIX/binary client decoders and
   the read-model key: a fresh epoch on every tier plus every client. Failing closed changes one apply-path branch
   and one appended reason, needs the same fresh-epoch same-image roll any core change needs, and is a ceiling
   no deployment has approached: an epoch that issues 2^31-1 references at 400K/s has run 1.5 h of pure order
   entry, and the production day is the epoch boundary. A NEW that arrives once `nextOrderRef > MAX_ORDER_REF` is
   refused before the stream gate and before the generator, with `RiskReason.ORDER_REF_EXHAUSTED` (appended) on
   the egress ack and ref 0: no ref, no `appliedSeq`, no engine, the same no-state-change posture as an ADR-090
   stream gap. Cancels and replaces of live orders still apply; the epoch is closed to new orders only.
   1a. **Deduplication first.** The first cut checked the ceiling ahead of both deduplication paths, so a retry
   of the LAST accepted order (its ack lost in an election) was answered ORDER_REF_EXHAUSTED with ref 0 while
   the order existed; a client reading that as "not booked" could place a duplicate (codeX pass 3 §15, reproduced
   in-process). Now the stream gate runs first, and at the ceiling a NEW whose key is committed — decided in the
   idempotency table (`knowsKey`, accepted or rejected), waiting in the PRE_OPEN queue, or a sequenced resend
   whose ring holds its answer (`sequencedDuplicate`) — continues into the engine or the queue WITHOUT a ref:
   both answer a duplicate from the key, never from `event.orderRef`, so the original ack (or the original
   rejection) is re-emitted. Only a key none of them knows is refused. A sequenced NEW refused this way records
   ORDER_REF_EXHAUSTED in its stream ring (`BlpRiskState.refuseSequenced`), so its go-back-N resend recovers the
   same refusal rather than a bare DUPLICATE. Every input to the decision is replicated state, so members and
   replay agree.
2. **The restore side refuses a wrapped header.** `finishLoad` throws when `nextOrderRef` is outside
   `[1, MAX_ORDER_REF + 1]` or `highestIssuedRef > MAX_ORDER_REF`: a snapshot written by a wrapping build already
   holds duplicate references and is not inherited. `MAX_ORDER_REF + 1` itself is a legitimately exhausted epoch and
   restores to a member that keeps refusing.
3. **Headroom is observable.** `traderx_cluster_order_ref_headroom` (member `/metrics`) = refs the epoch can still
   issue; alert on it long before 0 (at 400K/s, 2^31 is ~1.5 h of continuous order entry).
4. **Ticket bookings carry an explicit category.** `TradeNatsPublisher` publishes `bookingSource: TICKET | MATCH` on
   every `/trades` payload. A ticket carries NO `sourceOrderId` (it has no originating order; the SQL join key is
   null), a match carries `<epoch>-<ref>`. Inside the engine, `orderRef == 0` on a KIND_TRADE_BOOKED can only be a
   ticket because of decision 1, so the replay verifier (`projectionEvent`) derives the same bytes. The FR-DRS-33
   recon's orphan query now relies on `sourceorderid IS NOT NULL`; its `NOT LIKE '%-0'` line remains only for rows
   published before this ADR and is marked for removal. `bookingSource` is additive: trade-processor's `TradeOrder`
   ignores unknown properties (`@JsonIgnoreProperties(ignoreUnknown = true)`, the additive-payload note on the class), history-feed's `Rows` skips unknown keys and
   defaults the ref to 0 as it did for `<epoch>-0`. No SQL column is added (`ddl-auto=validate`).

## Consequences

- A deterministic-core change: every member must apply the same branch, so it rolls as a fresh epoch with the
  same image on all three members (ADR-087 OnDelete migration order), never a mixed roll. No snapshot format change.
  On production (2026-10-31) the fresh epoch archives the old one and resets, per ADR-101 (FR-PRG-29).
- The appended ordinal `ORDER_REF_EXHAUSTED` is new on the ack byte 22 and in snapshot order rows written after the
  roll; a gateway or client decoding reasons by table must carry the new value (it maps to a rejection like any).
- Pre-ADR `/trades` ticket rows in SQL keep `<epoch>-0`; new ones have a null `sourceorderid`. Managed (epoch-v1)
  projection scopes never accepted a ticket trade (`RunRegistry` refuses a non-positive ref), unchanged.

## Evidence

`OrderRefExhaustionTest`: with `nextOrderRef = 2^31-1` the last ref is issued, three further NEWs are refused with
ORDER_REF_EXHAUSTED and ref 0, `appliedSeq` does not move for them, a cancel of the last order still applies; the
exhausted state survives a snapshot round trip and a header with `nextOrderRef = 2^32` is refused at load; a
ticket payload carries `bookingSource: TICKET` and no `sourceOrderId`, a match carries both. Negative control
(same test against the previous service): the three NEWs were issued -2147483648, -2147483647, -2147483646.
§1a: `retryOfACommittedKeyRecoversItsAnswerWithoutARef` (retry of the last accepted key is re-acked ACCEPTED
with its ref and consumes nothing; a new key is refused, and so is its retry; a PRE_OPEN-queued key's retry is
answered from the queue) and `sequencedResendRecoversItsAnswerAtExhaustion` (resend of the accepted sequence is
answered from the ring; the next sequence is refused, its resend recovers the refusal, the one after is refused).
Negative control against the first cut: both answered the retry with a fresh rejection (kind 2 for 1).
