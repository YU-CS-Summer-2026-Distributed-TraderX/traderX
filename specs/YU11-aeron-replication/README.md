# Feature Pack: YU11-aeron-replication

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

- No PowerShell parity: the scripts this pack names are proof and benchmark runners (`scripts/bench/run-yu11-*.sh`)
  that drive an Aeron transport on a Kubernetes rig through `kubectl`. They are
  Linux/macOS tooling for a rig that is itself Linux, and there is no `.ps1` equivalent
  anywhere in this repo for `scripts/proofs` or `scripts/bench` — the repo's PowerShell
  scripts are lifecycle scripts for the numbered states. The windows badge above already
  says this in an image; this bullet is the machine-readable form of the same claim.


Status: In implementation
Track: `architecture`
Lineage role: `optional`
Previous state: `YU10-fix-ingress`

This pack adds a dual-capable Aeron + SBE replication leg to the `YU10-fix-ingress`
order-matcher while retaining File-backed NATS JetStream as the default and rollback transport.
The primary sends each fixed 64-byte input record once through a manual unicast MDC publication
whose destinations are the local Archive and peer follower. Both destinations therefore share one
session and position space without using IP multicast. The follower's journal remains the durable
replication watermark and each pod's journal remains the business recovery authority.

Primary intent:

- encode the existing `InputEvent` shape with generated SBE flyweights directly into Aeron
  claimed buffers, preserving the input/output Disruptor topology and exact-zero application
  hot-path gates,
- record live replication in Aeron Archive and use snapshot-plus-Archive replay for follower
  catch-up without replacing journal recovery,
- expose the exact `Journaler.journaledSeq()` post-force/pre-apply watermark for durable ACKs,
  while defaulting follower-loss handling to degraded-solo + alert and retaining strict
  synchronous halt as an opt-in policy,
- keep the Kubernetes Lease-gated promotion path as the default and provide an opt-in fast path
  that combines direct Aeron heartbeat detection with an atomic NATS KV witness claim,
- validate NATS and Aeron side by side before a coordinated pair cutover controlled by one
  transport environment value.

Core artifacts:

- `generation/runtime-overrides/order-matcher/` — SBE schema/code generation, transport seam,
  Aeron replicator/follower/ACK agents, exact journal watermark wiring, failover policy
- `generation/runtime-overrides/aeron-replication-sidecar/` — Java Archiving Media Driver image,
  Archive health and counter surface
- `generation/runtime-overrides/kubernetes-runtime/` — sidecar, UDP ports, NetworkPolicy,
  Archive PVC wiring, transport/policy flags
- `scripts/bench/run-yu11-aeron-transport.sh` — transport A/B harness and allocation proof
- `scripts/bench/run-yu11-gke-comparison.sh` — same-client booked-order comparison
- `system/adr-038` … `adr-043` — transport cutover, SBE/epoch contract, Archive recovery,
  durability policy, sidecar budget, and fast-witness failover

Target runtime behavior:

- `BLP_REPLICATION_TRANSPORT=nats` preserves the inherited File-backed JetStream path;
  `aeron` selects the Aeron/SBE leg on both replicas, and a mismatched pair refuses readiness,
- NATS-authoritative shadow mode records and compares Aeron sequence/payload checksums without
  gating the BLP,
- durable ACK mode advances only through the follower journal's forced contiguous watermark;
  degraded-solo remains available through follower reschedules while strict mode closes
  admission on any replication gap,
- the default promotion path remains Lease-gated; fast-witness mode requires both direct peer
  staleness and a successful atomic witness claim before admission opens.
