# YU12 Aeron Cluster — Consolidated Recap (full arc: 2026-07-17 → 2026-07-20)

**Purpose:** the single honest record of the YU12 workstream — what it is, what was built and proven,
the measured numbers, and what's genuinely open. Consolidates the whole arc (scaffold → HA → GKE →
throughput → bridge) so any next session starts from verified ground, not re-derivation.
**Status:** master recap, created 2026-07-20. Untracked working note — do not commit.
**Lineage:** worktree `traderX-YU12-aeron-cluster`, branch `YU12-aeron-cluster`, parented on YU11 at
`84d0d01`. Solo fable lane (codeX out). Nothing pushed (repo convention).
**Companion docs:** `RECAP-2026-07-20-yu12-bridge-bench-session.md` (today's detailed session log +
measurement traps); the five `HANDOFF-issue-yu12-*.md` files (open issues); the committed
`PROOF-yu12-*.md` evidence artifacts.

---

## 1. What YU12 is

BLP high availability recast as **Raft consensus**. A three-member **Aeron Cluster** replicates one
committed input log into a deterministic `ClusteredService` that hosts the inherited matching + risk
core. Election, log replication, commit, snapshotting, and empty-disk member catch-up are consensus
primitives — replacing YU11's hand-built Lease election, NATS-KV witness, custom MDC replication, and
snapshot-bundle recovery (what YU11 did in 5 hand-coded slices, YU12 gets as a Cluster primitive).

**Vehicle decision (settled with yaakov):** Aeron Cluster beats generic Raft libraries (no zero-alloc
hosting discipline) and broker-log sequencers (killed by measurement: NATS 10.5k/s vs Aeron 520k/s).

## 2. Build arc

| Date | Milestone | Commit |
|---|---|---|
| 07-17 | Scaffold — house-style spec pack (prefix AC, ADR-044..047), pipeline wiring | `756f52a` |
| 07-17 | Phase-1 spike — `MatchingEngineClusteredService` hosts unchanged engine; no-ID-reuse proven across snapshot+tail AND snapshot+ZERO-tail (YU11's defect case) — nextOrderRef bug class structurally gone | `62a06e8` |
| 07-17 | WS2 snapshot completeness — risk state in-cluster, idempotency + terminal-FIFO groups, fail-closed load matrix | `5979929` |
| 07-17 | WS3a HA in-process — 3-member election, leader kill, empty-disk rejoin, refs 1..7 no reuse | `4e788c1` |
| 07-17 | WS5 — SymbolRegister (SBE tmpl 7) closes F2; FeedAdapterMain | `236aceb` |
| 07-18 | kind egress ROOT-CAUSED — `/dev/shm` term-length bug (not network); fixed | `8b056e2` |
| 07-18 | kind HA PROVEN — cross-pod client, 0 ID reuse across 2 failovers + empty-disk rejoin | `01fa6f3` |
| 07-18 | WS4 gateway — one-owner-thread REST+FIX, live failover transparency on kind | `d1110eb` |
| 07-18 | GKE deploy — 3-member cluster + gateway to real GKE; off-plane failover across ~8 kills | — |
| 07-19 | **Throughput bar smashed** — pipelined gateway | `05ccec4` |
| 07-19 | Sub-second failover campaign complete | `6aaf28a` |
| 07-19 | Periodic snapshots live (60s); GCS backup+restore proven; observability/Grafana | `3d57869`,`bfaf179`,`9db9805` |
| 07-20 | Flood stability (emptyDir 20Gi + liveness) ; health-server hardening (134k survivable) | `198145b`,`c9ffb5e` |
| 07-20 | Trade→DB bridge + gateway scale-out | `89babcc` |
| 07-20 | (this session) `/trades` gateway route for UI order path | `35fd903` |

## 3. Proven capabilities (with evidence)

- **HA / failover** — off-plane (Raft-internal, no k8s in the decision) proven across ~20+ leader
  kills. System-facing **653–716ms** across 5 kills (node-clock precise); under full flood **778ms**,
  0 failed, 0 reuse, killed member rejoined mid-flood, all members deterministically equal after.
  Best single client-observed 192ms. Shipping config pinned 400ms heartbeat / 200ms election (rung A
  — the only flood-stable tight config; tighter detectors false-fire at snapshot barriers).
- **Empty-disk rejoin** — a wiped member re-converges to identical state via quorum catch-up (no
  bundle); Raft quorum IS the durability. emptyDir chosen for GKE members.
- **Throughput** — pipelined gateway (batch offered into the log, acks counted FIFO, no per-order
  committed-ack wait): **29–36k committed submits/s, 45–136k booked trades/s**, 3 stable runs, 0
  failures, 0 reuse — vs the 25,149 NFR-AC02 baseline and ~10k NATS-era. The deep fix under it: an
  output-ring self-deadlock (service thread is producer AND consumer; a fill cascade > ring parked
  apply forever, and because the trigger lives in the committed log it wedged REPLAY on all members) —
  fixed via a YU12 `OutputPublisher` override (inline drain-and-retry); a poisoned 1.4M-event log
  replayed clean.
- **134k burst survivable** — root cause of the flood crash-cascade was the LIVENESS PROBE, not the
  cluster: `httpGet /health` ran app logic on a starved in-JVM thread → timed out under flood → k8s
  SIGKILLed alive members (exit 137). Fixes: `tcpSocket :8080` liveness (kernel answers the SYN
  regardless of JVM scheduling), dedicated MAX_PRIORITY health pool, off-path 250ms `/ready` sampler.
  PROVEN: sustained 5×30s conc=32 batch=1000 (~134,755 booked burst) → **0 restarts**, stable leader.
- **Snapshots + recovery** — leader-toggled periodic snapshots (60s interval measured optimal: 30s
  cost ~8s cluster-wide apply-stall per snapshot; 46k/s w/o vs 31k/s w/ 30s snaps). Member recovery
  66s (snapshot+tail) vs growing full replay.
- **GCS disaster recovery** — CronJob backs up /data (208MB→8MB sparse tar) every 5min to GCS; drill
  proved whole-cluster wipe → member-0 restored to 1.67M applied → all 3 reconverged, 0 reuse. Closes
  the emptyDir all-nodes-die gap.
- **Observability** — member + gateway `/metrics` scraped by Prometheus (1s); Grafana "YU12 Aeron
  Cluster — live" at grafana.yaakovseif.dev (failover state-timeline, throughput, lag, snapshots).
- **Trade → DB → UI bridge** — leader-side `TradeNatsPublisher` publishes each booked trade to NATS
  `/trades` → trade-processor persists to MariaDB + republishes `/accounts/{id}/trades` + `/positions`
  (UI websocket feeds). Proven end-to-end this session (see §5).

## 4. The measured numbers (consolidated)

| Metric | Value | When / caveat |
|---|---|---|
| NFR-AC02 baseline (the bar) | 25,149 booked/s | target to beat |
| Committed submits/s | 29–36k | 07-19 pipelined gateway, sustained |
| Booked trades/s (cascade) | 45–136k | 07-19; booked > submit is real (one tick re-fills the book) |
| **Burst peak** | **134,755 booked/s** | 07-19/20, `booked=applied`, `sub/appl 0.99`, 0 restarts — a BURST |
| System-facing failover | 653–716ms | node-clock, 5 kills |
| Under-flood failover | 778ms | 0 failed, 0 reuse |
| Sustained (this session, scale-out) | 64k submit / 45k booked | 07-20, 3 gateways, 0 restarts |
| Member recovery | 66s | snapshot+tail |

## 5. This session (2026-07-20) — bridge proof, UI gap, bench re-measurement

- **Bridge PROVEN on the live GKE cluster.** Leader NATS **publish** count (`in_msgs`) tracks fills
  exactly (7 fills → 7 publishes); `/trades` has a live subscriber; per-account republish increments
  only after the DB write. Fills → NATS → DB → UI feeds confirmed working.
- **Matching model finally understood** — price-triggered (auto-fill against last market price when
  `isInTheMoney`, not a crossing book); `FILL_FULL_THRESHOLD=100` (orders ≥100 half-fill, leaving a
  resting remainder → unbounded book growth with the bench's QTY=500 default); credit is effectively
  unlimited (`Long.MAX_VALUE/4`), so it was never the limiter.
- **UI create-order path is broken** — `trade-service` pointed at the endpoint-less
  `order-matcher-primary`, and the gateway had no `/trades` route. Added the route (`35fd903`); user
  reports it STILL fails after repoint+redeploy → Open Issue A.
- **Bench re-measured; two measurement traps corrected:** (1) NATS `in_msgs` (publishes) vs `out_msgs`
  (deliveries) — misreading this cost many turns chasing a non-existent matching bug; (2)
  **`sessionAffinity: ClientIP` pinned all load to ONE gateway** — 2 of 3 gateways were idle all
  session, hiding the scale-out (Service path 41k → direct 3-gateway 64k submit).
- **NOT resolved:** 134k did NOT reproduce today (`batch=1000` clean-first-load gave ~2k) — leading
  suspect is today's image rebuild changing the member binary, UNVERIFIED; and the clean leader-kill
  failover was not run (only a spontaneous election under load was caught: 4/654 failed, 99.4%).

## 6. Current deployed state

- 3-member Aeron Cluster on `blp-c3-pool` (3× c3-standard-4, one member/node, tainted `workload=blp`,
  Guaranteed QoS, anti-affinity). emptyDir data. Image `cluster-node:yu12`.
- 3-replica gateway on `std-pool` (REST 18110 / FIX 18130), Service `order-matcher-gw`.
- Trade bridge active (`TRADE_BRIDGE_NATS_URL` set); trade-processor at -Xmx820m; DB truncated clean.
- Deploy is direct commands (no CI/CD for YU12); `kubectl apply -k gke/` renders the emptyDir variant.

## 7. Settled design (ADRs)

- **ADR-044/045** — Aeron Cluster consensus; the **consensus log is the ONLY input** (price ticks +
  control updates become cluster ingress via a feed adapter). One deterministic input → identical
  state on every member.
- **ADR-046** — snapshot completeness covers every future-output generator (incl. idempotency
  retention order + terminal-eviction FIFO).
- **ADR-047** — stateless-forward FIX/REST gateway follows the leader; horizontally scalable.
- **ADR-048** — leader-side trade-egress → NATS bridge (best-effort tap, NOT a consensus concern).

## 8. Open issues (formalized this session)

| File | Issue | Priority |
|---|---|---|
| `HANDOFF-issue-yu12-services-ui-rewire.md` | Rewire frontend + TraderX services to cluster/gateway/bridge; fix create-order | **Blocking** |
| `HANDOFF-issue-yu12-sustained-throughput.md` | Reproduce 135k reliably + make it sustained (incl. bench harness) | High |
| `HANDOFF-issue-yu12-gateway-sessionaffinity-split.md` | Per-port Service split so REST scales out | Ready now |
| `HANDOFF-issue-yu12-failover-measurement.md` | Clean leader-kill number + spontaneous-election cause | Open |
| `HANDOFF-issue-yu12-bridge-at-least-once.md` | Published-offset checkpoint gap (stub) | Low |

## 9. Hard-won gotchas / invariants (the operational landmines)

- **Only the 7 real SQL accounts persist** (10031, 11413, 22214, 42422, 44044, 52355, 62654):
  `trades.accountid` FK → `accounts.id`. `/seed` enables cluster risk state but does NOT create a SQL
  row. Arbitrary /seed accounts trade in the cluster but FK-fail on persist AND poison Hibernate batch
  flushes (one bad trade fails the whole batch) → fresh cluster restart is the only clean-up.
- **`imagePullPolicy: Always`** — a clean `scale 0→3` pulls the latest image on all members; a rebuild
  silently changes the running binary (this is the 134k-reproduction suspect).
- **emptyDir + `rollout restart` can lose the un-snapshotted tail** (readiness now gates on catch-up
  to fix this, but whole-cluster simultaneous death still needs GCS restore). Pod-level faults are safe.
- **Whole-cluster restore/wipe = NEW epoch** → gateway + any client must fully reconnect (native
  leader-follow survives a leader change but NOT a full wipe).
- **Rejects still consume orderRefs**; non-marketable accounts silently accumulate → risk-cap rejects.
  Canary/bench must use marketable flow + a dedicated account.
- **linux/amd64 only** for GKE (`YU12_PLATFORM=linux/amd64`) — arm64 Mac build won't run.
- **Bench needs** `MATCHER_SVC=order-matcher-gw` (the old `order-matcher` Service has no endpoints),
  a `/seed` first, `LIMIT=150 SIDES=alternate`, and per-pod metric summing under scale-out.
- GCS auth uses the journal-archive HMAC via **boto3 S3 endpoint** (node SA is storage-RO; gsutil
  picks metadata-SA over HMAC) with s3v4 + path-addressing + `AWS_*_CHECKSUM=when_required`.
