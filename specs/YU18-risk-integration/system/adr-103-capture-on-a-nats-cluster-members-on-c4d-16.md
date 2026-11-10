# ADR-103: Production at 450K orders/s: the capture stream on a three-server NATS cluster, members on c4d-standard-16

Status: accepted 2026-11-10 (yaakov: "Yes, all five", on the six decisions of
`issues/risk-integration/tasks/prod-450k-plan-20261110.md`; decision 3, the acceptors, needed none). Rendered, not yet
applied to production: every production change below still takes yaakov's yes on the change itself. Pack:
[production rig](../maintenance/production-rig/spec.md) FR-PRG-05 (amended), FR-PRG-28 (amended), FR-PRG-41;
[downstream read side](../components/downstream-read-side/spec.md) FR-DRS-44. Relates to ADR-096 (the rig at bench
parity, which this supersedes in §1's machine rows), ADR-097 (the snapshot read model), FR-DRS-34 (the three-server
capture bench), FR-DRS-41/42/43 (the batched, compressed, byte-sized wire), RI34 §1b (the campaign), RI35 (acceptors),
RI36 (read model).

## Context

The 2026-11-09/10 campaign (RI34 §1b, `docs/engineering/downstream-at-400k.md` §7.15) held 464K orders/s averaged over
an hour on GKE with the binary order path, the capture stream and the history store exact (1,671,082,768 orders, 0
rejected, no election; store trades = the members' sides; rows = published records = acked). It got there on a rig that
differs from the production render in six ways; this ADR takes the ones that are deployment decisions.

What the campaign measured, in the order the ceilings appeared:

1. **The capture volume.** One NATS server on a pd-balanced 416 GB claim saturated at 298 MB/s (100% busy) at 300K.
   On a Hyperdisk Balanced volume provisioned at 1,200 MB/s the same server saturated at 684–724 MB/s at 400K once
   `max_bytes` retention deletes joined the writes: JetStream API "took too long", slow-consumer cuts, unacked
   publishes, 429 retry storms. s2 compression (FR-DRS-42) cut the steady write to 311 MB/s but not the churn. The hour
   ran on memory streams (8 × 8 GB, about two minutes of feed), which is a rig setting, not a production one: no
   outage window, empty after a restart, one server with no failover.
2. **The members' memory.** On production's c4d-standard-8 with a 16Gi container the leader held 400K at 6.6 of 7
   cores. On c4d-standard-16 at the same 16Gi it accepted 510–527K for 17 minutes and then flapped: elections,
   `failed to join catchup log`, a member dead on `Media Driver timeout`. The archive's page cache and the /dev/shm
   term buffers are charged to the cgroup; at 50–65 MB/s of log a 16Gi cgroup lives in reclaim, and a stall past the
   200 ms heartbeat timeout is an election. At 40Gi (and 1000/500 ms timeouts) the hour ran without one.
3. **The acceptors** serve one order per TCP session at a time (RI35; throughput = sessions ÷ cluster RTT). Code, not
   deployment; it is RI35's own session.
4. **The read model** fetched 1,000 messages per partition sized for one-record messages; at the batched wire that
   was ~12 MB a fetch, cut as a slow consumer 351 times in five minutes. FR-DRS-43 (the fetch sized by bytes) is in;
   the structural fix is RI36.

FR-DRS-34 had already measured the capture on three servers: 12 R1 streams placed four per server by tag on
3 × c4d-standard-16 stored 1.00M msgs/s for 180 s with 0 lost, held 1.25M, and held it with 24 reader lanes draining
(busiest server 4.5 of 16 cores). One server stored 0.4–0.5M msgs/s whatever the stream count.

## Decision

### 1. The capture stream lives on a three-server NATS cluster (FR-PRG-41, FR-DRS-44)

| | Before (ADR-096/097) | Now |
|---|---|---|
| Servers | one `nats` Deployment | a `nats` StatefulSet of three, routes over `nats-headless`, `cluster { name: traderx }` |
| Node | `nats-pool`, 1 × c3-standard-8 (c3 so it could attach a pd-balanced claim) | `nats-pool`, 3 × c4d-standard-16, one server per node (required hostname anti-affinity) |
| Store | one pd-balanced `nats-data` claim | one Hyperdisk Balanced claim per server (`nats-store`: 600 MiB/s, 6,000 IOPS), retained on scale and delete |
| Streams | `TRADERX_FEED_P<p>`, R1, placed by the server | the same streams, R1, s2, each placed by tag on server `p mod 3` (`PROJECTION_FEED_STREAM_PLACEMENT=nats-0,nats-1,nats-2`) |
| Server identity | — | `server_name` and `server_tags` are the pod's name, substituted at start |
| Pod | 4 CPU / 24Gi, `GOMEMLIMIT=20GiB` | 12 CPU / 48Gi, `GOMEMLIMIT=20GiB`; the rest of the limit is the file store's page cache |
| Per-connection pending | 64 MB (default) | `max_pending: 256MB` |
| Node sysctls | kubelet read-only port off | plus the engine pool's `vm.dirty_*` (steady writeback) |

**Why three servers and not one bigger disk.** The per-VM ceiling that stopped a 1,200 MB/s volume at ~700 MB/s is
not understood, retention churn still lands on one device, and one server is one failure. Three servers write a third
of the bytes each (about 100–150 MB/s compressed at 450K, inside one volume with the churn), the readers' fetches are
served from three NICs (§7.13 item 3), and the JetStream meta group keeps its majority with one server down, so the
API stays up through a loss.

**Why R1, not R3.** An R3 stream would ingest three times the bytes for a window the Aeron log already covers. A
server loss loses its partitions' unreplayed tail exactly as the one server did, and the bridges' catch-up republishes
it when the stream is back (FR-DRS-38). The streams keep their two-hour age window and a byte window sized for the
longest replayed outage at 450K (`prepare.py`: 30 minutes, FR-PRG-20's model with `READ_MODEL_RATE` raised to the
measured rate).

**Placement is part of the sizing, not decoration.** Each server's `max_file_store` holds ceil(P/N) of the P partitions
plus 25%: with P=8 over N=3, three partitions. Without the tags the meta leader places R1 streams where it likes, and a
server holding more than three would refuse writes at its cap. So `validate()` refuses a partitioned render whose
placement does not name every server, and `JetStreamFeed` moves a stream found on another server to its tag (a
placement update is a stream move; R1 keeps its messages through it).

**Memory streams stay a rig setting** (`PROJECTION_FEED_STREAM_STORAGE=memory`, FR-DRS-42). Production is file-backed.

### 2. Members on c4d-standard-16, 15 CPU / 40Gi (FR-PRG-05 amended)

The container is sized so the archive's page cache and the term buffers never put the cgroup in reclaim at the log rate
of 450K: 12 GB heap + ~1 GiB shm + metaspace and threads + tens of GB of page cache under a 40Gi limit on a 62 GiB node.
The CPU count follows the node (15 of 16 for the dedicated driver's agents and the apply thread; the bench shape was 7 of
8). Heap, ZGC flags, `MAX_ACTIVE_BOOKS=1024` and the sysctls are unchanged from ADR-096.

**The timeouts are not changed by this ADR.** The hour ran at `CLUSTER_HEARTBEAT_TIMEOUT_MS=1000` /
`CLUSTER_ELECTION_TIMEOUT_MS=500`, but NFR-AC03 bought sub-second failover detection with 200/100 ms, and the flapping may
have been the memory alone. The 520K step is re-run at 40Gi with 200/100 ms; they stay if it holds, otherwise the
smallest setting that holds (500/250 before 1000/500), with the failover time measured at the chosen setting (the e1108
failover proof at rate). NFR-GMR-01 ("stays 200") stands until that run says otherwise; a change lands as its own
amendment with the evidence.

### 3. The read model: FR-DRS-43 ships; the read model is sharded by partition with a router by `account mod 8`

Decided here, implemented in RI36 (its own session): N trade-processor replicas each owning partitions
(`PROJECTION_FEED_PARTITIONS_OWNED`), a partition owned twice refused at start, the in-memory order view's HTTP reads
routed by `account mod 8` to the owning replica so the API contract is unchanged for every client (over the console
merging N answers, which also works at N=4 but moves the join into every reader). The SQL projection needs no routing.

### 4. FIX at rate, after RI35, with per-account fill subjects first

The FIX acceptor has the binary acceptor's shape (one order in flight per session), so it is pipelined in RI35. Before any
FIX run at rate the gateways' fill feed subscribes only to the partitions its sessions' accounts fall in (the options
paper's E1); without it every gateway reads the whole order feed, an 18–24× fan-out on the readers' bytes. The FIX
target is the same 450K (yaakov). FIX exactly-once stays off (lane rule).

### 5. Acceptance on the production render, standing capacity

The acceptance run is the production render (`prepare.py`, the whole service set: EOD extract, risk engine,
price-publisher, reference-data, trade-service, accounts, people, the web front ends, observability), FIX on, one hour at
450K, G3 on every replica, G3h, and the EOD extract run at the end against the resulting positions, with the load spread
over enough accounts that no account meets a real limit (production's risk limits stay). This shape is the standing
one, not a campaign shape the rig scales to: about $8–10/h on demand once RI35 sets the gateway count by CPU
(3 × c4d-standard-16 members ≈ $2.2/h, 3 × c4d-standard-16 NATS ≈ $2.2/h plus three Hyperdisks ≈ $0.35/h, readers,
gateways, history), against ~$3–4/h before.

## Rollout, change by change (each with yaakov's yes)

1. **Pools.** `nats-pool` is recreated at 0 nodes as 3 × c4d-standard-16 on Hyperdisk (`rig.sh pools` refuses the old
   machine type and names this ADR); `engine-pool` is recreated as c4d-standard-16 at 0 nodes (the sysctls are
   reapplied by `rig.sh pools` while it is empty). Both are operator actions on an idle rig.
2. **The render, at a fresh epoch.** The capture streams of an epoch start at seq 1 (FR-PRG-29) and the read model's
   cursors at 0, so the new cluster's empty streams are what a fresh epoch expects: no cursor rebase, no `STREAM_RESET`.
   The old `deploy/nats` must be gone before the StatefulSet's pods share its Service selector, and `pvc/nats-data`
   goes with the epoch's archive when FR-PRG-39 prunes it; neither is deleted by the render.
3. **The 520K re-test** at 40Gi with 200/100 ms (then 500/250, then 1000/500 only if needed), and a failover at rate at
   the chosen timeouts. A rig run, costed and approved before it starts.
4. RI35 (binary, then FIX), E1, the FIX load at rate.
5. The acceptance hour of §5, then production.

## Consequences

- The kind rig keeps one NATS server (its `nats.yaml` is YU17's); `PROJECTION_FEED_STREAM_PLACEMENT` is unset there
  and the server places the streams, so kind proofs are unchanged. The p4k throughput rig renders its own NATS.
- `rig.sh` records and checks three NATS pods (FR-PRG-28's "never restarted" now holds per server, and a replaced
  server is found by uid); `feed_streams` asks every server, because a server's `/jsz` reports only the streams it
  holds.
- The rows (default) render keeps the base `max_file_store: 24G` per server and a 32Gi claim each; the snapshot render
  sizes both from the rate.
- FR-GMR-03's "16Gi memory request equal to the limit" is the kind/ADR-096 figure; on the CompSci production profile
  FR-PRG-05 (40Gi) replaces it, as it already replaced the G1 flags.
- Three Hyperdisks and two more c4d-standard-16 nodes are the standing cost of the capture's window and failover;
  the single pd-balanced claim and the c3 node go.
- Not decided here: the gateway count after RI35 (measured per core on kind first), the compact wire (the options paper's
  F; RI36 keeps it in view), FR-DRS-33's ack-gap resend, a leaner G3h query.

## Evidence

- RI34 §1b and `downstream-at-400k.md` §7.15: the hour (11:14 pm–12:14 am ET, 2026-11-10), the volume saturations,
  the member memory finding, the read-model cuts; shared notes `bridge-batching-gke-20261109/`.
- FR-DRS-34, `downstream-at-400k.md` §7.13: three servers, 1.00–1.25M msgs/s, 0 lost, reader lanes draining.
- Render: `test_prepare.NatsCluster` (the shape and its sizing; six broken renders refused), `test_prepare.NatsMemory`
  (the memory arithmetic at the new budget), `test_rig.Nats.test_three_servers_are_three_pods_each_recorded`,
  `JetStreamFeedTest#aPartitionIsPlacedOnItsServersTag`. The live proofs (the cluster bench on the production render with
  batching, the 520K re-test, the failover at rate, the acceptance hour) are pending and listed in the rollout.
