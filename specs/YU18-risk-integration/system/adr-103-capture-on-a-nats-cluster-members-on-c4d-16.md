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
failover proof at rate). yaakov's condition on a slower setting (via the coordinator, 2026-11-10): "As long as it
doesn't negatively affect throughput and latency", so the re-test records throughput and the `/latency` segments at
each setting it tries, side by side, and a setting that costs either is not taken. NFR-GMR-01 ("stays 200") stands until that run says otherwise; a change lands as its own
amendment with the evidence.

### 3. The read model: FR-DRS-43 ships; the read model is sharded by partition with a router by `account mod 8`

Decided here, implemented in RI36 (its own session): N trade-processor replicas each owning partitions
(`PROJECTION_FEED_PARTITIONS_OWNED`), a partition owned twice refused at start, the in-memory order view's HTTP reads
routed by `account mod 8` to the owning replica so the API contract is unchanged for every client (over the console
merging N answers, which also works at N=4 but moves the join into every reader). The SQL projection needs no routing.
yaakov's condition (via the coordinator, 2026-11-10): "if this help then yes", so RI36's proof shows the router earning
its place: the same API contract for every client, and its latency and throughput against the fan-out-and-merge
alternative; if it does not help, the console merges. **Measured by RI36 on kind 2026-11-10 (commit `2c05b4fd` on
`tp-partition-ownership`, FR-DRS-45 ownership, FR-DRS-46 router):** four replicas, every partition read by exactly one,
G3 on the union; the router (inside trade-processor: an unowned account's `/accounts/{id}/orders` is forwarded to the
live owner, never twice, 503 with no owner) against fan-out-and-merge against the owner directly, open-orders reads at
16 concurrent: 1,450 / 635 / 1,410 reads/s, p50 8.8 / 21.7 / 10.1 ms; 16 MB `status=all` reads even. The router is
kept. The snapshot render now carries 4 trade-processor replicas at 12 CPU / 24Gi each, so `readmodel-pool` must hold
4 × 12 CPU (four c4d-standard-16, or two c4d-standard-32); that pool change lands with the acceptance run.

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
one, not a campaign shape the rig scales to (yaakov, via the coordinator, 2026-11-10: "yes, standing capacity"; members
on c4d-standard-16 at 40Gi and the three NATS servers "all the time"; gateways keep autoscaling from a small floor via
ADR-096's HPA): about $8–10/h on demand once RI35 sets the gateway count by CPU
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
   the chosen timeouts. A rig run, costed and approved before it starts. **Done 2026-11-10, 9:24-10:31 am ET** (yaakov:
   "Yes, run it"; ~$20): 200/100 ms holds (runs B and C: 20 minutes at ~495-510K/s, zero election transitions in any
   member's log); the relaxed timeouts are not needed and NFR-GMR-01 stands. The failover at rate (run D) detected and
   elected in ~1.1 s and the cluster stayed exact, but the promoted leader's bridge lanes dropped 22,948,341 sides
   from the stream in the first ~45 s (RI37). **Closed 2026-11-10, run E3 (RI37's session, yaakov: "Go ahead with
   gke")**: the same render with the FR-DRS-47 member (`cluster-node-member:ri37-15d49bd7`), leader killed at
   1:48:02 pm ET at ~465K sides/s — one election, member 1 LEADER_READY 1.81 s after the kill, `queueDropped` 0 and
   gaps 0 on every member, G3h 1..183,194,994 each once = the members' total, 0 rejected. The failover at rate is
   accepted on this render.
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
- **The re-test, 2026-11-10 9:24-10:31 am ET** (shared notes `retest-520k-20261110/`), on the p4k rig rendered from this
  ADR's production candidate (`prepare.py --read-model snapshot --partitions 8` → `p4k.py`; three NATS servers on
  3 × c4d-standard-16 with 563Gi Hyperdisk claims at 600 MiB/s, streams placed 3/3/2 by tag and read back from each
  server's `/jsz`; members 15 CPU / 40Gi at 200/100 ms; 24 gateways; trade-processor `bat-place1` with FR-DRS-44):
  - Run A, 400K for 10 min: 235,661,968 orders, 0 rejected, stream +19,496,103 = bridges' messages, store
    +706,985,904 rows = records = acked, **G3 ok and G3h ok**, caught up 28 s after the load; NATS 1.2 cores and 41%
    disk busy per server.
  - Run B, 520K setting for 12 min: 354,517,700 orders (~492K/s with the driver's pauses, every session at its
    in-flight cap: the RI35 serialisation point), 0 rejected, stream = messages, 1,063,553,100 records acked, G3h ok;
    roles L/F/F every minute and **zero election transitions in any member's log**. Run C, 8 min more: +240,422,954,
    G3h ok 1..830,602,622 each once, no election.
  - Run D, the failover at ~510K/s: new leader in ~1.1 s (member 2 LEADER_READY 0.17 s after the election opened; a
    second election at +4 s made member 1 leader), the driver kept its rate with 0 rejected and 1,024 ambiguous (in
    flight with the dead leader), the killed member rejoined in 96 s, all members at 1,003,275,732. Gateway
    `/latency` before vs 45 s after: total p50 0.91-0.97 → 0.92-0.95 ms, p99 1.29-1.64 → 1.68-1.69, p999 1.72-1.81 →
    1.78-2.81. **The stream lost 22,948,341 sides** to the promoted leaders' `queueDropped` (RI37).
  - Run E3, 2026-11-10 ~1:48 pm ET (RI37's session; shared notes `ri37-gke-run-e3/`, commit 5ee1376b): run D's render,
    only the member image changed to FR-DRS-47 (class md5 checked on all three). Leader member 0 killed at ~465K
    sides/s: one election, member 1 LEADER_READY 1.81 s after the kill; G3h holds 1..183,194,994 each once and the
    members agree at 183,194,994; `queueDropped` 0 and gaps 0 on every member, trades and orders (run D: 22,948,341
    sides and 46,297,224 order events); the promoted lanes caught up in 201-221 ms holding at most 8,681 sides /
    17,443 events per lane; driver 163,842,900 accepted, 0 rejected, 595 ambiguous; one gateway's total p50/p99/p999
    1.11/1.73/1.89 → 1.10/1.68/2.37 ms. Caveat: run D's 15-45 s stall of the promoted lanes' stream reads, and its
    second election, did not recur on the fresh rig, so E3 alone does not discriminate against the parent build;
    the kind proof and `ReceiptLanesIT` do, and what stalled run D stays open in RI37.
  - The read model (one trade-processor at 24Gi) fell behind at ~500K and was OOM-killed once after run A (RI36,
    already in hand); it did not affect the members or the history store.
- Render: `test_prepare.NatsCluster` (the shape and its sizing; six broken renders refused), `test_prepare.NatsMemory`
  (the memory arithmetic at the new budget), `test_rig.Nats.test_three_servers_are_three_pods_each_recorded`,
  `JetStreamFeedTest#aPartitionIsPlacedOnItsServersTag`. The cluster on the render with batching, the 520K re-test and
  the failover at rate ran 2026-11-10 (above), the failover at rate again with FR-DRS-47 (run E3); the acceptance hour is
  pending, after RI35 (RI36 and RI37 are merged).
