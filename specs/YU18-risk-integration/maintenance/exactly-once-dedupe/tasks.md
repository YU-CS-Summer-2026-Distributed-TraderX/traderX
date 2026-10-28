# Exactly-once dedupe tasks

- [x] ADR-090: the bound from measured election, reap and reconnect times; options (a), (b) and (c) sized; (c)
      recommended.
- [ ] yaakov approves ADR-090 (gate: nothing below starts before this).
- [ ] `StreamDedupe` state, apply-path branch, `OUT_OF_SEQUENCE`/`STREAM_LIMIT` reasons (FR-XOD-01..08).
- [ ] Ingress codec tail with both lengths decoded (FR-XOD-10); binary flags byte (FR-XOD-11).
- [ ] Snapshot format 14 and format-13 load (FR-XOD-09).
- [ ] `ExactlyOnceStreamTest`, including the negative control on today's key table (spec §Test plan).
- [ ] Loadgen `plan.sequenced` and HxNativeReplay `duplicateKeys`/`resendsOfCommitted`; arms `xs`/`xn`.
- [ ] Local triad: `xs` passes and `xn` fails.
- [ ] CLOUD-QUEUE row "exactly-once at 400K/s" (spec §Cloud proof), with NFR-XOD-01/02.
- [ ] FIX: inbound record, resubmit-on-adopt, `FixExactlyOnceIT`, NFR-XOD-03 A/B (FR-XOD-12).
- [ ] Ship in a `traderx` rollout (yaakov approves): snapshot barrier, whole-cluster stop, then gateways.
