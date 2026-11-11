# Production rig specification

Status: new 2026-10-29. Scope: bringing production `traderx` up at bench parity and down to zero, its restore gate,
and the gateway count. Decision: [ADR-096](../../system/adr-096-production-rig-lifecycle.md); the snapshot read-model profile
and its gate (FR-PRG-20, FR-PRG-21, 2026-10-29): [ADR-097](../../system/adr-097-production-read-model-at-volume.md).

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
  - `--dry-run` SHALL be honoured anywhere after the action, including after a LABEL/SIZE/N argument, and SHALL then
    run no mutating command (reads of live state are allowed for the labelled actions). On 2026-11-06
    `archive-members e1106 --dry-run` ignored the flag and created the production snapshots.
  - `GATEWAYS` outside 1..18, `AUTOSCALE` other than on/off, or `AUTOSCALE=on` with 18 gateways SHALL be refused.
- **FR-PRG-02:** `rig.sh down` SHALL:
  - scale the gateways to 0 first (each drains, FR-PRG-07) and delete the internal LB;
  - when all three members are Ready, take a snapshot barrier (ClusterTool on the leader with its own
    `JAVA_TOOL_OPTIONS`, `traderx_cluster_snapshots` advanced on all three, each past its own count before the
    barrier, since the counter is per process and restarts when a member's pod is replaced) and record ConfigMap
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
  | G0 | all three members report the same applied, engineApplied, trades, order hash and position hash; applied > 0; and engineApplied = applied, or -1 (restored from the snapshot into an idle cluster, no engine event since) |
  | G1 | each member's `/recon/full-history/reindex` reports `replayedAppliedSeq` = applied and `indexedTrades` = trades; with the archive purge rendered (FR-PRG-40), its `/recon/tail-state` is `complete` at applied with `digestTrades` = trades |
  | G2 | each member's `/recon/shadow-state` is `complete` at the same applied and equals its `/health?digest=1` on bookHash, positionHash, openOrders, positions and digestTrades; with the archive purge rendered, its `/recon/tail-state` does, and an empty tail is INCONCLUSIVE (FR-PRG-40) |
  | G3 | the read model's trades, positions and orderbook md5s equal those read before the members started; its NEW / PARTIALLY_FILLED rows equal the members' openOrders; and its engine trade sides (`<n>-B` / `<n>-S`) are exactly 1..trades, each number once. A managed projection scope (`e1-<epoch>-` ids) is refused, not guessed |
  | G4 | when `rig-shutdown-baseline` exists, the members' applied, trades and hashes equal it (not engineApplied, which a restore resets to -1) |
- **FR-PRG-04:** The smoke order SHALL go through a gateway over FIX from `web-front-end-console`.
  - It is a resting Buy 1 two percent under the mark (inside the collar), acked New, then cancelled.
  - The order SHALL read CANCELED within 60 s in trade-processor's order read model (`GET /accounts/{id}/orders?status=all`,
    the one FIX status requests read, FR-FIX04): the memory order view in snapshot mode, the `orderbook` rows otherwise.
    Amended 2026-11-07: with `ORDER_FEED_PERSIST=false` SQL `orderbook` is never written, and production's `up` refused
    after a clean New/Canceled. Members without bridges (FR-PRG-14) end at the FIX answers.
  - The FIX password SHALL NOT appear in any line it prints.
- **FR-PRG-05:** The production candidate (`prepare.py`) SHALL:
  - place members on `engine-pool`, gateways on `gateway-pool`, NATS on `nats-pool`, and ClickHouse with history-feed
    on `history-pool` (trade-history-tiers FR-THT-02, 2026-11-07), each with its taint's toleration, and everything
    else on `default-pool`;
  - run members with 15 CPU / 40Gi on c4d-standard-16 (ADR-103, 2026-11-10; was 7 CPU / 16Gi on c4d-standard-8: at
    16Gi the archive's page cache and the term buffers put the cgroup in reclaim at 50-65 MB/s of log, and a stall past
    the heartbeat timeout was an election, RI34 §1b; at 40Gi the 520K step held 20 minutes with 200/100 ms and no election,
    2026-11-10), generational ZGC at a 12 GB heap (`-Xms12g -Xmx12g
    -XX:+AlwaysPreTouch`), and a 4 MiB Aeron `so_rcvbuf`;
  - pin `MAX_ACTIVE_BOOKS` to 1,024, so the book cap (an apply-path decision, ADR-079 §6) cannot move with the heap.
  - `validate()` SHALL refuse a candidate that breaks any of these, including one whose heap cannot hold the pinned
    cap at ~7 MB per book with 50 % headroom.
  - `rig.sh pools` SHALL:
    - give `engine-pool` the bench sysctls (`vm.dirty_*`, `net.core.rmem_*`), and only while it has no nodes;
    - create `nats-pool` as three c4d-standard-16 on Hyperdisk (ADR-103, FR-PRG-41; was one c3-standard-8 for a
      pd-balanced claim), and refuse an existing `nats-pool` of another machine type, naming the ADR;
    - `engine-pool` (operator-created) SHALL be c4d-standard-16 (ADR-103; was c4d-standard-8);
    - create `gateway-pool` on c4d-standard-8 with the static CPU manager;
    - create `history-pool` on c4d-standard-8 (Hyperdisk Balanced for the store's claim), tainted `traderx-history`;
      `up` brings it to 1 node and waits for `sts/clickhouse` with the support workloads; `down` takes it to 0.
  - `system-pool` (2026-11-07, created by the operator, not by `rig.sh`): one untainted e2-standard-2 kept at 0, so
    kube-dns and konnectivity-agent have a node when `default-pool` is at 0. A side rig SHALL resize it to 1 before it
    applies anything and to 0 at its teardown; it SHALL NOT create an untainted pool of its own. It is not in
    `RIG_POOLS`: `rig.sh` neither sizes nor zeroes it (docs/deployment/compsci-gke.md "Node plan").
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

  Note (2026-11-10, the acceptance run): the HPA cannot place long-lived sessions. A FIX or binary session connects
  once, so a gateway the HPA adds receives only the logons that come after it; at 600 sessions on 18 gateways each
  gateway ran 0.7 of 4 cores and the HPA would not have fired, while every session sat at its window. **Decided
  2026-11-10 (yaakov, "All yes"; ADR-103 amendment §1, option A): the standing shape is a fixed gateway count sized
  to sessions, `AUTOSCALE` off, the count measured after RI35's FIX fix.** The production candidate SHALL carry no
  HPA on `cluster-gateway` and `validate()` SHALL refuse one (`test_prepare.AcceptanceShape`); `AUTOSCALE=on` stays a
  rig option for a measurement, not the shape.
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
- **FR-PRG-11:** The rehearsal's load clients SHALL be opt-in (`prepare.py --fix-test-clients [N]`, default 7): CompIDs
  CLIENT2…CLIENT<N+1>, round-robin over the seven dedicated test accounts (9900002–9900008), each bound to exactly one, each password a variable from Secret
  `fix-test-clients` defined before `FIX_SESSION_AUTH`. `validate()` SHALL refuse any CompID other than CLIENT1 that
  may trade every account (`*`) or whose password is not Secret-backed. `provision-fix-test-clients.py` SHALL keep the
  passwords only in that Secret and a mode-0600 file outside the checkout, and print none. Growing `--count` SHALL add
  CompIDs and keep every existing password; shrinking SHALL be refused. The default render SHALL
  carry no test client.
- **FR-PRG-40:** Production MAY purge each member's Aeron archive behind its second-latest snapshot (ADR-079 §3/§6, the
  bench's B2, proven on hxb at 250K/s for 45 min). `prepare.py --archive-purge` SHALL render `ArchivePurgeMain` (in the
  member image) as container `archive-purge` beside each member: the member's image, `/data` and `/dev/shm`, its
  `CLUSTER_HOSTNAMES`, `ARCHIVE_PURGE_DISCARD_HISTORY=true` (the purged log is synthetic and not offloaded; ADR-079
  amendment 2026-11-08), `ARCHIVE_PURGE_HOLD_FILE=/data/archive-purge.hold`, its own small JVM, requests = limits, and
  the pod's default container left on the member. `validate()` SHALL refuse an agent on another image, without that
  hold file, not Guaranteed, or that neither discards nor offloads history by name. The default render has none.
  Admission (`admit.py`) SHALL count the agent as one more container slot, on the member's own image, only when it
  is rendered; every other slot count is unchanged.
  - Once the log is purged, a replay from 0 refuses by design (`RECOVERY_ARCHIVE_*`). When the members' pod carries
    `archive-purge`, G1 and G2 SHALL read each member's `/recon/tail-state` instead: a shadow restored with the member's
    own `loadSnapshot` from the oldest snapshot it still retains (its recording present and its position covered by the
    log), then a strict, contiguous replay of the log after it, reported in `/recon/shadow-state`'s terms plus
    `snapshotLogPosition`, `replayedMessages` (the tail's session messages) and `inconclusive`. G1 holds when it is
    `complete` at the member's applied with `digestTrades` = trades; G2 when it equals `/health?digest=1` on bookHash,
    positionHash, openOrders, positions and digestTrades. G1's full-history index and G2's replay from 0 are not run.
  - A tail with no session message after that snapshot (a quiet log: snapshots kept firing after the last order) SHALL
    be INCONCLUSIVE, never ok: printed as such and recorded as `tail=INCONCLUSIVE` in `rig-gate`. It does not refuse the
    bring-up by itself; the gate then rests on G0, G3, G3h and G4, and G4's baseline is already required outside a
    provisioned epoch. A mismatch, an incomplete or missing answer refuses whether the tail is empty or not. (yaakov
    2026-11-08, "Yes, both": discard the purged log; an empty tail does not refuse. Refusing would make any bring-up
    after about two snapshot intervals idle impossible.)
  - The gate SHALL hold every member's purge (the hold file) before it reads a tail, wait out a pass already under way,
    and release it only after the gate passes; a refused gate leaves it held, which keeps log rather than losing it.
    A container listing that cannot be read SHALL refuse, never stand in for "not purged".
  - Test-account rows in the live read model SHALL still be cleared only with a fresh epoch (FR-PRG-38, FR-PRG-39): a
    deletion in place would need its trade sides recorded with `rig.sh unprojected`, or G3 refuses.
  - Consequence: a member whose disk is lost or wiped alone cannot rejoin a purged cluster from empty (its election
    replays the leader's log from 0, which the archive refuses; `ArchivePurgeTest` check 6). Recover it from a copy of a
    peer's disk, or take a fresh epoch, which wipes all three together.
- **FR-PRG-41:** The capture stream's servers SHALL be a NATS cluster of three (ADR-103): a `nats` StatefulSet,
  `podManagementPolicy: Parallel`, routes over a headless Service (`nats-headless`, not-ready addresses published)
  naming every server, `cluster { name: traderx }`, each server's `server_name` and `server_tags` its pod's name
  (substituted at start from the downward API), one server per node (required hostname anti-affinity on
  `nats-pool`), each server's JetStream on its own `data` claim of a rendered Hyperdisk Balanced class (`nats-store`,
  600 MiB/s and 6,000 IOPS provisioned), retained when the StatefulSet is scaled or deleted, `max_pending: 256MB` per
  connection. On the partitioned (snapshot) render trade-processor SHALL carry
  `PROJECTION_FEED_STREAM_PLACEMENT` naming every server's tag (FR-DRS-44), and each server's `max_file_store` SHALL
  hold ceil(P/N) of the P partitions' share of `PROJECTION_FEED_STREAM_MAX_BYTES` plus 25%, under its claim; the
  retention is sized at `READ_MODEL_RATE` = 450,000 orders/s (the 2026-11-10 hour) for the 30-minute outage. `validate()`
  SHALL refuse fewer than three servers, a route set that is not the StatefulSet, a server not named and tagged by its
  pod, servers not spread one per node, a `data` volume that is not a retained per-server claim, a claim off a rendered
  Hyperdisk class, and a partitioned render whose placement does not name every server. `rig.sh pools` SHALL create
  `nats-pool` as three c4d-standard-16 on Hyperdisk and refuse one of another machine type; `rig.sh up` SHALL expect
  three NATS pods and record them all (FR-PRG-28), and the fresh epoch's stream listing SHALL ask every server, since a
  server's `/jsz` reports only the streams it holds. Measured: one server stored 0.4-0.5M msgs/s whatever the stream
  count and three stored 1.00-1.25M with 0 lost (FR-DRS-34); one volume saturated at 298 MB/s (pd-balanced) and at
  684-724 MB/s (Hyperdisk, 1,200 MB/s provisioned) under retention churn at 400K orders/s (RI34 §1b).
- **FR-PRG-42** (2026-11-10, the acceptance run): Each member's archive claim (`data`, class `order-matcher-archive`)
  SHALL hold `MEMBER_ARCHIVE_HOURS` (3) hours at `READ_MODEL_RATE` at 125 B per order, rounded up to 50Gi: 600Gi. The
  pack's 32Gi is the kind/demo figure (~4 minutes at 450K); the acceptance rig ran 600Gi by hand. `validate()` SHALL
  refuse a smaller claim. The production render runs no purge, so an epoch at rate past the claim is ENOSPC on the
  recorder (`statefulset-emptydir.yaml`, GROWTH): grow the claims first (`rig.sh member-disk`; a claim never shrinks)
  or render `--archive-purge` (FR-PRG-40), which bounds the archive near two snapshot intervals
  (`CLUSTER_SNAPSHOT_INTERVAL_MS` 300 s: ~35 GB at 450K) whatever the claim. FR-PRG-39's disk snapshots are of the
  bytes used, not of the claim. The hours are yaakov's decision (ADR-103 amendment 2026-11-10); `test_prepare.AcceptanceShape`.
- **FR-PRG-43** (2026-11-10, the acceptance run): Every load test account (FIX 9900002-9900008, binary
  9900010-9900017) SHALL have a row in `specs/YU15-eod-risk-extract/reference-data/counterparties.csv`
  (`CPTY-TEST-<id>`, `NS-TEST-<id>`, USD). The extract aborts on a holding account with no mapping (FR-RXT09) and the
  image is the CSV's only carrier (`issues/risk-extract-counterparties-configmap-shadows-the-image.md`), so after any
  load the EOD stage failed closed. `prepare.py` SHALL refuse to render while one is unmapped
  (`unmapped_test_accounts`). A test account's positions are extracted like any other's (ADR-103 §5: the EOD extract
  runs against the load's positions); FR-PRG-38's clean-up is unchanged. `test_prepare.AcceptanceShape`.
- **FR-PRG-44** (2026-11-10, acceptance run 3): After a reconnect or a GapFill, the FIX rehearsal load SHALL reconcile
  before it resends. After `--reconcile-settle-ms` (default 10 s), it sends one OrderStatusRequest (H) per unanswered
  order. An order the venue reports (ExecType OrderStatus, 150=I) is counted `foundBooked`, taken as answered and
  set to the status reported (`foundFilled` when that status is Filled and its fill report never came). Only an order
  the venue answers "unknown order" is resent, with PossResend(97), and counted `resent`; resends are never in `sent`.
  The run reports `statusRequests`, and for each outage when the socket closed, what closed it (the Logout text or the
  socket error) and the Logon attempts it took.
  - Why: a blind resend books a second order once the engine's duplicate window (FR-IMRG14, a count shared by every
    session, ~1.7 s at 150K orders/s) has passed its key. In run 3, CLIENT386 was down 46.9 s and blind-resent 1,464
    orders; history held +427 NEW and +8 FILLED over `sent` (`history-overcount-run3.md`, trade-history-tiers).
  - The status comes from the read model, so an order booked within the read model's lag reads as unknown and is
    still resent: the settle time is that margin, not a proof. The acceptance compares the history store's NEW with
    `sent` + `resent`.
  - What a production client should do past the window (ADR-090 exactly-once, the engine window) is yaakov's decision
    and is not this row's.

  `test_fix_load.Drain`.
- **FR-PRG-39:** Only the last epoch's archive SHALL be kept (yaakov 2026-11-08: "Keep only the last one"; all trade
  data is synthetic). Once epoch L's archive is complete (rig-epoch `archived`, its three member-disk snapshots
  `traderx-L-m{0,1,2}` READY and `arc_L_trades` present), the fresh epoch's `up` SHALL delete every older epoch's
  archive before it restarts trade-processor: its `arc_<label>_*` tables in eod-price-db and in ClickHouse, and its
  snapshots `traderx-<label>-m<i>`. `rig.sh prune-archives` SHALL do the same for rig-epoch's label (state archived,
  provisioned, consumed or initialized), and `--dry-run` SHALL read the live state and print what would go, deleting
  nothing.
  - "Older" SHALL be the creation time of the label's `rig-epoch-<label>-history` ConfigMap, which fresh-epoch writes
    and which is kept as the epoch's record. A label found in any of the three places with no such record, or one not
    strictly older than L's, and any `arc_` table not of the form `arc_<label>_<table>`, SHALL be refused before
    anything is deleted (an `archive-members` run for the next epoch makes its snapshots before its record exists).
  - It SHALL refuse, deleting nothing, when any listing cannot be read, when L's archive is not complete, or when a
    table outside the old archives holds a foreign key onto one of them. Snapshots not named `traderx-<label>-m<i>`
    (e.g. a database snapshot) are not epoch archives and SHALL be left alone; live tables have no `arc_` prefix and
    SHALL never be named.
  - Every delete SHALL be `IF EXISTS` against a fresh listing and the listing SHALL be read again after it, refusing
    while anything older remains, so a retry after a partial delete finishes it, and an `up` refused there stays
    `archived` and prunes again on its retry.
- **FR-PRG-38:** `rig/test-data-cleanup.sh` SHALL clear the load test accounts (FIX 9900002–9900008, binary
  9900010–9900017) from the read model. `plan` SHALL change nothing and list, per archived epoch and for the live epoch,
  each account table's rows on those accounts, their directory rows, Secret `fix-test-clients` and the member-disk
  snapshots. `apply LABEL...` SHALL delete those accounts' rows from `arc_<LABEL>_{orderbook,order_view_snapshot,trades,
  positions}` only, in one transaction per label, and fail unless none remain. It SHALL never touch the live epoch's
  tables: G3 compares them with the members' trades, and the members' positions on those accounts go only with a fresh
  epoch (FR-PRG-29), which archives the live rows where `apply` can then clear them. `--accounts` SHALL delete the
  accounts' `accounts`/`accountusers` rows only once no table, live or archived, holds a row of theirs; `--secret`
  deletes Secret `fix-test-clients` (renders without `--fix-test-clients` from then on). Disk snapshots SHALL be listed,
  never deleted here. Running it on production needs yaakov's yes.
  Amended 2026-11-08 (FR-PRG-39): every archive but the last is now deleted whole at the next fresh epoch, so `apply`
  matters only for the one archive kept, and only until the following fresh epoch deletes it. What is left: `apply
  <last label>` when the test rows must go sooner, and `--accounts` / `--secret` when the test loads are retired. The
  live epoch's rows still go only with a fresh epoch.
- **FR-PRG-37:** `prepare.py` SHALL render the read-model database claim `eod-price-db-data` at 50Gi. 10Gi filled on
  2026-11-04 after the 18.5M-order scale-out rehearsal and an epoch archive: the schema-migrate init container's
  temporary mariadbd failed with InnoDB error 28 and `rig.sh up` refused at eod-price-db Ready. The live claim was
  expanded in place (standard-rwo allows expansion); a claim only grows, so the render never goes back down.
  Fixed 2026-11-08: the size had been written over the partitioned render's claim NAME (`READ_MODEL_DB_CLAIM =
  '50Gi'`), so `--read-model snapshot` rendered a claim named `50Gi`, which the API server refuses (RFC 1123). The
  size is `READ_MODEL_DB_SIZE`; the fast claim is `eod-price-db-fast` again (`test_the_read_model_database_claim_is_50gi`).
- **FR-PRG-36:** `loadgen.sh start` SHALL pass `LOADGEN_PAUSE_MS` (100..60000, default 3000) as binary-load.mjs's phase
  pause, the window in which every 10 s phase's orders must be answered before the boundary check. 1000 ms stopped a
  ~185K/s production run after its first phase on 2026-11-02 (~500 orders per session unanswered). A longer pause
  lowers the load's duty cycle (3000 ms: ~70% sending), so the demo pairs it with `HPA_CPU_TARGET=20`.
- **FR-PRG-35:** `rig.sh up` SHALL start, and `rig.sh down` stop and confirm TERMINATED, the KDB-X query VM
  `KDB_VM` (default `traderx-kdb`, FR-DA12) with the client VMs; `up` refuses when it is missing, and `KDB_VM=`
  runs the rig without it.
- **FR-PRG-34:** `rig.sh up` SHALL wait for each support Deployment by its replica counts (generation observed, every
  replica updated and available, none extra), up to 900 s, not by `kubectl rollout status`. Images applied at 0 nodes
  leave rollouts flagged ProgressDeadlineExceeded, which `rollout status` reports as a failure at once (production
  2026-11-01 9:24 pm ET: account-service refused while its pod was pulling its image).
- **FR-PRG-33:** The gateway HPA's CPU target SHALL be `HPA_CPU_TARGET` (10..99, default 60) of the gateway's request.
  Production 2026-11-01: on the binary path three gateways sat at 44% at the engine's ~230K orders/s, so a 60% target
  never scaled; at 30% the HPA went 3 -> 5 gateways in ~65 s with the load running. The demo runs 30.
- **FR-PRG-32:** `LOADGEN=N rig.sh up` (N 0..16, default 0) SHALL resize loadgen-pool to N nodes with the other pools and,
  after the smoke, run `LOADGEN_PODS` (default 4 per node) idle load pods (Deployment `loadgen`: the console image,
  `sleep`, `binary-load.mjs` mounted), Ready before `up` returns. `loadgen.sh start RATE [SECS] [TICKER]` SHALL refuse
  unless the restore gate covers the running members, an idle pod exists and none is already loading; it SHALL split
  RATE over every pod x 16 sessions at the members' mark of TICKER (default MSFT), confirm each pod's process runs and
  that every pod reports orders sent within 25 s, else stop the load and refuse (a load with no binary ingress reports
  sent=0 forever and exits 0).
  `loadgen.sh stop` kills the load, the pods stay idle; `rig.sh down` deletes the Deployment. The demo's gateway
  scale-out never waits on a node or an image pull (2026-11-05 demo).
- **FR-PRG-31:** Every `rig.sh up` SHALL open today's US Eastern business date (`BUSINESS_DATE` overrides) with
  SESSION_START (`POST /session/business-day`, FR-OT37) after the fresh-epoch initialization and before the smoke,
  accept APPLIED or STALE, and refuse on anything else. The engine never reads a clock and a fresh epoch has no date,
  so before this every DAY order on epoch e1101 was REJECTED NO_TRADING_DAY.
 On a fresh epoch whose gate has passed (`rig-epoch` consumed), `rig.sh up` SHALL load the account
  directory and the instrument catalog into the empty engine (`initialize-accounts.py --apply`, then
  `initialize-reference-data.py --apply`) after the gateways start and before the smoke, refuse on either one's
  failure, and only then mark the epoch initialized; a rerun retries until it is (production 2026-10-31: the first
  smoke on epoch e1101 was refused UNKNOWN_ACCOUNT).
- **FR-PRG-29:** A fresh epoch SHALL archive the old one before it wipes anything (ADR-101). `rig.sh archive-members
  LABEL` SHALL snapshot each member disk with the members stopped; `rig.sh fresh-epoch LABEL` SHALL refuse unless all
  three snapshots are READY and of exactly the current disks, record the old gate ConfigMaps in
  `rig-epoch-LABEL-history`, and wait until every member claim and disk is gone. The next `up` SHALL move the epoch
  tables into `arc_LABEL_*` in one RENAME, with trade-processor stopped, and recreate them empty with identical
  definitions, foreign keys included; only the epoch tables the database has, since a persistent volume never runs a
  table's later first-boot SQL. With trade-processor, the members and the gateways still stopped, it SHALL refuse
  unless every capture stream (`TRADERX_FEED`, `TRADERX_FEED_P<p>`) holds 0 messages, then delete them, so
  trade-processor recreates them at seq 1 for the new epoch's cursor 0 (2026-11-07: the old stream, aged out to 0
  messages at first_seq 371203273, made the snapshot store refuse with RETENTION_GAP and G3 fail). Having restarted
  trade-processor, it SHALL wait until `/v2/projection-feed/accounting` answers before G3 reads the read model, and the
  render SHALL give trade-processor a readiness probe on that endpoint, so Ready means G3's read answers (2026-11-07
  11:01 pm ET: `rollout status` returned on a probe-less pod, the accounting read got connection refused 10 s before
  it listened, and G3 refused). history-feed, when rendered, SHALL be stopped with trade-processor and started only
  after that answer, because it binds its lanes to the stream incarnation it starts on (2026-11-07 11:28 pm ET: one
  started before the reset read the deleted stream and G3h timed out). Each Deployment the archive stops SHALL
  restart at the replica count recorded on it (annotation `traderx-rig-held-replicas`) when the archive first stopped
  it, never at its live count, and SHALL be refused when it is at 0 with no count recorded (2026-11-08, e1108: a
  refused `up` had left trade-processor and history-feed at 0, and the retry restored 0). The precondition that the
  members and gateways are stopped covers only those two: trade-processor or history-feed at 0 does not fail it. The archive SHALL record its own completion (rig-epoch
  `archived`, after the capture streams are deleted and before trade-processor restarts), and `up` on an archived epoch
  SHALL only restart trade-processor and history-feed, never re-run the archive or its emptiness check (2026-11-08,
  e1108: the restarted trade-processor had written its seq-0 cursors, and a retried archive refused projection_cursor
  as not empty). `up` SHALL turn wiped into provisioned only
  with no member claim; under provisioned, all three claims are that epoch's own (an `up` refused after the members
  started) and SHALL be read like any other, with G0 refusing unless the members are empty, and 1 or 2 claims SHALL
  refuse (2026-11-07: twice deleted by hand). The gate SHALL accept empty members and no shutdown baseline only while
  `rig-epoch` is provisioned, and mark it consumed on PASS; a missing baseline otherwise SHALL refuse.
  Amended 2026-11-08: the archive is kept until the next fresh epoch's archive is complete, then deleted (FR-PRG-39);
  only the last epoch stays replayable from its snapshots and `arc_<label>_*` tables.
- **FR-PRG-28:** NATS's memory SHALL agree with itself in every profile, and `validate()` SHALL refuse a render where it
  does not. The pod's memory request SHALL equal its limit, and the limit SHALL fit nats-pool's node with headroom
  (ADR-103: c4d-standard-16, 62 GiB, GKE allocatable ~56 GiB, budget 54 GiB; was c3-standard-8, budget 26 GiB).
  `GOMEMLIMIT` SHALL hold the Go heap at or under 85% of the limit. `max_memory_store` SHALL be explicit (unset, NATS
  takes 75% of the host's memory, which the pod cannot hold), and it plus the ingest queue (`max_buffered_size`) SHALL
  fit in half of `GOMEMLIMIT`. Sizing: limit 48Gi (the rest of the limit over the heap is the file store's page cache,
  reclaimed before it stalls the writer), `GOMEMLIMIT=20GiB`, `max_memory_store: 256MB` (every stream and KV bucket is
  file-backed), the 1GB ingest queue unchanged. The "pod" is each server of FR-PRG-41's StatefulSet; "never restarted"
  holds per server, `rig.sh up` records every server's uid and `down` finds a replaced one by it. A run SHALL NOT look clean after NATS restarted: `rig.sh up` refuses a NATS pod that has
  already restarted and records the one it came up with (ConfigMap `rig-nats`), `rig.sh down` reports a restart, a
  replacement or a missing pod as FAILED (evidence `nats-restarts.txt`, exit 3) and still tears down to zero, and
  `rig.sh status` shows it. While the rig is up, `loadgen.sh start` SHALL refuse a NATS that restarted and
  `loadgen.sh results` SHALL report one as FAILED (exit 3) after the pods' counts, so the operator sees it at the load,
  not only at `down`. Production 2026-10-30, 3:03 pm: a 2Gi pod with NATS's memory store unset was OOM-killed 7
  times under the binary arm's ~150K trades/s bridge publish. Each restart took the FIX session store down, every FIX
  Logon was refused, and ~89.7M trade sides were lost, while the rig looked up. The 12Gi sizing measured on the bench
  existed only in the snapshot read-model profile, which production did not run.
- **FR-PRG-27:** The read-model fingerprint (G3's before/after, the shutdown baseline) SHALL be computed inside MariaDB
  as one aggregate per table: its row count and two exact sums of each row's md5 over every compared column, NULL kept
  apart from 'NULL'. It SHALL NOT dump a table (production 2026-10-30 12:45 pm: the sorted dumps of ~3.4M trade and
  3.5M order rows, buffered by the mariadb client inside the 768Mi eod-price-db container, OOM-killed it and refused
  `rig.sh down`). Measured on mariadb:11.4 at that size: container peak +3 MiB, against +470 MiB for the dumps.
- **FR-PRG-26:** `CLIENTS=N` SHALL name exactly traderx-client-1..N (production 2026-10-30: BSD `seq 2 1` counts down,
  so `CLIENTS=1` started and stopped traderx-client-2 as well, and listed client-1 twice).
- **FR-PRG-25:** G1 and G2 SHALL mint the admin token for each member's replay request just before that request
  (production bring-up 2026-10-30: one token minted up front, with a 600 s TTL, expired during the first two members'
  ~8 minute strict replays, so member 2 answered 401 and G2 refused with "no /recon/shadow-state"). A token that cannot
  be minted SHALL refuse the gate.
- **FR-PRG-24:** A gateway shrink SHALL delete only gateway-pool nodes a successful listing shows empty (codeX review
  2026-10-30, §8: a failed pod listing read as "no gateway here", and an occupied node was cordoned and deleted). It
  SHALL first read every node's gateways; a node list or any node's pod list that cannot be read SHALL refuse the
  whole deletion phase, and no node is cordoned or deleted.
- **FR-PRG-23:** G-pre and G4 SHALL fail closed on evidence they could not read (codeX review 2026-10-30, §2 and
  §3: each turned a failed read into a pass).
  - G-pre: a failed `kubectl logs` of a reader pod SHALL refuse, before that pod is deleted (it is kept for a look).
    The reader's output SHALL begin with its `mark files in` line and end with `errlog complete`; an empty or truncated
    log SHALL refuse. A found corruption pattern still refuses first.
  - G4: only a `NotFound` for ConfigMap `rig-shutdown-baseline` SHALL mean a first bring-up. Any other failed read
    SHALL refuse. A baseline that exists SHALL carry a well-formed members line (applied, engineApplied, trades, two
    hashes, snapshots), or G4 refuses.
- **FR-PRG-22:** The restore gate's read of the projection mode SHALL fail closed.
  - trade-processor's `TRADE_PROJECTION_MODE` SHALL be read from its Deployment. Unset or `snapshot` is the snapshot
    G3; `rows` was retired (trade-history-tiers FR-THT-01, 2026-11-06, with the row G3 and `rig.sh snapshot-base`). A
    Deployment that cannot be read, or any other value, SHALL refuse G3. An absent `rig-snapshot-base` is base 0.
  - The accounting fetch SHALL fail on an HTTP error or an empty body (`curl -f`). A failed fetch SHALL fail
    `read_model`, never hash as `accounting=md5("")`, and refuse G3.
  - (Retired 2026-11-06 with rows mode: `rig.sh snapshot-base` and a required `rig-snapshot-base`. A recorded base
    that is not a number still refuses G3.)
  - The default render (no `--read-model snapshot`) SHALL be the snapshot read model at one partition, as production
    epoch e1107 ran it (2026-11-07): `TRADE_PROJECTION_MODE=snapshot`, `ORDER_FEED_PERSIST=false`,
    `ORDER_VIEW_SNAPSHOT_MS=5000`. `validate` SHALL refuse any render without `ORDER_VIEW_SNAPSHOT_MS` > 0, because the
    snapshot G3 refuses accounting with no `orders` ("no order view snapshot"), as `up` did at 7:13 pm ET that night.
  - The before/after equality of `read_model` is unchanged; the accounting line takes part in it in snapshot mode.
- **FR-PRG-21:** When trade-processor runs `TRADE_PROJECTION_MODE=snapshot`, G3 SHALL check the committed accounting
  (`/v2/projection-feed/accounting`, FR-DRS-29/30). SQL then holds snapshots, not a row per trade side, so the row
  checks do not apply. It SHALL:
  - wait (at most 600 s) until every trade and order partition's committed cursor has reached its subject's last
    stream message;
  - refuse on any duplicate, on a highest `tradeSeq` above the members' `trades`, on a side count or wrapped sum of
    seq or seq² different from the closed form over (base, trades] minus the `rig.sh unprojected` ranges, on a
    missing order-view snapshot, and on committed open orders (NEW, PARTIALLY_FILLED) different from the members'
    restored `openOrders`;
  - take `base` from ConfigMap `rig-snapshot-base` (the members' `trades` when the read model switched from rows,
    after a passing row G3). With none recorded, base is 0.
  - refuse a managed projection scope, as the row G3 does.
  The read-model fingerprint SHALL then add the accounting without its cursors, so it must not move while the members
  start.
- **FR-PRG-20:** `prepare.py --read-model snapshot [--partitions P]` SHALL render the production read model with
  persistence on at high rates ([ADR-097](../../system/adr-097-production-read-model-at-volume.md)):
  - the members' `BRIDGE_PARTITIONS=P`;
  - trade-processor on `PROJECTION_FEED_MODE=jetstream` with P partitions (default 8), one stream per partition
    (`PROJECTION_FEED_STREAM_LAYOUT=partition`, FR-DRS-32), `TRADE_PROJECTION_MODE=snapshot`,
    `ORDER_FEED_PERSIST=false` and `ORDER_VIEW_SNAPSHOT_MS=5000`;
  - NATS at the base sizing every profile carries (FR-PRG-28; 2Gi was OOM-killed under load);
  - the stream limit sized as rate (125K orders/s, the measured hold, ADR-097) x stored bytes per order x the outage
    window, with NATS's `max_file_store` and its claim above it;
  - trade-processor on `readmodel-pool` and the database on `db-pool`, each tainted and guaranteed;
  - the database's claim moved to class `eod-price-db-fast` (Hyperdisk Balanced, provisioned IOPS and throughput),
    restored from a VolumeSnapshot of the old claim, never created empty.
  `validate()` SHALL refuse:
  - trade-processor's partitions different from the members' (a partition nobody reads);
  - the single-stream layout (it holds ~50K orders/s, not the profile's 125K);
  - a memory-only view without its snapshot (FR-DRS-30);
  - the snapshot projection off the stream;
  - a stream limit not under `max_file_store`, or `max_file_store` not under the NATS claim;
  - a moved database claim with no snapshot source.
  The default render keeps the row read model.
- **FR-PRG-19:** A read-model query that fails SHALL fail `read_model`, never be hashed as an empty table. `up` SHALL
  wait until the database answers before taking G3's before-reading, and refuse (G3) if either reading fails; the
  shutdown baseline SHALL refuse the same way. The hashes stay over the query's exact output, so earlier recordings
  still compare. (Production 2026-10-29 8:42 pm: the database was not answering yet, the before-reading was md5("")
  for all three tables, and G3 refused a read model that had not moved.)
- **FR-PRG-18:** G1 SHALL read the members' `RECON_FULL_HISTORY_MAX` (default 200000) from the running StatefulSet. When the
  restored `trades` exceed it, the full-history index cannot answer (it refuses rather than truncate), so G1 SHALL defer
  to G2 and record that in the gate evidence; G2's whole-log replay into a shadow proves the same property by hashes,
  with no cap. A deferred G1 SHALL make G2 mandatory, even where `GATE_ALLOW_NO_SHADOW` would otherwise skip it. A
  failed replay request SHALL be a named G1 refusal, not a bare exit. (Production 2026-10-29 8:15 pm: 3,392,664 trades
  against a 200,000 cap; HTTP 500 and `up` exited 8 without a reason.)
- **FR-PRG-17:** A combined run SHALL drive binary and FIX at once:
  - `CLIENTS=N` (1..3) client VMs `traderx-client-1..N`, each created by `rig.sh pools`, started by `up`, stopped by
    `down`, and each its own /32 in the internal LB's source ranges;
  - both load clients SHALL take `--ramp F1,F2,… --step-secs S`, running at each fraction of `--rate` for S seconds
    before the full rate, and log the current fraction with every progress line, so a run records its knee;
  - the two loads trade disjoint account sets (FR-PRG-16) and SHOULD trade different tickers, so neither crosses the
    other's orders and each one's accounting stays its own.
- **FR-PRG-16:** The throughput run's binary load (`rig/binary-load.mjs`, run by `rig/binary-load.sh` as an Indexed
  Job on `loadgen-pool`, never restarted) SHALL:
  - trade only its own test accounts 9900010–9900017, disjoint from the FIX load's 9900002–9900008, in two sets
    (even, odd): at any moment one set buys and the other sells, so no order can meet its own account (no self-trade
    cancel);
  - swap the sets' roles every wall-clock-aligned phase (`--phase-secs`, the same on every pod), so no account
    accumulates a position beyond one phase's buys; production's global risk policy is left as it is. Each side SHALL
    pick its accounts with its own counter, so every account trades both sides across phases (production 2026-10-29
    9:09 pm: `seq % n`, with sides alternating on `seq`, gave each side half the accounts; those only ever bought or
    only ever sold and hit POSITION_LIMIT within minutes, 5.4M rejects);
  - resolve its ticker per run through the gateway's lookup-only `/resolve`, and refuse before any order if it does not
    resolve;
  - send limit orders of 1 at one operator-given price, unflagged (never sequenced);
  - track every clOrdId until its first ack and never resend (a resend past the 256Ki-key window can book twice), in
    memory bounded by the in-flight window: an answer for a seq no longer open is a duplicate, so no per-answer set is
    kept (production 2026-10-29 11:04 pm: that set outgrew node's heap after 18 minutes);
  - in each phase's closing pause (`--pause-ms`), stop sending, wait for its answers and send the difference between
    its accepted buys and sells on the other side until they are equal; a session still unbalanced at the boundary
    SHALL stop the pod (exit 1) before the roles swap. A pod that exits 0 leaves nothing of its own resting;
  - exit 1 on any order unanswered, answered twice, ambiguous or unbalanced, and log one `RESULT` line.
  `binary-load.sh --apply` SHALL refuse unless the restore gate covers the running members. `rig.sh pools` SHALL
  create `loadgen-pool` (c4d-standard-8, tainted `traderx-loadgen`) at 0 nodes, and `rig.sh loadgen N` (0..16) SHALL
  size it. `rig.sh down` SHALL delete the Job before the gateways go, return `loadgen-pool` to 0, and count it in the
  zero-instance check. The pods run the candidate's `web-front-end-console` image (node 20), so no new image is built.
- **FR-PRG-15:** For a full-throughput hour, `prepare.py --throughput-members` SHALL render the bench's 450K member
  settings (`CLUSTER_EGRESS_COALESCE`, `CLUSTER_SNAPSHOT_PACKED`, `CLUSTER_SNAPSHOT_RING`, all `true`), keeping the
  heap and the pinned book cap. `rig.sh member-disk SIZE` SHALL grow the three member claims (allowing expansion on
  `order-matcher-archive` first), refuse a shrink or a malformed size before changing anything, and wait for each
  bound volume's size. The archive grows ~125 B per order and production runs no purge.
- **FR-PRG-14:** A throughput run MAY render the members without their NATS bridges (`prepare.py --no-bridges`), as
  the bench's 400K hours ran. The run's trade sides then never reach SQL. `rig.sh unprojected A-B` SHALL record that
  range (the members' `trades` before and after), appending, never removing. G3 SHALL then require SQL to hold exactly
  1..trades minus every recorded range, each number once, with none inside a range.
  With the members rendered without bridges, the smoke (FR-PRG-04) SHALL end at the FIX answers (New, Canceled) and
  say so, instead of waiting for a read-model row that cannot come.
- **FR-PRG-13:** A throughput run MAY open the gateways' binary entry (`prepare.py --binary-port`). It SHALL be a
  ClusterIP Service only, because binary frames carry the account and have no logon. While the population declares
  the members from an older commit, every gateway SHALL run `BINARY_SEQUENCED=refuse`: a NEW with the sequenced flag
  (ADR-090) is answered with a protocol error and never submitted. `validate()` SHALL refuse either missing.
- **FR-PRG-12:** The rehearsal load (`rig/fix-rehearsal-load.mjs`) SHALL run one session per load CompID on its own
  account, track every ClOrdID until its first ExecutionReport, and through a gateway drain:
  - reconnect through the internal LB with its next MsgSeqNum, never a reset after the first Logon;
  - answer the acceptor's ResendRequest with a GapFill before resending anything;
  - reconcile before resending (FR-PRG-44): resend with PossResend(97) only an unanswered order the venue answers
    "unknown order" to an OrderStatusRequest.

  It SHALL send no TimeInForce (a Day order needs an open trading day, FR-OT08), pace each session at its rate,
  cancel every order still resting at the end, and end non-zero when any order is unanswered, answered as New twice,
  or left resting. It SHALL print no password. Its per-session
  counts are compared with the read model's rows per test account at the effect end.

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| client logged out with an answer 4 s away | 07 | `FixSessionPersistenceIT.aDrainingGateway…` | resumes on the other replica, answer resent as number 3, PossDup |
| same, acceptor stopped at the Logout (old hooks) | 07 | same test, negative control | nothing stored; B logs on at 3; test fails |
| rendered candidate | 05, 06, 07 | `test_prepare` rig tests | pools and tolerations, gateway shape, member ZGC heap and pinned cap; 3 fail on the previous renderer |
| broken candidates | 05, 06, 07 | same | gateway on default-pool, a replica count, no anti-affinity, 250m CPU, 30 s grace, no drain delay, the G1 shape, the bench's 6 GB heap, no `MAX_ACTIVE_BOOKS`: each refused |
| the real smoke script against a fake acceptor | 04 | `test_rig.Up.test_the_smoke_script_against_a_fake_acceptor` | Logon, New, Cancel, Logout ends OK with the Logout answer; a refused Logon ends 1; the password never printed. Fails on the script that ended 1 on the Logout answer (production 2026-10-29, after `39=4`) |
| smoke script failing, or a malformed SendingTime | 04 | `test_rig.Up.test_a_failed_smoke_script…`, `…test_the_smoke_sends_a_fix_sending_time` | REFUSED smoke with its output kept; 52 is `YYYYMMDD-HH:MM:SS.sss` (production refused `20261029-174539.540Z`) |
| up order | 01, 03 | `test_rig.Up.test_gateways_start_only_after…` | gateways 0 → pools → members → `rig-gate` → gateways → ILB → smoke |
| cold restore into an idle cluster | 03 | `test_rig.Up.test_a_cold_restore_with_no_engine_event_since_passes_g0_and_g4` | engineApplied -1 passes G0 and G4; fails on the previous G0 with production's exact refusal |
| read model trade sides missing, duplicated, gapped or extra | 03 | `test_rig.Up.test_the_read_model_must_hold_exactly_the_members_trade_sides` | REFUSED G3; on production the query reads 18/18/18 against trades 18 |
| managed projection scope | 03 | `test_rig.Up.test_a_managed_projection_scope_is_refused_not_guessed` | REFUSED G3 |
| an engine behind the log, or one member restored idle | 03 | `test_rig.Up.test_an_engine_behind_the_log_is_refused` | REFUSED G0 |
| profile rendered | 20 | `test_prepare.SnapshotReadModel` | partitions on both sides, snapshot env, pools, retention = the sizing, the DB claim restored from a snapshot of the old one |
| profile parts that do not fit | 20 | same | partitions 4 vs 6, view snapshot 0, core feed, a 1G store, a DB claim without its source: each refused |
| snapshot read model at restore | 21 | `test_rig.Up.test_the_snapshot_read_model_is_checked_by_its_accounting` | G3 ok over 1..5, 2 open orders; the row check never runs |
| a side lost, duplicated, doubled across partitions, beyond the members, or open orders that differ | 21 | `test_rig.Up.test_a_snapshot_read_model_that_lost_or_doubled_a_side_is_refused` | REFUSED G3 each; with the sums check removed (counts only) the cross-partition double passes and the test fails |
| projection mode unreadable, or not rows/snapshot | 22 | `test_rig.Up.test_an_unreadable_projection_mode…`, `…unknown_projection_mode…` | REFUSED G3, no gateway; unset and `rows` still run the row G3; controls: with the guard removed (`|| true`, any value = rows) both fail |
| accounting fetch fails | 22 | `test_rig.Up.test_an_accounting_that_cannot_be_read…`, `test_rig.ReadModel.test_a_failed_accounting_fetch…` | REFUSED G3; `read_model` fails, never hashes md5(""); control: `curl -s` with no failure check fails the test |
| snapshot G3 with no recorded base | 22 | `test_rig.Up.test_a_snapshot_g3_without_a_recorded_base_is_refused` | REFUSED G3; control: a missing base read as 0 fails the test |
| `rig.sh snapshot-base` | 22 | `test_rig.SnapshotBase.*` | records `trades` once with `create`; refuses a second run, snapshot mode, an unreadable mode, running gateways, ungated or disagreeing members, and a row check that fails; control: without the existing-value check, a second run writes |
| a rows-era base, an unprojected window | 21 | `test_rig.Up.test_the_snapshot_check_starts_at_the_recorded_rows_era_base` | 3..5 passes with base 2, is refused without it; 4-4 unprojected passes |
| each gate check failing | 03 | `test_rig.Up.*refused*` | REFUSED Gn; no gateway scaled up; no `rig-gate` |
| a past partial restore in a member's error log | 03 | `test_rig.Up.test_a_member_that_once_restored_partially…` | REFUSED G-pre for each of the three messages; no member started |
| error log unreadable, or members running | 03 | `test_rig.Up.test_an_unreadable…`, `…not_read_while_members_run` | refused |
| G-pre pods | 03 | `test_rig.Up.test_the_error_logs_are_read_read_only…`, `…test_the_pod_runs_the_reader_on_the_claim` | claim and mount read-only, the shipped reader on `/data/cluster`, all three read before the members scale to 3 |
| a refusal in the service mark file, as Aeron writes it | 03 | `test_rig.Up.test_the_reader_finds_a_refusal_aeron_logged_in_the_service_mark_file` (`rig/ErrlogProbe.java`, Aeron 1.51.0) | each of the three messages found in `cluster-mark-service-0.dat` and REFUSED; clean control passes; no service file = exit 3; reading the consensus file alone fails the test |
| Pending members at bring-up | 01 | `test_rig.Up.test_members_are_held_at_zero…` | StatefulSet to 0 before any pool resize |
| re-run on a gated rig | 01 | `test_rig.Up.test_a_rerun_on_a_gated_rig…` | nothing scaled to 0, no G-pre, no gate; gateways to N |
| build without check A | 03 | `test_rig.Up.test_a_build_without_check_a…` | refused on `traderx`, opt-out ignored there; skipped only in a side namespace |
| shrink resume report | 06 | `test_rig.Resume` on production's 2 → 1 drain logs | only logouts after `GATEWAY-DRAIN begin`: 4 sessions, worst 595 ms; without that filter the real log reports 22 sessions, worst 805 s |
| gateways sharing a node | 06 | `test_rig.Up.test_gateways_sharing_a_node…` | refused |
| down order | 02 | `test_rig.Down.test_gateways_then_barrier…` | gateways 0 → LB → barrier (ClusterTool with -Xmx128m) → baseline → members → pools → VM |
| the instance check itself | 02 | `test_rig.InstanceCheck` (the real `no_rig_instances`, gcloud stubbed) | a failing query refuses; an instance in any pool is found; on production 2026-10-29 the old alternation filter crashed gcloud and passed with default-pool RUNNING |
| a member exits 137 | 02 | `test_rig.Down.test_a_member_that_did_not_exit_143…` | exit 3, pools still at 0 |
| dry run | 01 | `test_rig.Plan` | contacts nothing; bad arguments refused |
| gate removed from `up` (mutation) | 01, 03 | `test_rig.Up` | 14 of 18 fail |
| G-pre removed from `up` (mutation) | 03 | `test_rig.Up` | 10 of 18 fail |
| malformed declarations | 10 | `test_population.Declaration` | bad schema, unknown field, short SHA, exception on the default commit, duplicate image: each refused |
| neither commit, each half of a mix, missing image | 10 | `test_population.Images`, `.Admit`, `.Measure` (pull with docker stubbed) | refused in `population.py`, `admit.check_population` and `measure-admission.pull` |
| wrong placement | 10 | `test_population.Measure`, `test_prepare.Population` | members on the tip image, member image on the gateway: refused by `measure-admission.population` and `prepare.validate` |
| guard removed (mutation) | 10 | same | each of the five guards removed: 1–6 tests fail |
| tip ingress on the members' codec | 10 | `rig/codec-compat.sh` | 42 encodings over 19 command types, 518 checks, 0 failures; both controls fire |
| an order never answered | 12 | `rig/test_fix_load.Drain.test_an_unanswered_order_does_not_leave_the_others_resting` | the resting sweep still runs halfway through the wait; the run still fails; the previous condition left 18 resting |
| pacing | 12 | `rig/test_fix_load.Drain.test_the_rate_is_per_session_per_second` | 5/s for 2 s sends 8–11; the previous script sent 188 (production 2026-10-29: `--rate 5` sent 100/s per session) |
| a drain mid-load | 12 | `rig/test_fix_load.Drain` (scripted acceptor) | 30 orders, 10 in flight at the Logout: resumed without reset, GapFill before the first resend, every order answered once, 1 reconnect; resending straight after the Logon fails it (the GapFill lands after 13 messages) |
| throughput member settings | 15 | `test_prepare.Population.test_throughput_members_get_the_bench_settings` | the three switches on; heap and book cap unchanged; off by default |
| member disks | 15 | `test_rig.MemberDisk` | StorageClass patched before any claim (skipped when already expandable); a shrink, a bare number, a smaller size: refused with no patch |
| an unprojected window | 14 | `test_rig.Up.test_a_recorded_unprojected_window_is_excluded_exactly`, `…test_recording_a_window_appends…`; the query checked on mariadb:11.4 | sides 3–4 excluded passes; window not excluded, a row inside it, one missing: each refused; malformed ranges refused |
| smoke without bridges | 14 | `test_rig.Up.test_without_bridges_the_smoke_ends_at_the_fix_answers` | ends at New + Canceled and says so; with bridges the read-model row is still required |
| members without bridges | 14 | `test_prepare.Population.test_a_throughput_run_can_render_the_members_without_bridges` | default keeps `TRADE_BRIDGE_NATS_URL`; `--no-bridges` removes it |
| sequenced binary in front of older members | 13 | `BinarySequencedFrameTest.refusedSequencing…`, `test_prepare.Population.test_older_members_get_a_gateway_that_refuses_sequenced_binary` | flagged NEW answered protocol-error, nothing submitted, unflagged unchanged; the render carries the switch and refuses without it; the test fails on the previous acceptor |
| binary entry | 13 | `test_prepare.Population.test_the_binary_entry_is_in_cluster_only` | env + port + ClusterIP Service; a LoadBalancer refused |
| load clients scaled | 11 | `test_prepare.FixTestClients.test_a_throughput_run_scales…`, `…test_the_provisioner_grows_without_rotating` | 200 CompIDs over the same 7 accounts, CLIENT2–8 unchanged; 7 → 20 keeps the first seven passwords; 5 refused |
| load clients rendered | 11 | `test_prepare.FixTestClients` | CLIENT2–8 each on its account, Secret-backed and ordered before the auth list; none in the default render |
| unbound or literal load client | 11 | same | `*` account, literal password, password after the list, non-Secret password: each refused; dropping the check fails the test |
| older archives deleted, the newest kept | 39 | `test_rig.Retention.test_every_older_epoch_goes_and_the_newest_stays`, `test_archive_read_model_mariadb.test_prune_drops_only_the_older_epochs_archive` | e1101 and e1107's SQL and ClickHouse arc tables and snapshots deleted, e1108's and a non-member snapshot kept, no live table named; on mariadb:11.4 the live definitions are unchanged; a rerun deletes nothing |
| a label whose age is not on record | 39 | `test_rig.Retention.test_a_label_whose_age_is_not_on_record_is_refused`, `…nothing_older_goes_until…`, `…a_live_tables_key…` | a snapshot set with no history record, a newer or equal record, a malformed arc name, any unreadable listing, an incomplete newest archive, a live key onto an archive (also on mariadb:11.4): each REFUSED with nothing deleted |
| a retry after a partial delete | 39 | `test_rig.Retention.test_a_retry_after_a_partial_delete_finishes_it` | stopped at the snapshot, ClickHouse or SQL delete: the retry deletes only what is left and ends with only e1108's archive |
| the purge agent rendered | 40 | `test_prepare.ArchivePurge` | beside each member on its image, `/data` and `/dev/shm`, discard and hold file named, Guaranteed, default container the member; none by default; each of five broken agents refused |
| snapshot+tail reference on real members | 40 | `ThreeMemberClusterTest.aPurgedLogIsReproducedFromTheOldestRetainedSnapshotPlusItsTail` | after a real purge and a restart from disk: the replay from 0 refuses; each member's tail-state equals its live digest, replays exactly the 2,400 orders after the oldest retained snapshot and moved the state past it; two idle snapshots later the tail is empty and inconclusive. With the snapshot restore removed the test fails (applied 2,400 vs 6,404) |
| G1/G2 on a purged log | 40 | `test_rig.Purge` | purge held before any tail read and released after `rig-gate` (tail=PASS); an empty tail INCONCLUSIVE, never ok, and refused without a G4 baseline; a differing hash, applied, trades or positions, an incomplete or error answer, a failed hold, an unreadable container list: each REFUSED with the purge still held; without the purge the replay from 0 still runs. Treating an empty tail as ok fails 1, dropping the hold fails 2 |
| purge and gate on kind | 40 | `rig/kind-purge.sh` (2026-11-08) | members rendered by `prepare.archive_purge`: all three purge (2 passes, 11 deletes each); restarted from disk, `g12_tail` PASS over a 65,895-order tail with the purge held; idle past two snapshots INCONCLUSIVE. No trades on kind (the proof client's orders rest) |
| fresh epoch `up` and `prune-archives` | 39 | `test_rig.Retention.test_a_fresh_epoch_up_prunes…`, `…test_prune_archives_on_its_own` | archived → prune → trade-processor restart → provisioned; a refusal leaves archived and the retry prunes first; standalone needs a completed epoch; `--dry-run` deletes nothing |
| three NATS servers rendered | 41 | `test_prepare.NatsCluster` | StatefulSet of 3 over `nats-headless`, routes = the set, name and tag from the pod, one per node, retained Hyperdisk claim per server, `max_pending` 256MB, on `nats-pool`; no placement on the one-stream render |
| the partitioned capture placed and each store sized to its share | 41 | `test_prepare.NatsCluster.test_the_partitioned_capture_is_placed…` | `nats-0,nats-1,nats-2`; retention = 450K × 30 min; `max_file_store` = ceil(8/3)/8 of it + 25%, above three partitions' share, below the whole feed, under the claim |
| broken NATS renders | 41 | `test_prepare.NatsCluster.test_drift_is_refused` | 1 replica, a route to a server that is not there, a fixed `server_name`, no anti-affinity, a claim deleted on scale, a pd-balanced class, a placement missing a server: each refused |
| members at the ADR-103 shape | 05 | `test_prepare.test_members_run_generational_zgc_with_the_book_cap_pinned` | 15 CPU / 40Gi requests = limits, flags and the pinned cap unchanged |
| three NATS pods recorded and checked | 28, 41 | `test_rig.Nats.test_three_servers_are_three_pods_each_recorded`, `…test_pools_creates_three_nats_nodes_on_hyperdisk` | `up` records `u1,u2,u3`; two pods REFUSED ("want 3"); a replaced server FAILED at `down`; three intact pass; `pools` creates nats-pool as c4d-standard-16 on Hyperdisk |
| a partition placed on its server's tag | DRS-44 | `JetStreamFeedTest#aPartitionIsPlacedOnItsServersTag` | p mod n; unset or blank places nothing; a stream is placed only when it carries its tag |
| the three-server capture on the render at 400K and ~500K | 41 | GKE 2026-11-10, `retest-520k-20261110` runs A-C | streams 3/3/2 by tag on the servers; 400K: G3 and G3h ok, 41% disk busy per server; ~495-510K for 20 min: stream = messages, records = acked, G3h ok 1..830,602,622 each once, 0 rejected |
| 520K at 40Gi with 200/100 ms | 05 | GKE 2026-11-10, runs B-C | zero election transitions in any member's log over 20 min; NFR-GMR-01 stands |
| failover at rate | 05, 41 | GKE 2026-11-10, run D | new leader in ~1.1 s, 0 rejected, members exact, killed member back in 96 s; latency p50/p99 unchanged; the promoted leaders' lanes dropped 22,948,341 sides from the stream (RI37) — not accepted until fixed |
| the acceptance hour | 05, 41 | rig proof, pending | ADR-103 rollout 5, after RI35, RI36, RI37 |
| live bring-up, down, scale and autoscale | 01–09 | rig proof, pending | see tasks |
