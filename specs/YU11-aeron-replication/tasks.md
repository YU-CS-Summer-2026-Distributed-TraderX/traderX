# Tasks: YU11-aeron-replication

## Spec and generation

- [ ] T-AR01 Create the full YU11 spec pack and generated architecture document.
- [ ] T-AR02 Add catalog, state-generation, render, runtime-harness, and state-wrapper entries.
- [ ] T-AR03 Audit every YU11 full-file override against YU02/YU03/YU04/YU05/YU09/YU10 copies
  and record ancestor markers.
- [ ] T-AR04 Generate YU11 from a clean target and verify every ancestor marker survives.

## SBE and transport

- [ ] T-AR05 Lock compatible Aeron/Agrona/SBE versions and add schema code generation.
- [ ] T-AR06 Define input, ACK, handshake, heartbeat, snapshot, and replay SBE templates.
- [ ] T-AR07 Add golden N/N-1 encode/decode vectors and schema-checksum startup validation.
- [ ] T-AR08 Implement the transport selector with NATS default and Aeron authoritative mode.
- [ ] T-AR09 Implement exact-zero Aeron primary encode/claim publication.
- [ ] T-AR10 Implement exact-zero follower poll/decode/ring injection with sequence/epoch checks.
- [ ] T-AR11 Implement fixed-capacity local-to-primary sequence mapping and ACK publication.

## Shadow, durability, and policy

- [ ] T-AR12 Implement NATS-authoritative Aeron shadow record/consume/checksum comparison without
  follower BLP injection.
- [ ] T-AR13 Wire the exact follower `Journaler.journaledSeq()` post-force watermark to the ACK
  agent.
- [ ] T-AR14 Prove on-ring ACK precedes the durable watermark and durable ACK follows it.
- [ ] T-AR15 Implement degraded-solo default with alert/reconnect and strict durable halt opt-in.
- [ ] T-AR16 Reject incompatible strict/on-ring and mixed transport/schema configurations.

## Sidecar and recovery

- [ ] T-AR17 Add the Archiving Media Driver sidecar module/image with shared threading mode,
  health, counters, and one-core resource budget.
- [ ] T-AR18 Record live data and snapshot streams; persist recording/checkpoint metadata.
- [ ] T-AR19 Implement retained-volume Archive replay-to-live merge.
- [ ] T-AR20 Implement checksummed snapshot-bundle install plus Archive replay for an empty
  follower volume.
- [ ] T-AR21 Implement retention behind the minimum follower checkpoint and disk/catalog
  fail-closed states.

## Failover

- [ ] T-AR22 Preserve the Lease-gated admission path as the default.
- [ ] T-AR23 Emit/measure direct Aeron heartbeat and peer-staleness detection.
- [ ] T-AR24 Implement atomic `TRADERX_BLP_FAST_WITNESS` compare-and-set claim and revision fence.
- [ ] T-AR25 Reconcile the Kubernetes Lease asynchronously and demote on foreign witness/epoch/
  Lease proof before admission.
- [ ] T-AR26 Prove asymmetric contenders produce one witness winner and measure detection/claim/
  admission phases against the 30–60 ms target.

## Runtime and operations

- [ ] T-AR27 Add compose primary/follower/sidecar/NATS runtime and proof scripts.
- [ ] T-AR28 Add a dedicated multi-node kind profile without modifying the shared cluster.
- [ ] T-AR29 Add GKE sidecar, UDP ports, headless addressing, NetworkPolicy, anti-affinity, Secret,
  transport/policy env, and Archive volume wiring.
- [ ] T-AR30 Add the StorageClass/PVC expansion and StatefulSet orphan-recreate runbook.
- [ ] T-AR31 Add transport/watermark/Archive/witness health, metrics, dashboards, and alerts.

## Verification and benchmark

- [ ] T-AR32 Pass deterministic schema/sequence/epoch/shadow tests.
- [ ] T-AR33 Pass transport exact-zero allocation, inherited allocation/risk, and Epsilon-GC gates.
- [ ] T-AR34 Pass packet-loss, sidecar, follower, primary, DNS, empty-volume, Archive corruption,
  Archive disk-full, and N/N-1 vectors.
- [ ] T-AR35 Store three 30-second compose transport runs with same-day File-NATS controls.
- [ ] T-AR36 Store GKE NATS/Aeron/NATS A/B/A plus single-BLP controls and evaluate both throughput
  gates, failover gate, risk p99, and output-topology regression.
- [ ] T-AR37 Record implementation evidence and commit coherent owner-layer chunks without push.
