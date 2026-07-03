# TraderX — Handoff: branch state, lineage, and where to pick up

**For:** teammate's Claude session, onboarding onto this work cold.
**Repo:** `YU-CS-Summer-2026-Distributed-TraderX/traderX` on GitHub.
**Active branch:** `lmax-kubernetes-blp-ha` — pushed and up to date as of 2026-07-03.

> You already have GCP project access (`tanidiament@gmail.com`: Editor + `clouddeploy.approver`)
> and a Cloud Build CI/CD pipeline exists for order-matcher — **read §7 before doing any manual
> `kubectl`/`docker push` against the shared cluster.** Both of us pushing directly to the same
> live pods is exactly the collision this pipeline exists to avoid.

---

## 1. Branch lineage (how we got here)

```
main
 └─ lmax-sequencer-no-gc (state 009b: LMAX Disruptor BLP core, no k8s, no replication)
     ├─ gridgain-research      (teammate's perf branch: journal batch coalescing,
     │                          bounded terminal retention, no-GC work)
     └─ lmax-kubernetes         (GKE deployment work, single BLP)
            │
            ▼  merged at f0dd482 "gridgain branch and lmax-kubernetes branch combined"
            │  (2026-07-01 17:07) — gridgain-research's perf work folded into lmax-kubernetes.
            │  gridgain-research itself has NOT moved since (HEAD d70f703, "stuff",
            │  2026-07-01 13:35) — treat it as merged/frozen, not actively diverging.
            │
      lmax-kubernetes  ──(no further commits since f0dd482 — STALE)──
            │
            └─ lmax-kubernetes-blp-ha   ← YOU ARE HERE (active branch)
                 diverged from lmax-kubernetes at f0dd482, then:
                 2a0244b  Add BLP high-availability (leader election, NATS JetStream
                          replication, heartbeat failover) — 2026-07-01 23:04
                 82977f5  Fix broken MariaDB projector (Postgres syntax bug) + HdrHistogram
                          metrics race — 2026-07-02
                 e365690  Fix DATABASE_PG_PORT=5432 hardcoded on 3 services (should be 3306)
                 b44efaf  Document deploy discipline + repo hygiene in CLAUDE.md
                 90ff61d  Dedicated c2-standard-4 node pool + resources/probes tuning
                 493e058  Async-pipelined NATS JetStream replication (HA throughput fix)
                 4c0633c  Add CI/CD pipeline: Cloud Build -> Cloud Deploy (order-matcher)
```

**Important:** `lmax-kubernetes` (no `-blp-ha` suffix) is a **different, older, stale branch**.
It still has the bugs listed below unfixed and deploys as a plain single-replica `Deployment`
(no HA, no StatefulSet). Don't build/deploy from it by mistake — always confirm you're on
`lmax-kubernetes-blp-ha`.

---

## 2. What's actually running on GKE right now

- **Cluster:** `traderx-lmax`, zone `us-east1-b`, project `traderx-501015`.
- **Live at:** `https://yaakovseif.dev`
- **Deployed from:** `lmax-kubernetes-blp-ha` @ `4c0633c` or later (verify with `git log -1` after you pull).
- **order-matcher (the BLP)** is currently in **HA mode**: 2 replicas, leader-elected
  PRIMARY/FOLLOWER, NATS JetStream replication, on a dedicated node pool.

Full architecture (node pools, data flow diagram, all operational commands) is in
**[`CLOUD-ARCHITECTURE.md`](CLOUD-ARCHITECTURE.md)** — read that before touching infra. Short version:

| Node pool | Machine | Count | Purpose |
|---|---|---|---|
| `default-pool` | e2-standard-2 | 3 | Everything except order-matcher |
| `blp-pool` | c2-standard-4 (dedicated, tainted) | 2 | order-matcher only |

---

## 3. Bugs found and fixed today (2026-07-02) — read this before debugging anything similar

These took most of a day to find. If something looks broken in a way that matches one of these
symptoms, it's very likely **not fixed on other branches or in other environments** — check there
first before assuming it's new.

1. **`ProjectorHandler` used PostgreSQL `ON CONFLICT ... DO UPDATE` syntax against MariaDB.**
   MariaDB doesn't understand it → every DB flush failed → **zero trades were ever persisted**,
   silently, for the life of the deployment. Fixed: `INSERT ... ON DUPLICATE KEY UPDATE`.
2. **`HotPathMetrics.renderHistogram`/`renderCountHistogram` read a live `ConcurrentHistogram`
   while the BLP thread concurrently wrote to it** → `ConcurrentModificationException` /
   `NoSuchElementException` on every `/metrics` scrape under load. Fixed: snapshot via
   `histogram.copy()` before reading (the HdrHistogram-supported pattern for this).
3. **`account-service`, `position-service`, `trade-processor` had `DATABASE_PG_PORT=5432`**
   hardcoded in their Deployment manifests (leftover from the Postgres→MariaDB migration —
   `application.properties`'s `:3306` default never took effect because the env var was
   explicitly set). Result: HikariCP hung silently forever, no error logged, just an endless
   `HikariPool-1 - Starting...` loop. Fixed in the manifest overrides.
4. **Root cause pattern behind #1–#3:** deployed images were *stale relative to committed
   source* — the fixes already existed in git, GKE was just running an older build. See
   `CLAUDE.md`'s "Deploy discipline" section for the rule this produced: rebuild+redeploy from
   `generated/` after every merge, unique dated image tags, never reuse a mutable tag like
   `state009`.
5. **`NatsJournalReplicator.onEvent` called `js.publish()` synchronously per event** — one
   JetStream round-trip per event, so a 1000-event batch cost ~688ms and HA topped out at
   ~1.7k booked/s. Fixed: `publishAsync()`, pipelined within a batch, broker ACKs drained once
   per Disruptor batch (or when a 256-in-flight window fills). The follower-ACK spin-gate at
   `endOfBatch` (the actual synchronous-replication durability mechanism) is **unchanged** —
   only the wasteful per-event broker round-trip was removed.

---

## 4. Throughput, as measured today

All measured in-cluster (no `kubectl port-forward` — that tunnel is unreliable at high
concurrency and gave misleading numbers earlier in the session; use
`scripts/bench/run-gke-bench.sh`, which runs load from an in-cluster pod).

| Config | booked/s | Notes |
|---|---|---|
| In-process BLP only (JUnit, no I/O) | ~2,400,000 | Proves the BLP thread itself is never the bottleneck |
| Single-BLP, no dedicated resources | ~6,000 | Original state; crashed under real concurrency |
| Single-BLP, resources + tolerant probes | ~13,000 | Stable, but core-bound on shared 2-vCPU node |
| **Single-BLP, dedicated c2 node** | **~42,000** | Matches local docker-compose baseline |
| HA, old synchronous-per-event replication | ~1,714 | The bug fixed in `493e058` |
| **HA, async-pipelined replication, c2 node** | **~22,000** | At `conc<=8` — see known issue below |

Bottleneck diagnosis that got us here: it was **never NATS/the message bus**. It was (a) the
BLP pod being CPU-starved on a shared/BestEffort node, and (b) the synchronous per-event publish
in HA. Aeron was considered and explicitly rejected — see `HANDOFF-ha-throughput-improvements.md`
for the reasoning if you're tempted to revisit it.

---

## 5. Known issues / where to pick up (not yet fixed)

Pick any of these — they're the natural next steps:

1. **HA leader-election lease starves under high concurrency.** At `conc>=24` in benchmarks, the
   CPU-saturating load starves the lease-renewal goroutine/thread → primary gets a 409 on lease
   renewal → false-demotes to FOLLOWER → all writes fail until the cluster re-settles (~15-25s).
   This caps *safe* HA throughput at whatever concurrency keeps the lease renewing (tested clean
   to `conc=8`, ~22k/s). Fix ideas: longer `leaseDurationSeconds`, or run the election
   heartbeat on a thread/priority that isn't starved by the saturated Disruptor/gateway threads.
   This is probably the highest-value next task — it's the actual ceiling on HA today.
2. **`blp-role=primary` pod label isn't always set after a redeploy**, so the
   `order-matcher-primary` Service can come up empty. The plain `order-matcher` Service still
   routes correctly (FOLLOWER reports not-ready, so only PRIMARY gets traffic), so this hasn't
   caused an outage, but it's a real bug in the label-patching logic in `LeaderElection.java`
   worth chasing down.
3. **Pushing single-BLP past ~42k** needs more cores for the REST gateway (confirmed
   gateway-CPU-bound, not BLP-thread-bound — the in-process BLP does 2.4M/s) — e.g. a
   `c2-standard-8`, or reducing per-order gateway allocation cost. **Not** CPU pinning — we
   verified analytically that pinning the single BLP thread wouldn't help since that thread was
   never the bottleneck, and a Guaranteed-QoS CPU limit would cap the *other* cores the gateway
   needs.
4. **Async-replication durability is only benchmark- and single-failover-tested.** One kill-primary
   test passed cleanly (follower promoted in ~25s, no split-brain, state intact) but a broader
   crash/partition test (kill mid-batch repeatedly, kill during a NATS network partition, etc.)
   hasn't been done. Worth doing before fully trusting this in production.
5. See `HANDOFF-ha-throughput-improvements.md` for further HA-specific levers (larger
   `batchRecords`, deeper ACK pipelining) that weren't needed to hit today's numbers but are
   there if you want to push further.

---

## 6. Getting set up

```bash
git clone https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX.git
cd traderX
git checkout lmax-kubernetes-blp-ha
git log -1   # sanity check: should show 4c0633c (CI/CD pipeline) or later
```

You already have GCP project access (`tanidiament@gmail.com`: Editor + `clouddeploy.approver`).
Set it up locally:

```bash
gcloud auth login
gcloud config set project traderx-501015
gcloud container clusters get-credentials traderx-lmax --zone us-east1-b
kubectl config use-context gke_traderx-501015_us-east1-b_traderx-lmax
kubectl get pods -n traderx -l app=order-matcher   # confirm you can see the cluster
gcloud auth configure-docker us-east1-docker.pkg.dev   # one-time, for manual docker pushes
```

Read `CLAUDE.md` in full before making changes — it has the deploy discipline rule this session
established (rebuild+redeploy from `generated/` after every merge, unique dated tags) plus the
repo hygiene rule (don't commit scratch/handoff docs like this one — this file itself should
**not** end up committed; it's meant to be read and then discarded or kept locally).

---

## 7. CI/CD — how deploys actually happen now (read before touching the cluster)

order-matcher has a **Cloud Build → Cloud Deploy** pipeline as of 2026-07-03. This exists
specifically so we don't collide by both running `kubectl set image`/`docker push` against the
same live StatefulSet — **use this instead of manual deploys for order-matcher.**

**Flow:** push to `lmax-kubernetes-blp-ha` → Cloud Build trigger `order-matcher-cicd` fires →
regenerates `generated/` from committed `specs/`, builds the jar, builds+pushes a uniquely-tagged
image, creates a Cloud Deploy release → release sits at `PENDING_APPROVAL` — **nothing touches the
cluster until someone approves it.**

**To approve/reject a release** (you have `clouddeploy.approver`, so either of us can):
```bash
gcloud deploy rollouts list --delivery-pipeline=order-matcher-pipeline --region=us-east1
gcloud deploy rollouts approve <rollout-name> \
  --release=<release-name> --delivery-pipeline=order-matcher-pipeline --region=us-east1
# or reject:
gcloud deploy rollouts reject <rollout-name> \
  --release=<release-name> --delivery-pipeline=order-matcher-pipeline --region=us-east1
```
Or use the console: **Cloud Deploy → order-matcher-pipeline**.

**To trigger a build without a git push** (e.g. testing locally before committing):
```bash
gcloud builds submit --config=cloudbuild.yaml --project=traderx-501015 .
```

Full details — exact IAM setup, why the artifact bucket had to be custom, the config files
(`cloudbuild.yaml`/`clouddeploy.yaml`/`skaffold.yaml`) — are in `CLOUD-ARCHITECTURE.md` §6.

**Scope:** order-matcher only right now. Other services (`account-service`, `trade-processor`,
etc.) still deploy via the manual commands in `CLOUD-ARCHITECTURE.md` §5 — extending CI/CD to
those is a reasonable next task if you want one.
