# Exactly-once dedupe tasks

- [x] ADR-090: the bound from measured election, reap and reconnect times; options (a), (b) and (c) sized; (c)
      recommended.
- [x] yaakov approves ADR-090 (2026-10-28, option c).
- [x] `StreamDedupe` state, the stream gate in `MatchingEngineClusteredService`, the ring as the sequenced key's
      answer store in `BlpRiskState`, `OUT_OF_SEQUENCE`/`STREAM_LIMIT` reasons (FR-XOD-01..08).
- [x] Ingress tail behind `INPUT_FLAG_SEQUENCED` (FR-XOD-10); the binary NEW flags byte (FR-XOD-11).
- [x] Snapshot format 14 with `T_STREAM`; formats 9–13 restore with no streams (FR-XOD-09).
- [x] `ExactlyOnceStreamTest` (12), including the negative control on today's key table; `BinarySequencedFrameTest`.
- [x] FIX: inbound order records, resubmit by ClOrdID, catch-up on `OUT_OF_SEQUENCE`, `FixExactlyOnceIT` (3, with its
      negative control) (FR-XOD-12).
- [x] Loadgen `plan.sequenced` and HxNativeReplay `duplicateKeys`/`resendsOfCommitted`; arms `xs`/`xn`
      (`exactly-once-sequenced.patch`, the hxb harness, not this repo).
- [ ] Local triad: `xs` passes and `xn` fails.
- [ ] CLOUD-QUEUE row 17 "exactly-once at 400K/s", with NFR-XOD-01/02.
- [ ] NFR-XOD-03 A/B before `FIX_EXACTLY_ONCE` is enabled on any rig.
- [ ] Ship in a `traderx` rollout (yaakov approves): snapshot barrier, whole-cluster stop, then gateways.
