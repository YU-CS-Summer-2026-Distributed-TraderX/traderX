# Exactly-once dedupe plan

The window problem is a sizing problem only while the key is opaque. A client that numbers its own requests
lets the engine keep one counter per stream instead of one key per order. Contiguity, the rule FIX already
applies to `MsgSeqNum`, is what makes that counter sound: "below `next`" can mean only "already answered".
See ADR-090 for the bound, the options and the sizing.

## Implementation (after ADR-090 is approved), in order

1. **Engine state** (`risk/StreamDedupe.java`, new, owned by `BlpRiskState`). It holds presized arrays for
   `account`, `streamId`, `next` and `lastUsedMs`, and `long[MAX_STREAMS × W]` for the rings, each entry packing
   ref and decision. Stream lookup uses open addressing on `scopeKey(streamId, account)`. LRU eviction scans for
   the minimum `lastUsedMs` only when the table is full (O(streams), and rare).
   `ponytail:` the scan is fine at 8,192 streams; use an intrusive LRU list if `MAX_STREAMS` grows past ~10⁵.
2. **Apply path** (`MatchingEngine`). Before `answeredAsDuplicate`, branch on the sequenced flag:
   - duplicate: answer from the ring;
   - gap: answer `OUT_OF_SEQUENCE`;
   - in order: the existing path, then record the answer.

   Append `OUT_OF_SEQUENCE` and `STREAM_LIMIT` after the last `RiskReason` ordinal.
3. **Ingress codec** (`AeronReplicationCodec`, SBE schema). Add the `streamId u64, seq u64` tail and accept both
   block lengths. `InputEvent` gains `streamId` and `streamSeq`.
4. **Snapshot** (`MatchingEngineClusteredService`). Format 14 with `T_STREAM`, written with ring bulk copies. A
   load of formats 9–13 leaves the stream table empty. W and `MAX_STREAMS` are recorded and checked (FR-XOD-06).
5. **Binary gateway.** Decode the flags byte and pass `(streamId, seq)` through, applied to the YU18 override of
   `BinaryGatewayAcceptor`.
6. **Loadgen and hxb harness** (independent-baseline, not TraderX). Add `plan.sequenced` and the
   `OUT_OF_SEQUENCE` go-back-N. Add HxNativeReplay `duplicateKeys` and `resendsOfCommitted`. Add arms `xs` and
   `xn` in `render.py`.
7. **FIX** (FR-XOD-12, separately enabled). Add the `FixSessionStore` inbound record and resubmit-on-adopt, then
   run the NFR-XOD-03 A/B before it is turned on.

Steps 1–5 land as one engine change and one image. Step 6 is hxb-only. Step 7 is gateway-only, so it can land
later without touching the core.

## Roll (ADR-090 §3)

Take a snapshot barrier on all three members, then do a whole-cluster stop and start onto the new member image,
which loads the format-13 snapshot. Then roll the gateways one at a time. Rollback is a fresh epoch from the
barrier snapshot. Production goes ahead only on yaakov's approval of a rollout, after the cloud row passes.
