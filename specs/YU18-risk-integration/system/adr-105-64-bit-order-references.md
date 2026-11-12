# ADR-105: Order references are 64-bit, start at 2^32 + 1, and fail closed at 2^53 - 1

Status: implemented on branch `orderref-64bit` (off `traderX-risk-integration` 074b6fd5c), 2026-11-11; not deployed.
yaakov approved the build in the coordinator's chat ("Yes, build it") after the design note
`orderref-64bit-design-20261111.md` in the lane's shared notes. Amends [ADR-100](adr-100-order-ref-exhaustion.md),
whose fail-closed branch stays as the guard at the new ceiling. Requirements:
[engine-review hardening pack](../maintenance/engine-review-hardening/spec.md), FR-ERH-F17 (and FR-ERH-F13,
amended).

## Context

L0 hit ADR-100's ceiling on slot A at 3:50 pm ET on 2026-11-11: at applied 2,147,483,957 the leader refused every
new order. At 450K/s an epoch issues 2^31 references in about 80 minutes. A duplicate retry and a session refusal
consume a reference too (ADR-046/069), so the limit counts references, not orders. A regular session at the target
rate needs about 10.5 billion (6.5 h × 450K/s), about 34 bits, and an extended day about 26 billion. A per-day epoch
does not help: one day overruns 32 bits about five times. It would also empty the engine every day, losing GTC
orders, positions, idempotency and the session rings, because an ADR-101 fresh epoch starts empty.

The reference was already wide in most places:
- the read model's `<epoch>-<ref>` string, `OrderNatsPublisher`, trade-processor and its `RunRegistry`;
- history-feed's `Long.parseLong`, ClickHouse `order_ref UInt64`, SQL `VARCHAR(50)`;
- the snapshot's `T_ORDER`, `T_IDEMPOTENCY`, `T_STREAM`, queue tuples and header (8-byte columns).

A NEW never carries a ref on the log, because the ref is assigned in the replicated apply. The int lived in the
engine (`InputEvent`/`OutputEvent`/`RestingOrder`, the `Int2ObjectHashMap` index, the terminal ring), the risk
tables (idempotency, the ADR-090 stream rings, the ADR-104 session rings), the log's `uint32` field, the 32-byte
egress ack, the binary wire, the FIX gateway's maps and parsers, REST's `asInt()`, and `TradeNatsPublisher`.

## Decision

1. **64-bit everywhere it is held or carried.** `long` in the engine, risk and gateways; `Long2ObjectHashMap` and
   `long[]` (allocation-free, sized at start, so the allocation gates are unchanged).
2. **The ceiling is `MAX_ORDER_REF = 2^53 - 1`**, not 2^63 - 1, so a ref stays exact as a JSON number in JavaScript
   (the console, the load drivers). That is 634 years at 450K/s. ADR-100's refusal (`ORDER_REF_EXHAUSTED`,
   deduplication first) is unchanged at it.
3. **A fresh epoch's first ref is `FIRST_ORDER_REF = 2^32 + 1`**, a code constant, so it is deterministic. Every order
   then carries a wide ref from the first one: a missed int anywhere fails on order one, in every test and rig run,
   instead of hours into a trading day. The epoch's first ref is replicated (format 17 header at 68), and an epoch
   restored from format 16 or older keeps first ref 1. `operatorOrderRefs` = `nextOrderRef - firstOrderRef -
   externalOrderRefs`, so it is still a count. Ids gain digits, and delta readings are unaffected.
4. **Log: a flagged 8-byte tail, no schema version.** When a ref is above 32 bits the encoder sets
   `INPUT_FLAG_WIDE_REF` (32) and appends `u64 orderRef`. The `uint32` field then carries the low half. Templates 1
   and 9 are unchanged, so every narrower message and every old log replays byte for byte.
   - Only CANCEL and REPLACE name a ref, and neither takes the ADR-090 or ADR-104 tail, so the tails are exclusive:
     the encoder throws on the combination and the decoder refuses it.
   - One encoding per ref: a tail naming a ref that fits 32 bits, or a negative one, is `WRONG_LENGTH`.
   - A member older than this refuses the flag (`UNKNOWN_FLAGS`).
5. **Snapshot format 17.**
   - The session ring's packed entry carries its ref as a long: 25 bytes, 53 entries a record, still one packed
     message (FR-XOD-18).
   - The header appends the first ref.
   - A format 16 snapshot still restores. Its ring records are read at 21 bytes, its first ref is 1, and
     `MIN_READABLE_SNAPSHOT_FORMAT` stays 9.
6. **Egress ack 40 bytes (ack format 2).**
   - Every field keeps its offset. 8..11 hold the ref's low half (or the int a non-order ack carries there), 32..35
     the high half, and 36..39 the format word `EGRESS_ACK_FORMAT`.
   - `putAckRef`/`ackRef` are the only writer and reader of the ref. Every reader checks `ackFormatOk`: the gateway,
     `ClusterProofClient`, `FeedAdapterMain`, `RiskExtractMain` and the test capture.
   - The word exists because a coalesced message from a 32-byte member (CLUSTER_EGRESS_COALESCE) can split into
     records of the right length: 4 × 40 = 5 × 32 = 160. A length check alone would read garbage.
7. **Binary wire: new type codes.**
   - NEW 0x11, same layout.
   - CANCEL 0x12, 20 bytes: u64 ref at 4, clOrdId at 12.
   - REPLACE 0x13, 32 bytes: u64 ref at 4, qty at 12, px at 16, clOrdId at 24.
   - ACK 0x82, 20 bytes: u64 ref at 4, clOrdId at 12.
   - The 32-bit codes 1, 2 and 3 are answered with a 0x81 ack in their own 16-byte layout, status protocol-error and
     clOrdId echoed. An old client therefore fails on its first frame rather than reading a truncated ref.
   - The OUT_OF_SEQUENCE answer's expected sequence rides the u64 ref.
   - Updated tools: `binary-load.mjs` (acceptance-450k's, told before the change), `bin-multi.mjs` and
     `binary-latency-probe.mjs`.
8. **REST.** `/cancel` and `/replace` read the ref with `requestOrderRef`. It accepts a JSON integer or digits in
   `[1, 2^53 - 1]`, and anything else is a 400. `asInt()` kept the low 32 bits, so 2^32 + 5 named order 5.
9. **The retired single-BLP tier stays 32-bit**: its journals, read model and ledger. It refuses a wider ref loudly
   (`Math.toIntExact`) instead of widening code no deployment runs.

## Consequences

- **A deterministic-core change:**
  - It rolls like ADR-100: the same image on all three members and the gateways together, never mixed (the 40-byte
    ack and the format word refuse a mixed fleet loudly in one direction; an old 32-byte reader cannot be fixed).
  - **Recommended roll: an ADR-101 fresh epoch** (`rig.sh archive-members`, `fresh-epoch`, then `up`). The formats
    would let an epoch continue from a format 16 snapshot.
  - **Never roll in place an epoch that reached headroom 0.** Its log holds NEWs the old build refused, which this
    build would accept on replay.
- **Clients:**
  - Binary clients must move to the new type codes before the roll.
  - FIX clients see a longer `OrderID` (`ord-4294967297`), an opaque string.
  - REST clients see longer `orderRef` numbers, exact in JavaScript.
- **Downstream:** no JSON field is added or renamed, so trade-processor, history-feed, ClickHouse and SQL need no
  change and nothing downstream rolls.
- **Cost:**
  - **Log:** 0 bytes per NEW, +8 per CANCEL/REPLACE.
  - **Egress:** +8 bytes per ack record.
  - **Heap:** about +22 MB at full tables.
  - **Snapshot:** about +4.9 MB at 600 full session rings, about +4 ms of halt at the measured 0.84 ms/MB.
- **Still open: FIX `OrderID` has no epoch.** It is `ord-<ref>`, so it repeats after a fresh epoch. With refs starting
  at 2^32 + 1 every epoch, the repeat is exact. It should become epoch-qualified like the read model (a follow-up, not
  in this change).

## Evidence

**`OrderRefWideningTest`:**
- Three NEWs from `nextOrderRef = 2^31 - 1` are all accepted (2^31 - 1, 2^31, 2^31 + 1), and a cancel of the last one
  applies. Negative control on 074b6fd5c: ref 2,147,483,647, then two refusals with reason 36 (ORDER_REF_EXHAUSTED)
  and ref 0.
- A fresh epoch issues 2^32 + 1 first, and its cancel takes the tail (72 bytes), decodes whole and applies.
- The tail has one encoding.
- A snapshot keeps every wide ref and its idempotency, stream-ring and session-ring answers, and the first ref.
- A format 16 ring record still restores, and is refused when read at format 17's width.
- Two members, and a third restored mid-log, write identical snapshots after the same log.
- REST refs are read whole or refused. The trap is shown in the test: `asInt()` of 4294967301 is 5.
- The ack's word refuses every record of a 32-byte member's 160-byte coalesced message.

**Other tests:**
- `GatewaySubmissionEncodingTest#wideCancelCarriesItsTailAndCompletesFromTheWideAck`: the gateway encodes the tail,
  ignores an ack without the format word, and completes from the wide ack.
- `BinaryGatewayAcceptorTest#wideRefsDecodeAndAckWhole` and `#anOldClientsFramesAreRefusedInItsOwnAckLayout`.
- `OrderRefExhaustionTest`: ADR-100's behaviour at the new ceiling.
- The allocation gates, at exactly zero.
