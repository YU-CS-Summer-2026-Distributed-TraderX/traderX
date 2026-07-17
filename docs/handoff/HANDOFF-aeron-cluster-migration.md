# HANDOFF: Aeron Cluster migration — the next state (YU12 candidate)

Audience: the new fable chat taking on YU12 (Aeron Cluster), after codeX's **now-completed and
proven** YU11 cross-epoch recovery work. This is a forward-looking design brief, not a
resume-a-session handoff. It records the **leading direction and the full reasoning behind it**, the
concrete mapping onto this codebase, and — most valuably — the hard correctness requirements codeX's
recovery work distilled, so YU12 starts from a considered proposal rather than a blank page.

## How to use this doc

This is a **recommendation — Aeron Cluster** — with the full reasoning laid out so it can be argued
with, not a neutral menu. The premise it rests on is settled: **yaakov confirmed (2026-07-18) that
this system is a waypoint toward a production matching engine**, not a demo whose HA ambition tops
out at surviving one pod death. That decides the big fork — production-grade fault tolerance is in
scope, so the hand-built 2-node path is a stepping stone, not the destination.

What's still open to brainstorm is the **vehicle, not the whether**: is Aeron Cluster the right way
to get production consensus, or is there a better one (a different consensus implementation,
topology, or an approach neither lane has framed)? Push on that with yaakov before starting the
build; if the discussion produces something that clearly beats Cluster, take it. But this doc is
the **anchor**: if an hour of exploration hasn't beaten the case below, converge on it and move
rather than spin. Relitigate the vehicle deliberately, then commit.

## The leading direction, and why

**Proposed: move BLP high-availability from the hand-built 2-node + witness model to Aeron Cluster
(Raft consensus).** YU11 deferred this ("no 3+ replicas / quorum / Aeron Cluster") and instead
hand-built leader election (k8s Lease), replication (Aeron MDC), fencing (the fast-witness CAS on
NATS KV), and cross-epoch recovery (the snapshot-bundle work codeX has now completed and proven).
That path works and is measured (see below), but it requires us to hand-roll — and adversarially
prove — correctness machinery that a consensus protocol provides primitively.

The counter-case for staying 2-node — cheaper, ~80% built, k8s-idiomatic — only wins if HA ambition
tops out at surviving one pod death. yaakov has confirmed it doesn't (production matching engine is
the trajectory), so this fork is closed in favor of the migration. Recorded here for completeness,
not as a live decision.

The trigger for revisiting: **the single largest piece of machinery still being hand-built
(codeX's cross-epoch cold-follower recovery) is exactly what Aeron Cluster gives for free** via
its log + snapshot + automatic member catch-up. Continuing to deepen the 2-node path is a
sunk-cost trap — at some point the sum of {fast-witness enablement + fencing proof + snapshot
transfer + FIX gateway} exceeds the cost of adopting the consensus library that makes most of it
unnecessary and correct-by-construction.

The load-bearing premise, **confirmed by yaakov 2026-07-18: 2-node HA is a waypoint, not the
destination** — a stepping stone toward a production matching engine. codeX's recovery bundle gives
a working HA demo now; Aeron Cluster is the production answer. This is settled, not open — it is the
basis the recommendation stands on.

### What tipped it (the honest case)

- **Split-brain becomes structurally impossible.** A partition minority cannot win a majority vote,
  so it cannot elect a leader. This is the deep win: correctness by construction, not by a fencing
  proof that must hold under adversarial timing.
- **Failover moves off the k8s control plane, natively — and gets fast.** Raft elects internally;
  k8s only schedules the pods. This satisfies the original "failover off the control plane, pods
  stay in k8s" goal without a NATS-KV witness, and it is the answer to the failover-*speed* axis
  YU11 never delivered: Raft election is ~150–300ms vs YU11's ~17s kind promotion (Lease/heartbeat,
  fast-witness off). Combined with the decoupled FIX/REST gateway that repoints on the leader
  signal, this is the path to the three-digit-ms **client-observed** failover target.
- **It is the purpose-built tool.** Aeron Cluster is Raft + the Aeron transport + a replicated
  deterministic state machine, built by the Aeron/LMAX-lineage authors for fault-tolerant matching
  engines. The problems YU11 fought (two-publication position spaces, cross-epoch recovery,
  fencing) are first-class solved primitives here.

### The measured baseline it must beat or match

From `scripts/bench/results/yu11-transport-2026-07-17.md` (same cluster/params):
- Transport: Aeron MDC 520,520 events/s vs File-NATS HA 10,561 (~49×).
- E2E on GKE: Aeron HA 25,149 booked/s (single-BLP parity, +149% over NATS HA).
Aeron Cluster uses the same Aeron transport, so throughput is not expected to regress; majority
commit adds a round-trip on the commit path, but the current design already awaits a follower ACK,
so the delta is small. Re-run the same harness (`run-gke-bench.sh`, label `aeron-cluster`) as the
gate.

## How Aeron Cluster works, mapped to THIS codebase

**The reassurance to lead with:** the scary word "rewrite" is mostly *deleting hand-built
infrastructure* and hosting the *already-deterministic* `MatchingEngine` inside a different
container. The crown-jewel logic — matching, risk, the deterministic single-threaded state machine —
is reused almost as-is. You are replacing plumbing (replication, election, recovery, fencing) with a
battle-tested library, not rewriting the engine.

Each cluster node (3 or 5, odd for quorum) runs: Media Driver + Archive + **Consensus Module**
(the Raft engine) + **Clustered Service Container** hosting your `ClusteredService`. The Consensus
Module handles election, log replication, and commit. Your service implements the callbacks.

### The replacement map

| Current (YU02–YU11, hand-built) | Under Aeron Cluster | Action |
|---|---|---|
| `MatchingEngine` deterministic core | Body of `ClusteredService.onSessionMessage()` | **REUSE** ~as-is — already a deterministic state machine |
| `BlpRiskState` two-tier risk | Runs inside `onSessionMessage` | **REUSE** logic |
| `Journaler` / input journal | Consensus log (Raft-committed) | **DELETE** |
| `SnapshotStore` / `snapshot.dat` | `onTakeSnapshot(publication)` + load on `onStart` | **ADAPT** — serialization reused, trigger/storage becomes the cluster's |
| `JournalReader` / recovery / **codeX's cross-epoch bootstrap** | Automatic member recovery: snapshot + log replay + dynamic join | **DELETE / REPLACED** — this is the big one |
| `LeaderElection` (k8s Lease) | Raft leader election | **DELETE** |
| `FastWitness` (NATS KV CAS) | Raft majority | **DELETE** — no external witness |
| `AeronReplicator` / `AeronReplicationFollower` / MDC publication machinery | Consensus log replication | **DELETE** most; **REUSE** the SBE codecs (`AeronReplicationCodec`, wire format) as message encoding |
| `AeronPeerControlAgent` / heartbeats / HMAC auth | Raft internal heartbeats; Cluster `AuthenticatorSupplier` | **ADAPT** — fold HMAC identity into Cluster auth if peer auth is wanted |
| DB read-model / `ProjectorHandler` / CQRS side | Unchanged — cluster emits committed outputs, projector drains to MariaDB | **KEEP** |
| NATS (control feeds, pricing, EOD, bus) | Same roles, minus replication + witness | **KEEP** (shrunken role) |
| FIX gateway (see below) | Ingress in front of the cluster client | **BUILD** (orthogonal — needed either way) |

Key `ClusteredService` callbacks to implement: `onStart` (load snapshot / begin), `onSessionOpen`
/ `onSessionClose`, `onSessionMessage` (the order entry point — MatchingEngine goes here),
`onTimerEvent`, `onTakeSnapshot`, `onRoleChange`.

### k8s deployment

- 3-pod (or 5) StatefulSet, each pod a full cluster member; headless service for member discovery;
  per-pod PVC for the log + snapshots (like the current per-pod PVCs).
- Static member list keyed on the StatefulSet ordinal, or Aeron Cluster dynamic membership (which
  maps to StatefulSet scaling — keep odd counts for quorum).
- **No k8s control-plane dependency for election** — the goal, achieved natively.
- Same `blp-pool` dedicated-core pinning for the single-threaded service thread.

## What survives the migration (build/keep regardless)

- **FIX gateway (decoupled from the BLP).** Orthogonal to how the BLP achieves consensus, and
  required for client-observed fast failover on the FIX path. Today the QuickFIX/J acceptor runs
  *in-process* (YU10 ADR-034), so a counterparty's FIX session dies with the pod → reconnect +
  re-logon + sequence renegotiation = seconds, independent of election speed. Decouple it: a
  separate `fix-gateway` tier terminates the counterparty session and forwards to whichever node is
  leader (the Aeron Cluster client handles "route to leader" natively). On failover the gateway
  keeps the counterparty session alive and re-points internally — no disconnect. Start with a
  stateless-forward gateway (session store on the gateway side); add gateway-tier HA only if
  gateway durability becomes a requirement. Cost: ~one in-cluster hop (~100–300µs) to erase
  multi-second failover outages — an overwhelmingly good trade for an OMS whose HA value is
  failover-transparency. The gateway is *also* the fast routing layer for REST (holds warm
  connections to members, flips on the leader signal), so it solves FIX-session-survival and
  routing-repoint in one tier.
- **The SBE wire codecs and golden vectors** — reusable as the cluster message encoding.
- **The risk gateway, read-model/projector, settlement, EOD, tick-store, the whole CQRS/downstream
  side** — these sit downstream of committed output and are largely unaffected. Verify each, but
  expect no structural change.
- **The benchmark harnesses** (`run-gke-bench.sh`, the Phase-0 transport test) — reuse as the
  regression gate.

## Suggested phasing (de-risk cheaply first)

1. **Spike:** `MatchingEngine` as a single-node `ClusteredService`; prove an order round-trips
   through the consensus log and matches. This de-risks the one question that matters most — does
   our engine fit the callback mold — for very little effort.
2. **3-node on kind:** prove leader election + automatic follower catch-up (the thing that is
   hand-built and hard today, free here).
3. **Snapshot:** port the snapshot serialization into `onTakeSnapshot` / load; prove
   snapshot + recovery of a joining member.
4. **Failover proof:** kill the leader; prove election + client-session survival via the cluster
   client (or the FIX gateway); measure client-observed failover time (target: three-digit ms).
5. **FIX gateway** (parallelizable with 2–4).
6. **Benchmark** vs the YU11 Aeron HA numbers, same harness.
7. **GKE 3-node deploy** on the `blp-pool` (scale it to ≥3).

## Risks and open questions

- **Node count:** 3 (tolerate 1 failure) vs 5 (tolerate 2). Start with 3; +50% blp-pool cost vs the
  current 2. 5-node is +150%.
- **Throughput of majority commit:** expected comparable (same transport; current design already
  awaits a follower ACK). Prove it, don't assume it.
- **Ingress model:** Aeron Cluster has its own client protocol. Front it with the FIX/REST gateway
  that speaks counterparty protocols outward and the Cluster client inward.
- **Old journal/snapshot format:** almost certainly throwaway (fresh cluster) — this is a research
  system, no migration path needed. Confirm nobody depends on the YU02–YU11 on-disk format.
- **Risk edge-replica model:** the two-tier risk currently pushes policy to gateway replicas at the
  edge. Decide whether that folds into the cluster service or stays at the (new) gateway tier.
- **Aeron Cluster version:** open source (Apache 2), part of the Aeron repo. Align with the Aeron
  1.51.0 already in use.
- **Operational surface:** Aeron Cluster brings its own runtime concerns (membership, snapshot
  management, backup). Less k8s-turnkey than StatefulSet+Lease — budget learning time.

## What to still invest in on the 2-node path — and what to stop

With the migration confirmed as the direction, the 2-node path is the interim waypoint, not a
competing destination. So **don't sink new effort into machinery Cluster replaces** — skip
fast-witness enablement (P5) and lease-tuning (P2) unless a near-term demo genuinely needs
sub-second failover *before* Cluster lands. **Do build the decoupled FIX gateway and the fast
routing layer** — both are needed under Cluster too, so they're never wasted. And codeX's recovery
bundle is now finished and proven (see below) — its snapshot-completeness findings are load-bearing
requirements for the Cluster implementation, not just demo scaffolding.

## What codeX's recovery work proved — and the hard requirements it hands YU12

codeX **completed and proved** the cross-epoch recovery bundle (five committed slices
`fb4bf13`→`aefc885`; full evidence in `PROOF-yu11-cross-epoch-recovery-2026-07-17.md`). An empty-PVC
replacement, unable to prove the state before a nonzero epoch-start recording (`epochStartInputSeq
9685`, local tail −1), requested an authenticated snapshot bundle, installed it at an exact Aeron
marker (`inputSeq 10492 @ position 77568`), replayed **zero** local tail, merged into the recording,
and reached 2/2 Ready with a **matching read-model SHA-256 on both nodes** — then **promoted on a
second crash**. The P1 "one-shot failover" gap is closed for the YU11 custom transport.

Aeron Cluster **supersedes the mechanism** — its log + snapshot + dynamic member join replace the
bundle transport, marker probing, and Archive-catalog negotiation entirely. But codeX's work
distilled **four correctness invariants that transfer directly, and that YU12 must bake in from the
start.** These are worth more than the code:

1. **A snapshot is valid only when bound to exactly one applied replicated-log position.** In YU11
   the snapshot had to pin one exact Aeron marker; a general sequence map got overwritten by
   upstream run-ahead, forcing a dedicated primitive marker register. Cluster analogue:
   `onTakeSnapshot` state must correspond to the service's exact applied log position.
2. **The recovered service resumes *after exactly that boundary*** — no re-applying tail the
   snapshot already covers, no gap.
3. **Snapshot completeness covers every future-output generator and admission dependency — not just
   the visible order book.** This is the one that bit hard (see nextOrderRef below): `nextOrderRef`,
   trade IDs, idempotency state, risk reservations, symbol-table identity, and policy/control
   versions all need explicit snapshot/replay invariants.
4. **Acceptance must be adversarial about completeness:** issue orders *after* a snapshot, recover
   from snapshot + tail, promote the recovered node, and assert the next generated order ID is
   **strictly greater than every ID ever issued** — not merely greater than every ID still retained
   in memory (terminal-order eviction can drop the highest historical reference).

### The nextOrderRef bug — inherited, diagnosed, NOT fixed (a YU12 requirement)

The zero-tail bundle install exposed `12 orders warm, nextRef 8`; the promoted node then reissued
`ord-013-0008`. **codeX's root cause (complete):** the monotonic ID generator lives *outside* the
deterministic replicated state — journal replay and follower injection apply `ORDER_NEW` to the book
without advancing `nextOrderRef`, so a later snapshot captures newer orders with a stale counter.
Zero-tail install didn't create the bug; it removed the paths (DB warm-up max-recompute, no-new-ID-
before-restart) that had always masked it. This is the concrete instance of invariant 3, and it is
**not fixed on the committed branch.** codeX built a three-seam diagnostic repair and proved it live
(IDs 0013/0014 pre-crash → 0015 post-promotion) but deliberately did **not** commit it — it crosses
three state-transition seams and a `max(retained orders)` fallback is not a complete invariant under
terminal-order eviction. **Do not port the three-seam patch.** In YU12 it dissolves correctly: make
the ID generator part of snapshotted/replicated state and assert on recovery that it exceeds every
ID ever issued.

### Risk-replica lag — a readiness/admission invariant for YU12

At the recovery boundary the matching/read-model state matched, but the independent gateway control
replicas did not (`follower ready=false, watermark 954 vs primary 2897`) — even while Kubernetes
reported the pod `2/2 Ready`. YU12 must **distinguish deterministic risk state carried in the
Cluster snapshot/log from asynchronously-refreshed control-feed/gateway state used to admit
commands**, and must not expose admission until the latter is valid (or rebuild it from the same
Cluster snapshot rather than an independent feed). Two readiness semantics silently coexisting is
the trap.

## Acceptance bar for YU12 (definition of done)

1. **No-ID-reuse recovery proof** (invariant 4 above): orders after a snapshot → recover from
   snapshot + log tail → promote the recovered node → next order ID strictly greater than every ID
   ever issued. Plus the corruption/interrupted-install/epoch-change matrix codeX already wrote
   tests for.
2. **Client-observed failover** in three-digit ms — kill the leader, measure time to accepted
   traffic through the FIX/REST gateway (not just the internal election).
3. **Throughput** at or above the YU11 Aeron HA baseline (25,149 booked/s GKE) via the same
   `run-gke-bench.sh` harness, label `aeron-cluster`; plus all inherited allocation gates + noGcTest.

## State of the world the new chat inherits

- **Branch `YU11-aeron-replication`, HEAD `6afd30d`.** Seven commits ahead of `origin` (origin is at
  `83049c8`, pushed earlier for the GKE deploy). The recovery slices are **not pushed** — respect
  never-push-without-OK.
- **Working tree clean** except this untracked handoff. The nextOrderRef diagnostic fix is **not** in
  the tree — codeX removed it before handing off.
- **GKE is live on YU11 Aeron HA**: image `yu11-aeron-20260718` = commit `83049c8` (*pre*-recovery-
  slices), 2/2 primary+standby, 25,149 booked/s measured. It carries neither the recovery slices nor
  the nextRef fix. The fable lane owns this deployment — scale down the matcher / `blp-pool` when
  done with it.
- **kind cluster `kind-traderx-yu11-aeron` is still running**, both matchers 2/2 — but on a **local
  diagnostic image with the uncommitted counter fix** (`sha256:8f1a5751…`), which is NOT the
  committed branch. Throwaway diagnostic env, not a reference build.
- **codeX is out of weekly usage** — it will not continue YU11 or start YU12. YU12 is the new fable
  chat's, solo.

## First reading for the implementer

- Aeron Cluster docs + the `ClusteredService` / `EchoService` examples in the Aeron repo.
- **`PROOF-yu11-cross-epoch-recovery-2026-07-17.md`** — the recovery evidence, the exact nextRef
  root cause, the risk-replica lag, and the "transfers to Aeron Cluster" list. Read this closely; it
  is the source for the requirements section above.
- This codebase: `MatchingEngine`, `LmaxEngine` (the recovery/role-change orchestration Cluster
  subsumes), `SnapshotStore` (serialization to adapt into `onTakeSnapshot`), `AeronReplicationCodec`
  (the reusable wire format).
- The YU11 measured baseline (`scripts/bench/results/yu11-transport-2026-07-17.md`) — the numbers to
  match.
- `RECAP-yu11-full-2026-07-17.md` (full engagement history — the superseded first-session no-go
  conclusion, the fable overnight fixes, and the codeX five-slice addendum) and
  `ISSUES-yu11-e2e-2026-07-17.md` (hand-built-path problem inventory, most of which Cluster closes
  structurally).

Constraints carried forward: correctness-first (sell-side OMS trajectory); `risk.entitlement.
enforced` stays false; never push without explicit OK; new state follows the YUxx lineage
conventions (own branch + worktree, commit-but-never-push).
