# Exactly-once dedupe plan

The window problem is a sizing problem only while the key is opaque. A client that numbers its own requests
lets the engine keep one counter per stream instead of one key per order. Contiguity, the rule FIX already
applies to `MsgSeqNum`, is what makes that counter sound: "below `next`" can mean only "already answered".
See ADR-090 for the bound, the options and the sizing.

## Implementation, in order (done 2026-10-28; as-built notes in ADR-090 §3–§4)

1. **Engine state** (`risk/StreamDedupe.java`, new, attached to `BlpRiskState`). It holds presized arrays for
   `account`, `streamId`, `next` and `lastUsed`, plus `int[MAX_STREAMS × W]` refs and `byte[MAX_STREAMS × W]`
   decisions for the rings. Stream lookup uses open addressing with backward-shift delete. LRU eviction scans for
   the minimum `lastUsed` only when a new stream meets a full table (O(streams), and rare).
   `ponytail:` the scan is fine at 8,192 streams; use an intrusive LRU list if `MAX_STREAMS` grows past ~10⁵.
2. **The stream gate** (`MatchingEngineClusteredService.admitSequenced`). It runs before the ref generator, so a
   gate answer changes nothing:
   - a gap answers `OUT_OF_SEQUENCE`;
   - a full table answers `STREAM_LIMIT`;
   - a duplicate with a recorded rejection answers that rejection;
   - a duplicate with no answer it can give answers `DUPLICATE`;
   - an admitted NEW, or a duplicate of an accepted one, continues into the engine, with
     `BlpRiskState.beginSequenced` making the ring the answer store for that one key. The engine code is
     unchanged: `answeredAsDuplicate` and `decideAndReserve` find the ring through the key lookups they already
     make.

   `OUT_OF_SEQUENCE` and `STREAM_LIMIT` are appended after the last `RiskReason` ordinal.
3. **Ingress codec** (`AeronReplicationCodec`). The `u64 streamId, u64 seq` tail follows templates 1 and 9 behind
   `INPUT_FLAG_SEQUENCED`; the schema is unchanged. `InputEvent` gains `streamId` and `streamSeq`.
4. **Snapshot** (`MatchingEngineClusteredService`). Format 14 writes one `T_STREAM` per stream in index order, each
   carrying W; restore puts each back at its index. A load of formats 9–13 leaves the stream table empty.
5. **Binary gateway.** The YU18 override of `BinaryGatewayAcceptor` decodes the NEW flags byte and calls
   `submitSequencedOrder`; `ClusterGatewayMain` appends the tail.
6. **Loadgen and hxb harness** (independent-baseline, not TraderX). Add `plan.sequenced` (one stream per session)
   with the `OUT_OF_SEQUENCE` go-back-N. Add HxNativeReplay `duplicateKeys` and `resendsOfCommitted`. Add arms
   `xs` and `xn` in `render.py`.
7. **FIX** (FR-XOD-12, enabled separately). `FixSessionStore` keeps the inbound order records, and
   `FixGatewayAcceptor` sequences a NewOrderSingle through them, catching up on `OUT_OF_SEQUENCE`. Run the
   NFR-XOD-03 A/B before it is turned on. The heap keeps the unanswered requests and a ClOrdID window, and
   resends past the window are looked up in the `K.c.*` index (FR-XOD-13).

Steps 1–5 land as one engine change and one image. Step 6 is hxb-only. Step 7 is gateway-only, so it can land
later without touching the core.

## Roll (ADR-090 §3)

Take a snapshot barrier on all three members, then do a whole-cluster stop and start onto the new member image,
which loads the format-13 snapshot. Then roll the gateways one at a time. Rollback is a fresh epoch from the
barrier snapshot. Production goes ahead only on yaakov's approval of a rollout, after the cloud row passes.
