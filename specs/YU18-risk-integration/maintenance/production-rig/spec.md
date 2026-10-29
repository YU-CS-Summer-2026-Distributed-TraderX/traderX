# Production rig specification

Status: new 2026-10-29. Scope: bringing production `traderx` up at bench parity and down to zero, its restore gate,
and the gateway count. Decision: [ADR-096](../../system/adr-096-production-rig-lifecycle.md).

## Requirements

- **FR-PRG-01:** `rig.sh up` SHALL run in this order: gateways held at 0 (HPA removed, pool autoscaling off),
  members held at 0 (a StatefulSet left at 3 while its nodes were at 0 has Pending members that would otherwise start
  with the first engine node), rig pools resized and the client VM started, support workloads Ready, read-model
  fingerprint, G-pre (FR-PRG-03), members to 3 Ready, the restore gate (FR-PRG-03), N gateways (FR-PRG-06), the
  internal LB (FR-PRG-08), autoscaling when `AUTOSCALE=on` (FR-PRG-09), the smoke order (FR-PRG-04).
  - On a rig whose three members are Ready and covered by `rig-gate`, `up` SHALL hold nothing and re-gate nothing; it
    resizes the pools and continues from the gateways.
  - Every step SHALL check live state before it changes it, so a re-run is safe.
  - A check that does not hold SHALL stop the run with `REFUSED` and exit non-zero, and no later step SHALL run.
  - `--dry-run` SHALL print every step and contact neither kubectl nor gcloud.
  - `GATEWAYS` outside 1..18, `AUTOSCALE` other than on/off, or `AUTOSCALE=on` with 18 gateways SHALL be refused.
- **FR-PRG-02:** `rig.sh down` SHALL:
  - scale the gateways to 0 first (each drains, FR-PRG-07) and delete the internal LB;
  - when all three members are Ready, take a snapshot barrier (ClusterTool on the leader with its own
    `JAVA_TOOL_OPTIONS`, `traderx_cluster_snapshots` advanced on all three) and record ConfigMap
    `rig-shutdown-baseline`;
  - stop the members and require each container to have terminated with exit code 143;
  - resize every rig pool to 0, stop the client VM, and verify with `gcloud compute instances list` that no rig node
    instance remains and the VM is TERMINATED.
  - A member that did not exit 143 SHALL be reported, the teardown SHALL still complete, and the run SHALL exit 3.
- **FR-PRG-03:** Restore gate. Before the members start, G-pre SHALL read each member claim's cluster error log in a
  read-only pod while no member runs, and SHALL stop the bring-up on "snapshot corrupt", "NEWER" or "unknown snapshot
  record type" (a past restore that loaded a snapshot partially, before FR-ERH-F12), or on a log it could not read.
  - It SHALL read the error logs of both the consensus module's mark file (`cluster-mark.dat`) and the service
    container's (`cluster-mark-service-*.dat`). A refused snapshot record is logged in the service container's.
  - A claim without both mark files SHALL count as unreadable. After they start, gateways SHALL stay at 0 until every check
  passes, and `up` SHALL stop on the first that fails. The checks are G0–G4 (table below); G2 and G3 are checks A
  and B.
  - G2 SHALL refuse on namespace `traderx` when a member has no `/recon/shadow-state`. `GATE_ALLOW_NO_SHADOW=1`
    SHALL skip it only in another namespace.
  - A pass SHALL be recorded in ConfigMap `rig-gate` with the members' pod UIDs.

  | Check | Holds when |
  |---|---|
  | G0 | all three members report the same applied, engineApplied, trades, order hash and position hash, and applied = engineApplied > 0 |
  | G1 | each member's `/recon/full-history/reindex` reports `replayedAppliedSeq` = applied and `indexedTrades` = trades |
  | G2 | each member's `/recon/shadow-state` is `complete` at the same applied and equals its `/health?digest=1` on bookHash, positionHash, openOrders, positions and digestTrades |
  | G3 | the read model's trades, positions and orderbook md5s equal those read before the members started, and its NEW / PARTIALLY_FILLED rows equal the members' openOrders |
  | G4 | when `rig-shutdown-baseline` exists, the members' applied, engineApplied, trades and hashes equal it |
- **FR-PRG-04:** The smoke order SHALL go through a gateway over FIX from `web-front-end-console`.
  - It is a resting Buy 1 two percent under the mark (inside the collar), acked New, then cancelled.
  - The order's `orderbook` row SHALL reach CANCELED within 60 s.
  - The FIX password SHALL NOT appear in any line it prints.
- **FR-PRG-05:** The production candidate (`prepare.py`) SHALL:
  - place members on `engine-pool`, gateways on `gateway-pool` and NATS on `nats-pool`, each with its taint's
    toleration, and everything else on `default-pool`;
  - run members with 7 CPU / 16Gi, generational ZGC at a 12 GB heap (`-Xms12g -Xmx12g -XX:+AlwaysPreTouch`), and a
    4 MiB Aeron `so_rcvbuf`;
  - pin `MAX_ACTIVE_BOOKS` to 1,024, so the book cap (an apply-path decision, ADR-079 §6) cannot move with the heap.
  - `validate()` SHALL refuse a candidate that breaks any of these, including one whose heap cannot hold the pinned
    cap at ~7 MB per book with 50 % headroom.
  - `rig.sh pools` SHALL:
    - give `engine-pool` the bench sysctls (`vm.dirty_*`, `net.core.rmem_*`), and only while it has no nodes;
    - create `nats-pool` on c3-standard-8, so that it can attach NATS's pd-balanced claim;
    - create `gateway-pool` on c4d-standard-8 with the static CPU manager.
- **FR-PRG-06:** Gateways run one per node.
  - The Deployment SHALL have no replica count in the pack, a required hostname anti-affinity, and a guaranteed
    whole-CPU pod (4 CPU / 2Gi). `validate()` SHALL refuse otherwise.
  - `gateways.sh N` SHALL refuse unless `rig-gate` covers the members now running.
  - To grow, it SHALL resize the pool to N before the Deployment.
  - To shrink, it SHALL:
    - remove the newest gateways;
    - require each to log `GATEWAY-DRAIN done inflight=0`;
    - report each FIX session's logout-to-logon time on a remaining gateway;
    - delete from the pool's instance group exactly the nodes left without a gateway.
  - It SHALL end with N gateways on N distinct nodes.
- **FR-PRG-07:** On SIGTERM a gateway SHALL drain in one shutdown hook:
  1. Keep serving for `GATEWAY_DRAIN_DELAY_MS`.
  2. Log every FIX session out with its store still open; stop accepting binary connections; stop the HTTP server
     once its exchanges are answered.
  3. Wait until nothing has been in flight for 200 ms, at most `GATEWAY_DRAIN_TIMEOUT_MS` (default: the ack deadline
     + 2 s).
  4. Release the FIX leases, then close the cluster session. It SHALL log `GATEWAY-DRAIN begin` and
     `GATEWAY-DRAIN done inflight=<n> elapsedMs=<t>`.

  An answer that arrives after a session's Logout SHALL be stored and resent where the client resumes.

  On the rig the delay SHALL be at least 25 s, and `terminationGracePeriodSeconds` at least the delay + 15 s.
- **FR-PRG-08:** Service `order-matcher-gw-ilb` SHALL be an internal passthrough LoadBalancer to the gateway pods,
  with:
  - FIX 18130 and REST 18110;
  - `externalTrafficPolicy: Local` and no session affinity;
  - `loadBalancerSourceRanges` set to the client VM's address /32.

  The binary port SHALL NOT be exposed on it.
- **FR-PRG-09:** With `AUTOSCALE=on`, an HPA SHALL scale `cluster-gateway` on CPU at 60 %, from `GATEWAYS` to 18:
  - up by at most 4 gateways a minute;
  - down only after a 30-minute stabilization window, by 1 gateway per 10 minutes, never below `GATEWAYS`.

  `gateway-pool`'s cluster autoscaler SHALL run with min `GATEWAYS` and max 18. `gateways.sh N` on an autoscaled
  rig SHALL set both minimums to N.
- **FR-PRG-10:** The image population SHALL be declared in `scripts/gcp-compsci/population.json`: one default source
  commit, and per workload at most one exception (image, component, full source SHA, not the default).
  - `prepare.py`, `measure-admission.py`, `admit.py` and `admission-controls.py` SHALL refuse:
    - an image built from neither declared commit;
    - a default image built from an exception's commit;
    - an exception image built from the default commit;
    - an exception image on another workload, or a declared workload on another image;
    - a missing exception image, two digests for one image name, and an exception without its own derivation.
  - The members' commit SHALL decode the tip gateway's unflagged ingress byte for byte, shown with both sides' real
    compiled codec (`rig/codec-compat.sh`).

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| client logged out with an answer 4 s away | 07 | `FixSessionPersistenceIT.aDrainingGateway…` | resumes on the other replica, answer resent as number 3, PossDup |
| same, acceptor stopped at the Logout (old hooks) | 07 | same test, negative control | nothing stored; B logs on at 3; test fails |
| rendered candidate | 05, 06, 07 | `test_prepare` rig tests | pools and tolerations, gateway shape, member ZGC heap and pinned cap; 3 fail on the previous renderer |
| broken candidates | 05, 06, 07 | same | gateway on default-pool, a replica count, no anti-affinity, 250m CPU, 30 s grace, no drain delay, the G1 shape, the bench's 6 GB heap, no `MAX_ACTIVE_BOOKS`: each refused |
| up order | 01, 03 | `test_rig.Up.test_gateways_start_only_after…` | gateways 0 → pools → members → `rig-gate` → gateways → ILB → smoke |
| each gate check failing | 03 | `test_rig.Up.*refused*` | REFUSED Gn; no gateway scaled up; no `rig-gate` |
| a past partial restore in a member's error log | 03 | `test_rig.Up.test_a_member_that_once_restored_partially…` | REFUSED G-pre for each of the three messages; no member started |
| error log unreadable, or members running | 03 | `test_rig.Up.test_an_unreadable…`, `…not_read_while_members_run` | refused |
| G-pre pods | 03 | `test_rig.Up.test_the_error_logs_are_read_read_only…`, `…test_the_pod_runs_the_reader_on_the_claim` | claim and mount read-only, the shipped reader on `/data/cluster`, all three read before the members scale to 3 |
| a refusal in the service mark file, as Aeron writes it | 03 | `test_rig.Up.test_the_reader_finds_a_refusal_aeron_logged_in_the_service_mark_file` (`rig/ErrlogProbe.java`, Aeron 1.51.0) | each of the three messages found in `cluster-mark-service-0.dat` and REFUSED; clean control passes; no service file = exit 3; reading the consensus file alone fails the test |
| Pending members at bring-up | 01 | `test_rig.Up.test_members_are_held_at_zero…` | StatefulSet to 0 before any pool resize |
| re-run on a gated rig | 01 | `test_rig.Up.test_a_rerun_on_a_gated_rig…` | nothing scaled to 0, no G-pre, no gate; gateways to N |
| build without check A | 03 | `test_rig.Up.test_a_build_without_check_a…` | refused on `traderx`, opt-out ignored there; skipped only in a side namespace |
| gateways sharing a node | 06 | `test_rig.Up.test_gateways_sharing_a_node…` | refused |
| down order | 02 | `test_rig.Down.test_gateways_then_barrier…` | gateways 0 → LB → barrier (ClusterTool with -Xmx128m) → baseline → members → pools → VM |
| a member exits 137 | 02 | `test_rig.Down.test_a_member_that_did_not_exit_143…` | exit 3, pools still at 0 |
| dry run | 01 | `test_rig.Plan` | contacts nothing; bad arguments refused |
| gate removed from `up` (mutation) | 01, 03 | `test_rig.Up` | 14 of 18 fail |
| G-pre removed from `up` (mutation) | 03 | `test_rig.Up` | 10 of 18 fail |
| malformed declarations | 10 | `test_population.Declaration` | bad schema, unknown field, short SHA, exception on the default commit, duplicate image: each refused |
| neither commit, each half of a mix, missing image | 10 | `test_population.Images`, `.Admit`, `.Measure` (pull with docker stubbed) | refused in `population.py`, `admit.check_population` and `measure-admission.pull` |
| wrong placement | 10 | `test_population.Measure`, `test_prepare.Population` | members on the tip image, member image on the gateway: refused by `measure-admission.population` and `prepare.validate` |
| guard removed (mutation) | 10 | same | each of the five guards removed: 1–6 tests fail |
| tip ingress on the members' codec | 10 | `rig/codec-compat.sh` | 42 encodings over 19 command types, 518 checks, 0 failures; both controls fire |
| live bring-up, down, scale and autoscale | 01–09 | rig proof, pending | see tasks |
