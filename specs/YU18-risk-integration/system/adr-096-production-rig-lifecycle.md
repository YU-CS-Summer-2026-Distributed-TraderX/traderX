# ADR-096: The production rig is brought up at bench parity by one script, and its gateways scale

Status: accepted 2026-10-29 (yaakov: rollout 5a "yes, optimized for throughput also"; client path option A; FIX store
on and measured). Code and scripts on `traderX-risk-integration`; not yet run on production. Pack:
[production rig](../maintenance/production-rig/spec.md) (FR-PRG-01..09).

## Context

Production `traderx` (cluster `traderx-demo`) is not up around the clock: it is scaled to zero between uses. Its per-
node configuration had drifted from the bench that set its numbers. The hxb 400K/s hour (10-28, f15hf2 / b15ho:
FIX p99 3.54 ms, binary p99 2.67 ms) ran:
- members on c4d-standard-8 with gen-ZGC 6g / SoftMax 3g, 7 CPU, writeback and receive-buffer sysctls;
- gateways one per dedicated c4d-standard-8 with 4 pinned CPUs;
- clients connected straight to the gateways.

Production ran:
- members on G1 with a 12 GB heap in 3 CPUs, with no sysctls;
- one burstable gateway (250m request) sharing a node with NATS, the database and the console;
- no client path to the gateways except through the console.

Bring-up and shutdown were by hand. A cold start restores the members from their snapshots, and on 10-29 the
snapshot restore was found not to fail closed over the real channel (FR-ERH-F12). So a bring-up must also prove the
restore before any order reaches the members.

## Decision

### 1. One rig, at bench parity per node (FR-PRG-05)

| Tier | Pool | Machine | Pod |
|---|---|---|---|
| Members | `engine-pool`, 3 nodes, sysctls `vm.dirty_*` + `net.core.rmem_*` | c4d-standard-8 | 7 CPU / 16Gi; `-XX:+UseZGC -XX:+ZGenerational -Xms12g -Xmx12g -XX:+AlwaysPreTouch -Daeron.socket.so_rcvbuf=4194304`; `MAX_ACTIVE_BOOKS=1024` |
| Gateways | `gateway-pool`, one node per gateway, static CPU manager | c4d-standard-8 | 4 CPU / 2Gi guaranteed; gen-ZGC 768m (unchanged) |
| NATS | `nats-pool`, 1 node | c3-standard-8 | unchanged; c3 because its JetStream claim is pd-balanced, which C4D cannot attach |
| Everything else | `default-pool` | e2-standard-4 | unchanged |

**The member heap is production's 12 GB, not the bench's 6 GB.**
- **The heap sets the book cap.** With `MAX_ACTIVE_BOOKS` unset, the book cap is derived from the heap (ADR-079 §6).
  Every touched security's book costs ~7 MB under ZGC.
- **6 GB would cut capacity.** The cap would drop from production's 1,024 books (12 GB G1) to ~436. An order on a new
  security past it is refused `BOOK_CAPACITY`, so a heap change would change what the engine decides, replay
  included.
- **The bench is not evidence here.** Its members held few books (idle heap ~260 MB); 1,024 books need ~7.2 GB.
- **Production keeps what bought the latency:** generational ZGC instead of G1's stop-the-world pauses, 7 CPUs, and
  the sysctls. It also keeps its 12 GB heap and pins `MAX_ACTIVE_BOOKS=1024`, today's derived value, so no heap
  change can move the cap.
- **No SoftMax target.** The bench needed one because a 3 GB heap left ~180 MB at the young-GC trigger (f15hx). A
  12 GB heap holding at most ~7.2 GB of books leaves gigabytes.

`prepare.py` renders the placement, sizes and drain settings. `validate()` refuses a candidate without them, a heap
that cannot hold the pinned cap, or no pin at all.

### 2. Lifecycle: `scripts/gcp-compsci/rig/rig.sh pools|up|down|status` (FR-PRG-01..04)

- **`up`**, in this order:
  1. Gateways are held at 0 (HPA removed, autoscaling off).
  2. Members are held at 0. A StatefulSet left at 3 while its nodes were at 0 has Pending members, which would start
     with the first engine node.
  3. Pools are resized and the client VM started.
  4. Support workloads come up, then the read model is fingerprinted.
  5. **G-pre** runs (§2a).
  6. The members start from their retained claims.
  7. The **restore gate** runs (§2a).
  8. N gateways start, then the internal LB.
  9. Autoscaling is switched on, if asked for.
  10. A FIX smoke order runs, checked at the effect end.
  - A re-run on a rig whose members are up and covered by the gate record holds nothing and re-gates nothing.
- **`down`**, in this order:
  1. Gateways go to 0 first; each drains (§4). The internal LB is deleted.
  2. A snapshot barrier is taken, and a **shutdown baseline** is written to a ConfigMap.
  3. Members stop; each must exit 143.
  4. Every rig pool goes to 0 and the VM is stopped.
  5. The result is verified with `gcloud compute instances list`.
  - A member that does not exit 143 is reported, but the teardown still finishes, and the run exits non-zero:
    stopping halfway would leave every pool billing.
- **Every step checks live state first**, so `up` and `down` can be re-run.
- **Any check that does not hold stops the run** with `REFUSED` and a non-zero exit.
- **`--dry-run`** prints the plan and contacts nothing.

#### 2a. The restore gate (FR-PRG-03)

Applied alone is not evidence: a load that skipped a record still ends at the full log's applied. Agreement across
members is not enough: a record type that every member's loader refuses fails the same way on all three. So the
reference never comes from a snapshot.

| Check | Holds when |
|---|---|
| G-pre | before any member starts: each member claim's cluster error log, read by ClusterTool in a read-only pod, has no "snapshot corrupt", "NEWER" or "unknown snapshot record type". Production's 00b31d33 skipped a refused snapshot record silently (Aeron's `Image.poll` swallows the handler's exception), so such a line means that member once came up partial, and the bring-up stops before anything else runs |
| G0 | the three members agree on applied, engineApplied, trades and both hashes |
| G1 | each member's replay from log position 0 (`/recon/full-history/reindex`) reaches its applied with its trade count |
| G2 (check A) | each member's strict full-log replay into a fresh shadow (`/recon/shadow-state`, FR-ERH-F13) is `complete` at the same applied and equals `/health?digest=1`: book hash, position hash, open orders, positions, trades |
| G3 (check B) | the read model's md5s are unchanged since before the members started, and its open rows equal the members' open orders |
| G4 | the members equal the last `down`'s shutdown baseline, when one exists |

Gateways stay at 0 until all of these pass. A build without `/recon/shadow-state` fails G2 on `traderx`.
`GATE_ALLOW_NO_SHADOW=1` exists for rehearsals in a side namespace only. A pass is recorded in ConfigMap `rig-gate`
with the member pod UIDs, and `gateways.sh` refuses to run for members the gate did not cover.

### 3. Gateway count is the rig's, not the pack's (FR-PRG-06, FR-PRG-09)

- **The pack carries no gateway replica count.** `rig.sh up` sets it, `gateways.sh N` changes it, and the HPA moves
  it when autoscaled.
- **One gateway per node** (required hostname anti-affinity on a static-CPU-manager pool), so the gateway count is
  the `gateway-pool` node count.
- **`gateways.sh N`, to grow:** the pool grows first, then the Deployment.
- **`gateways.sh N`, to shrink:**
  - The newest gateways go (pod-deletion-cost), and each drains (§4).
  - Each drained gateway must log `GATEWAY-DRAIN done inflight=0`.
  - The script reports each FIX session's logout-to-logon time on a remaining gateway.
  - Exactly the emptied nodes are deleted from the pool's instance group, never a node that still runs a gateway.
- **`AUTOSCALE=on`:** an HPA on CPU (60 %), from `GATEWAYS` to 18.
  - Up: 4 gateways a minute.
  - Down: after 30 minutes of low CPU, one gateway per 10 minutes, never below `GATEWAYS`.
  - The pool's cluster autoscaler (min `GATEWAYS`, max 18) adds a node per pending gateway and removes an emptied one.
- **Signal: CPU, chosen over the gateway's in-flight gauge.** HPA reads CPU natively. The gauge would need a
  custom-metrics adapter, a new component with its own admission. Two notes on CPU:
  - the gateway owner thread spins while anything is in flight, so a gateway under any load reads about one of its
    four cores;
  - the 60 % target sits above that floor.

### 4. A gateway drains on SIGTERM (FR-PRG-07)

The gateway had three shutdown hooks (FIX stop, binary stop, HTTP stop plus cluster close). The JVM runs hooks
concurrently, so the cluster session could close while orders were in flight. It now has one ordered drain:

1. **Keep serving for `GATEWAY_DRAIN_DELAY_MS` (30 s on the rig).** The pod is terminating, so its node fails the
   internal LB's health check (8 s × 3). Meanwhile kube-proxy still forwards connections to the serving, terminating
   endpoint.
2. **Stop new input.**
   - Log every FIX session out, keeping its store open: an answer that arrives after the Logout is stored and resent
     where the client resumes (ADR-086).
   - Stop accepting binary connections.
   - Stop the HTTP server once its exchanges are answered.
3. **Wait** until nothing has been in flight for 200 ms, at most `GATEWAY_DRAIN_TIMEOUT_MS` (the ack deadline + 2 s).
4. **Release the FIX leases, then close the cluster session.**

`terminationGracePeriodSeconds` is 60, which covers 30 + 12 + margin.

### 5. Client path: an internal passthrough LB (FR-PRG-08)

`order-matcher-gw-ilb` exposes FIX (18130) and REST (18110) straight to the gateway pods.
- **Placement:** internal to the VPC. `externalTrafficPolicy: Local` gives no second hop through kube-proxy.
- **Spread:** no session affinity. A FIX session resumes on any gateway (ADR-086), so a new connection may hash
  anywhere.
- **Access:** `loadBalancerSourceRanges` is the in-region client VM's address only.
- **Binary is not exposed.** Its frames carry the account and it has no logon, so it stays in-cluster until it has
  one.
- **External FIX for the operator's Mac** is separate work: FIX over TLS on an allowlisted entry, ADR-095.

## Consequences

- **Cost per hour at parity, list prices:**

  | Item | $/h |
  |---|---|
  | Members 3 × c4d-standard-8 | 1.14 |
  | `default-pool` 3 × e2-standard-4 | ~0.40 |
  | NATS c3-standard-8 | ~0.35 |
  | Client VM c4d-standard-8 | 0.38 |
  | Internal LB | ~0.03 |
  | Base with 2 gateways (each c4d-standard-8 adds 0.38) | **~3.05** |
  | 18 gateways | **~9.15** |

  `down` takes it to zero instances. The retained claims (3 × 32 GiB Hyperdisk, NATS and DB pd-balanced) and the
  control plane still bill at zero.
- **Rebalancing:** a scale-up adds capacity for new connections. FIX sessions already connected stay on their
  gateways until they reconnect. Rebalancing them (logging a share out of the busiest gateways) is not part of this
  decision.
- **`rig.sh` owns the gateway count.** A pack re-apply (5a, 5b) leaves the live count, because the field is absent.
  The first apply after this change resets it to Kubernetes' default of 1, so run `gateways.sh` or `rig.sh up` after
  it.
- **The member JVM change and the gateway drain ship in rollout 5a.** They are node-local and gateway-only, so no
  member is rolled one at a time and the apply path is unchanged.

## Evidence (offline, 2026-10-29)

- **`FixSessionPersistenceIT.aDrainingGatewayLogsOutAndStillStoresTheAnswerInFlight`:**
  - The client is logged out while an order's answer is 4 s away; it resumes on the other replica and gets that
    answer, resent as number 3.
  - Negative control: stopping the acceptor at the Logout (the old hooks) leaves nothing stored, and B logs on at 3.
    The test fails.
  - 9/9 in the class.
- **`test_prepare`:** 38/38. The three new rig tests fail on the previous `prepare.py`.
- **`rig/test_rig.py`:** 22/22, stable over 3 runs, covering:
  - the up and down order, with real `rig.sh` functions and kubectl/gcloud stubbed;
  - each gate check refusing, with no gateway scaled up after a refusal;
  - a member's bad exit still ending at zero;
  - a dry run contacting nothing;
  - argument refusals.
  - G-pre: each past-restore message refused, an unreadable log refused, read-only mounts.
  - Members held at 0 before any pool grows, and a gated re-run that stops nothing.
  - Mutations: with the gate removed from `up`, 14 of the 18 `up` tests fail; with G-pre removed, 10 fail.
