# ADR-094: Take the snapshot pause off the order path

Status: PROPOSED 2026-10-29. No engine code until yaakov approves. Builds on:
- ADR-090 (the stream table);
- FR-IMRG14 (the idempotency table);
- ADR-079 (archive purge behind snapshots, which is why snapshots must keep coming).

## Context

At 400K/s the hour arms take a snapshot every 60 s, and their p99.9 is 23–24 ms. With no snapshot in the window, p99.9 is 4.5–4.9 ms (THROUGHPUT-NIGHT `s30`/`s60` rows). The stall is a fixed cost per snapshot, not a load effect: the per-snapshot max is 36–57 ms at both 100K and 400K.

**Why a fixed pause reaches p99.9.** Let A be the number of orders that one snapshot delays past a threshold, and N the number of snapshots in the measured period. p99.9 sits inside the snapshot tail when

    N × A  >  0.001 × rate × period

- At 60 s and 400K/s, one minute carries 24M orders, so the budget is 24K delayed orders per snapshot.
- The tip's unpacked snapshot delays about 31K orders past 10 ms per snapshot: 187K in the 3-minute `ap0` window, which held 6 snapshots (CLOUD-QUEUE row 3).
- 31K > 24K, so p99.9 falls inside the snapshot tail, which matches the observed 23–24 ms.

The fix therefore has two levers:
- **A**: shorten the pause, so fewer orders are caught;
- **N**: take snapshots less often.

Either one brings p99.9 back to the ~5 ms body. p99.99 and the per-snapshot max fall only with A.

## (1) Decomposition of today's pause (tip e6171d79, the default unpacked writer)

**Why the cluster stops.** Aeron Cluster's snapshot is a log-position barrier:
- The leader's consensus module enters SNAPSHOT state.
- In that state it polls no ingress and appends nothing.
- It stays there until its service has written the snapshot AND the archive has recorded it (`ClusteredServiceAgent`: start recording → `onTakeSnapshot` → await recording complete → ack).
- So the leader's whole snapshot is a cluster-wide ingress halt. The followers' snapshots do not hold commit (STALLS.md §1).

The halt is followed by a backlog drain at the cluster's spare capacity, roughly 1.7 × the halt at 400K. For example, `ap0` had a ~31 ms commit-flat halt for a ~36 ms client max, with 31K orders past 10 ms.

| Part | What it costs | Evidence |
|---|---|---|
| a. Snapshot setup: publication plus recording start | < 2 ms | STALLS.md §1: first byte within a few ms of the action |
| b. Service serialization, unpacked: one offer and one `long[]` allocation per record, one hash probe per idempotency entry | Writer alone 11–20 ms; through a real IPC publication 22–30 ms | This ADR's local measurement below. On hxb, the prototype's JFR samples the service thread in the idempotency walk for ~12 ms, plus 2–4 ms of offers |
| — of which T_IDEMPOTENCY (262,144 entries × 28 B = 7.34 MB) | ~98% of the bytes when no streams are present | measured |
| — T_STREAM (ADR-090: 2,108 B per full 256-answer stream) | 2,048 streams = 4.3 MB; 8,192 streams = 17.3 MB. Unpacked over IPC, 8,192 streams take 35 ms in total | measured |
| — T_ORDER (open plus retained terminal rows) and everything else | 0.13 MB and < 0.3 ms on the crossing flow | measured. The terminal FIFO is sized 262,144 (`DEFAULT_TERMINAL_RETAIN`); a resting-heavy book could make it the largest structure, so the hxb arm records its record counts |
| c. Archive records the snapshot | ~12 ms for 16.9 MB on hxb (~1.4 GB/s, of which only 3–4 ms is the recorder's write time), so ~5 ms for today's 7.5 MB | STALLS.md §1 |
| d. Consensus effects | No commit or ingress for (a + b + c); then the backlog drain. The consensus module's own snapshot is ~3 KB and is not on the critical path | STALLS.md §1, CnC counters |

**Local measurement** (`SnapshotPauseCostTest`, a scratch test kept in the coordinator notes folder until this ADR is approved):
- State: 800K orders through the real apply path, so the 262,144-key idempotency table is full, plus 0, 2,048 or 8,192 full ADR-090 streams.
- Medians of 7 runs on a shared Mac:

| State | Writer, unpacked | Writer, packed + ring | onTakeSnapshot over a real IPC snapshot publication, unpacked | Same, packed + ring |
|---|---|---|---|---|
| No streams, 7.5 MB | 16.7–20.5 ms | 12.1–17.6 ms | **22.2–29.8 ms** | **4.3–13.0 ms** |
| 2,048 streams, 11.8 MB | 11.2–16.6 ms | refused (defect below) | 18.8–19.3 ms | refused |
| 8,192 streams, 24.7 MB | 14.0–20.4 ms | refused | 30.5–34.7 ms | refused |

**On hxb** (prototype image f, the same packed and ring writers that later landed in the tip):
- Ring plus packing took the leader halt from a median of 28.5 ms to 14 ms.
- The client-side max per snapshot fell from 47.6 ms to 20.5 ms (STALLS.md §1, the `c0`/`c1` arms).

### Defect found while measuring (ADR-090, landed but not deployed)

- **The threshold.** A T_STREAM record is 4 + 8 × (7 + n) bytes, which exceeds the 1,376-byte snapshot payload once a stream holds more than 163 answers.
- **Unpacked writer.** `offer` fragments the record, and `loadSnapshot` polled the Image with no `FragmentAssembler`. The refusal ("snapshot corrupt: stream 0") was then **swallowed**: `Image.poll` hands a handler's exception to the client's error handler and keeps polling, so the load reached END with no streams. That is a silent partial restore, not a fail-closed one.
- **Packed writer.** The packer refuses the record, so `onTakeSnapshot` throws.

The in-memory snapshot tests never fragment and never go through `Image.poll`, which is why they pass.

**Step 0 is DONE**, as ADR-090 §2a / FR-XOD-18:
- the load runs through an assembler and rethrows the first refusal;
- the packer sends an oversized record on its own;
- no format change;
- it ships in the same all-members roll as ADR-090.

## (2) Options

Expected p99.9 figures are for one hour at 400K/s binary with 60 s snapshots, from the model above: halt H, drain ≈ 1.7 H, so A ≈ rate × (2.7 H − 6 ms) past 8 ms. They are estimates until row 18 measures them.

| # | Option | Effect on the pause | Determinism / recovery risk | Snapshot format | Roll method | Expected p99.9 |
|---|---|---|---|---|---|---|
| 0 | **Fix the oversized-record defect.** `loadSnapshot` reassembles fragments with an `ImageFragmentAssembler`. The packer flushes, then sends any record larger than one message as its own (fragmented) message | None. It unblocks option 1 wherever streams exist | Restore-side only, and fail-closed checks are unchanged. Test: the full-stream real-channel round trip, which fails today | None (14) | Reader first on every member, then writers. A snapshot barrier plus whole-cluster restart is the house rule; nothing in the change needs it | — |
| 1 | **Turn on packed + ring** (`CLUSTER_SNAPSHOT_PACKED=true`, ring is the default under packed). Already landed, byte-identical stream content | Serialization plus offer from 22–30 ms to 4.3–13 ms locally. Halt 28.5 → 14 ms on hxb | Low: proven byte-identical (`SnapshotPackedRoundTripTest` steps 1–7: restore, a tail with a key retry, the real transport). Reader accepts both framings | None (14) | Writer flag, node by node (the reader takes both). Production ADR-087: OnDelete members, one at a time behind the snapshot barrier | ~5–6 ms (A ≈ 13K/snapshot against the 24K budget, ~2× margin) |
| 2 | **Snapshot on log size, with a time floor.** The leader toggles when the log has grown by `CLUSTER_SNAPSHOT_LOG_BYTES` (e.g. 6 GB ≈ 2 min at 400K), or after `CLUSTER_SNAPSHOT_INTERVAL_MS` at low rate | N falls by the ratio. At 2 min, 30 snapshots an hour; at 5 min, 12 | None to determinism: the toggle is the consensus module's own action, taken at whatever log position. Costs: recovery replays up to one interval (2 min at 400K ≈ 48M inputs ≈ 25–40 s of replay), and the archive keeps up to one interval plus a segment (6 GB on the 32Gi volume, today 3 GB) | None | `ClusterNodeMain` only (the leader toggles). Rolls node by node | With 1: ~5 ms (A ≈ 13K × 30 = 0.4M against the 1.44M hourly budget). Alone at 2 min: 31K × 30 = 0.93M < 1.44M, ~5 ms. p99.99 and max unchanged |
| 3 | **Compact the stream records.** A ring's refs are increasing, so delta-varint the refs and run-length the decisions: ~2–3 B per answer instead of 8. Write only rings of streams used within the idle window | T_STREAM 17.3 MB → ~5–6 MB at 8,192 streams, removing ~12 ms of serialization plus recording at full table | Medium: a new codec in the deterministic core. Needs a round-trip property test over random rings, plus restore-into-old-and-new-build tests | Format 15; the reader keeps 14 | Snapshot barrier plus whole-cluster restart (ADR-087), reader before writer | Matters only with many streams. With 1: keeps H ≈ 14 ms at 8,192 streams instead of ~30 ms |
| 4 | **Shrink the idempotency table** once clients are on ADR-090 streams. 256Ki keys cover 0.65 s at 400K; a sequenced client never needs them | `IDEMPOTENCY_CAPACITY` 32Ki: 7.3 MB → 0.9 MB, so serialization ~1–2 ms and recording ~1 ms. H drops to a few ms | Behaviour change: an UNSEQUENCED client's dedupe window falls from 0.65 s to 0.08 s at 400K (FR-IMRG14 is restated). Restore must refuse, or truncate in retention order, a snapshot written with a larger table | Header carries the capacity; restore rule changes | Snapshot barrier plus whole-cluster restart | ~4.5–5 ms; p99.99 also falls |
| 5 | **Serialize off the service thread from a copy-on-write image.** | Within Aeron Cluster's protocol, little: the consensus module keeps ingress stopped until the service acks, and the ack must follow a complete, recorded snapshot. Copying 7.5–25 MB on the service thread costs about what packed serialization does | It only pays with "snapshot by reference": a tiny Aeron snapshot at P plus a state file written later. But Aeron treats P as recoverable at ack time, before the file is durable, and purge deletes the log before P. A crash in that gap loses recovery unless purge moves to the file's position and the service replays P′..P itself on start (as `ClusterRecon` does). That is a second recovery path in the deterministic core. **High** risk | New, external | Fresh design and proof; not a roll of today's snapshot | ~5 ms, but no better than 1+2 at 400K |
| 6 | **Stagger or move work.** | Aeron Cluster snapshots every member at the same log position (a replicated action), and only the leader's pause halts ingress. There is no open-source way to snapshot followers only. Moving leadership away before a snapshot costs an election each time, which is worse | — | — | — | — |
| 7 | **Aeron native.** | Open-source Aeron has no incremental snapshot. Aeron Premium's Cluster Standby takes snapshots on non-voting standby nodes, so the voting leader never pauses: commercial licence, out of scope here. `CLUSTER_ARCHIVE_THREADING=DEDICATED` did not help (CLOUD-QUEUE row 3, `ap1`: snapshot halts 31 → 36 ms, confounded by leader deferral) | — | — | — | — |

## Decision (proposed)

1. **Step 0 now:** fix the oversized-record defect. It is a recovery defect for ADR-090 streams, independent of latency.
2. **Then options 1 + 2:**
   - turn on packed + ring;
   - snapshot on log size (`CLUSTER_SNAPSHOT_LOG_BYTES`, proposed 6 GB) with the 60 s time floor kept for low rates.
   - Neither changes the snapshot format or the deterministic core's behaviour, and both roll node by node.
   - Together the model gives p99.9 ≈ 5 ms with about a 3× margin.
3. **Option 3 only if** an hour with sequenced clients shows T_STREAM in the halt (thousands of full streams).
4. **Option 4 only as a product decision** (yaakov): it restates FR-IMRG14 for unsequenced clients.
5. **Options 5–7 are not proposed.** 5 adds a second recovery path to the deterministic core for no gain at 400K; 6 has no open-source mechanism; 7 needs a commercial licence.

## (3) Test plan

### Local

- **`SnapshotPauseCostTest`** (land with step 0):
  - per-record-type bytes and time, for both framings, on the 400K-hour state shape (full idempotency, 0/2,048/8,192 full streams, retained terminals);
  - `onTakeSnapshot` timed over a real IPC snapshot publication;
  - printed, not asserted, so wall clock never fails CI.
- **`aFullStreamSnapshotRestoresOverTheRealChannel`** (asserted):
  - 3 full streams through `onTakeSnapshot` → the real snapshot channel → `loadSnapshot(Image)` must restore byte-identical;
  - repeated packed;
  - fails on today's tip ("snapshot corrupt: stream 0") and must pass after step 0.
- **The `SnapshotPackedRoundTripTest` population** gains full streams, so step 6 (real transport) covers T_STREAM in both framings.
- **Option 2:**
  - a `ClusterNodeMain` unit test: the toggle fires on log growth past the threshold, and on the time floor at low rate;
  - a three-member in-process cluster test, the ThreeMemberClusterTest shape: snapshots at the expected positions, restart from the latest, state hash equal on all three.

### hxb: CLOUD-QUEUE row 18 (proposed)

Sized for a decisive answer (yaakov 2026-10-29: total cap $3,500, cost no constraint for the tail work).

**Arms.** One hour each, one image (a tip with step 0 and option 2), only the env differs:
- `b15ho` = the control: today's unpacked writer, 60 s snapshots. It is re-run, not banked, so control and treatment share the night and the lanes.
- `b15hk` = `b15ho` plus `CLUSTER_SNAPSHOT_PACKED=true`.
- `b15hl` = `b15hk` plus `CLUSTER_SNAPSHOT_LOG_BYTES=6000000000`.
- `b15hs` = `b15hk` with ADR-090 sequenced clients (the `xs` plan, no kills). Every session is a stream, so the stream rings fill and the snapshot carries T_STREAM. It is the only arm that exercises step 0 on the real channel at load.

**Repeats and order.**
- Three repeats each of `b15ho`, `b15hk` and `b15hl`, and two of `b15hs`: 11 arm-hours.
- Both lanes, interleaved and reversed: lane A runs `ho hk hl hs ho hk`, lane B runs `hl hk ho hs hl`, then A finishes with `hl ho hk` (lane A nine, lane B five, ≈ 13 h wall in all).
- Every treatment then has a control on the same lane within two hours, and no arm always runs first.

**Shape.** Both use 400K/s binary, 60 s snapshots (`b15hl`: log-size trigger with a 60 s floor), member `/data` on the Hyperdisk, the probe running the whole hour, hx-purge and hx-verify.

**The member heap stays 3g, as in `b15ho`,** so the comparison varies the snapshot only. The member GC sizing from f15hx (6g, SoftMax 3g) is a separate row. If an allocation stall appears, the arm is re-run, not judged.

**Verdict rules, written before the run:**
- **The control must reproduce the problem.** Every `b15ho` repeat must show p99.9 ≥ 15 ms, with snapshot-class orders above the 24K-per-snapshot budget. Otherwise the night is INCONCLUSIVE: the snapshot is not what sets p99.9 on that rig, and no treatment verdict is read.
- **A treatment passes only if all its repeats pass.** For `b15hk`, `b15hl` and `b15hs`, every repeat must satisfy:
  1. p99.9 ≤ 8 ms over the hour, intended→ack;
  2. snapshot-class orders past 8 ms per snapshot below 24K. This is the model's own quantity, so a pass can be told apart from a quiet hour;
  3. snapshot plus tail verify `problems: []` with its negative control failing; snapshot hashes agree across members.
- **Arm-specific checks:**
  - `b15hk`: leader halt per snapshot (probe: ingress plus commit flat) with median ≤ 16 ms over all repeats.
  - `b15hl`: the expected number of snapshots for its log growth, and the archive never above one interval plus a segment.
  - `b15hs`: every member restores its last snapshot after the hour (a restart of one follower at the end), proving step 0 at load. Its T_STREAM bytes are recorded, and they decide option 3.
- **Split results:** one repeat failing and others passing is reported as a FAIL, with the failing hour's stall classes (probe) named.
- **Recorded on every arm:** snapshot record counts per type, so the terminal-row size on this workload is measured.
- **Member GC:** the members stay at 3g as in the f15hx/b15ho evidence, so the snapshot is the only variable. An allocation stall voids that repeat, which is then re-run rather than judged.

**Cost.**
- Lane ≈ $9/h, and each arm ≈ 1.25 h including bring-up and collection, so ≈ $11.5 per arm-hour.
- 11 arm-hours ≈ **$125**, plus one re-run allowance per arm type (4 × $11.5 ≈ $46): **≈ $170 in all**, spread over two ET days.
- That is within the $3,500 total cap (~$1,800 spent).
- Check the controller disk before every launch (feedback: f15hf2). The controller VM is TERMINATED as of 2026-10-29 and must be started first.

**Owner and prerequisites:**
- the coordinator assigns the lane;
- images.tip rebuilt from a tip with step 0 and option 2;
- the arms added to `harness/render.py`, which is the shared harness: patch only.
