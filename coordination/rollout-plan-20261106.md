# Production hardening rollout: PROPOSED 2026-11-06, waiting for yaakov's yes

Production is down: 0 nodes, every VM TERMINATED since 2026-11-05 5:15 pm ET, epoch e1104. Nothing here has run on
production. Each step below needs yaakov's yes in the rollout chat, and a relayed approval does not count. Before the
launch, re-project the cost against the $3,500 cap (about $2.3K+ spent).

## What is ready, and what is not

| Item | State | Evidence |
|---|---|---|
| 1. NATS memory | **Ready.** The sizing (FR-PRG-28: 24Gi = request = limit, `GOMEMLIMIT=20GiB`, `max_memory_store` 256MB) has been the base render since 2026-10-31. New: `loadgen.sh start` refuses a restarted NATS, and `results` reports one as FAILED (exit 3), so the operator sees it during the session, not only at `down`. | kind, `scripts/proofs/yu18-nats-memory-kind.sh`: control (the 10-30 config) OOMKilled within 30 s, and the real `rig.sh` `nats_problems` reported it. Fixed (the same ratios at 6Gi): two ~12 min runs at up to 400K msgs/s × 600 B, with 0 restarts and 0 cgroup OOM kills. The second run's scripted verdict was FAIL: its last pod listing timed out on a shared host, and the check fails closed. The heap held at ~94% of GOMEMLIMIT, so GC bounds it, not the 1 GB queue. Step 4 is the live check. The FIX store's fail-closed behaviour and its recovery after a NATS outage: `FixSessionPersistenceIT` 14/14. |
| 2. Exactly-once FIX | **The members already have it.** `tip-60544a6e-j21` carries ADR-090 and FR-XOD-18, and the fresh epoch e1104 started it on empty disks, so no member roll is needed. **Left: the gateway switch `FIX_EXACTLY_ONCE=jetstream`**, after NFR-XOD-03 is measured on the rig. | 51 tests at tip, 0 skipped (exactly-once, snapshot restore, three-member failover/rejoin, FIX store). The roll procedure for later core changes is in `maintenance/exactly-once-dedupe/plan.md` (Roll). |
| 3a. DB cutover (ADR-097 §5, per-partition streams) | **Not rig-ready.** Missing: `readmodel-pool`/`db-pool` in `rig.sh pools`/`up`/`down`, and a kind run of the snapshot profile. The trade-history-tiers lane is changing `rig.sh` and the same streams (ADR-102), so it should be one owner's change. | — |
| 3b. 3-server NATS (FR-DRS-34) | **Not rig-ready.** Measured on the bench only; no production manifests. It is needed only for the 400K-orders/s tier. | bench, `downstream-at-400k.md` §7.13 |
| 3c. Archive purge + snapshot+tail gate | **Not rig-ready.** The hxb prototype says it "never lands in a TraderX branch". It needs a member sidecar, G1/G2 snapshot+tail, and a kind proof. Fresh epochs bound the log meanwhile. | hxb `pg2`, 250K/s × 45 min |
| 4. Test-data cleanup | **Ready, not run.** `rig/test-data-cleanup.sh` (FR-PRG-38): `plan` is read-only; `apply` touches archived epochs only. | real-MariaDB test, 4/4 |
| 5. codeX pass 3 | **Done before this session.** It ran on 2026-10-30, and §15/§16/§17 are fixed on this branch (`b43e9042`, `423290c8`, `9b9cb12e`, `756f267b`, `ac85ade1`), all in `60544a6e`. | regression tests at tip: `OrderRefExhaustionTest` 5, `FixStoreGroupCommitIT` 9, `AttestationIT` 1, `StreamIdentityIT` 2, `HistoryFeedIncarnationIT` 1 |

## The session: items 1, 2 and 4 (one bring-up, ~3 h, ≈ $20–25)

No new image. Every change is a laptop script (`loadgen.sh`, `test-data-cleanup.sh`), and the gateway switch is an
env var on the admitted `60544a6e` gateway image. The e1104 read model lost the demo load's trades, so this `up` is a
fresh epoch anyway.

| # | Step | Gate (refuse = stop) | Rollback | Time |
|---|---|---|---|---|
| 1 | `rig.sh archive-members e1106`, then `rig.sh fresh-epoch e1106` (members at 0) | three READY disk snapshots of exactly the current disks | the snapshots are the archive; nothing is deleted before them | 15 min |
| 2 | `LOADGEN=2 rig.sh up` | G-pre..G4 on the fresh-epoch rules; NATS recorded intact (FR-PRG-28) | `rig.sh down` | 15–35 min |
| 3 | `test-data-cleanup.sh plan`, then on yes `apply e1101 e1101b e1104` (labels as `plan` lists them). Not `--accounts` or `--secret`: the loads still use them. | each label: 0 test-account rows remain; the live epoch unchanged | none needed (archive rows only). Run it after an archive dump if a copy is wanted. | 10 min |
| 4 | NATS soak, item 1 live: `loadgen.sh start 150000 600`, then `loadgen.sh results` | every pod `rejected 0 imbalance 0`; `NATS intact (FR-PRG-28)`; `kubectl top` NATS under 24Gi | `loadgen.sh stop` | 15 min |
| 5 | NFR-XOD-03: `fix-session-latency.mjs` in a pod beside the gateways, `FIX_EXACTLY_ONCE` off, then on (`kubectl set env deploy/cluster-gateway FIX_EXACTLY_ONCE=jetstream`: a gateway-only rolling restart, during which FIX clients resume on the remaining gateways, ADR-086) | SEQ/BURST p50 and p99 recorded for both; yaakov decides whether the on-delta is acceptable | `kubectl set env deploy/cluster-gateway FIX_EXACTLY_ONCE-` (gateway-only) | 40 min |
| 6 | Optional, yaakov's call: exactly-once across a leader failover, with `fix-rehearsal-load.mjs` on CLIENT2–8, deleting the leader member pod once | 0 double-bookings from the load's own counts; members equal at `down`'s baseline | the member rejoins from its log (normal HA) | 20 min |
| 7 | `rig.sh down` | barrier; `NATS stayed up`; 0 rig instances | — | 10 min |

Step 5's `set env` is a measurement, and the next `up` re-applies the render without it. Keeping it on means a
`prepare.py` render change in its own commit, with its spec row.

Step 4's load runs ahead of the row read model (~1,100 rows/s). The epoch's read model then lags the members, so the
next `up` is another fresh epoch, or a recorded `rig.sh unprojected` range. That is item 3a's limit, not item 1's.

Cost at ≈ $5–7/h for 3 gateways and 2 loadgen nodes: about 3 h ≈ $20, plus the disk snapshots (cents per day).
A run that breaks a gate stops there and tears down.

## Later: item 3, as its own work (not proposed for this session)

| Piece | Work before a rig | Production run |
|---|---|---|
| 3a cutover | pools and sizing in `rig.sh`, one kind run of the snapshot profile and the §5 sequence; ~1 day, coordinated with ADR-102 | ~3 h incl. the database disk snapshot/restore; ≈ $30 (readmodel c4d-16 + db c4d-8 added) |
| 3b 3-server NATS | StatefulSet, render and validate, kind run at reduced size; ~1 day | needs 3 × c4d-standard-16 (≈ +$2.5/h); only with a 400K-tier goal |
| 3c purge + snapshot+tail | port the hxb sidecar and `HxTailReplay` into the member image and G1/G2, kind proof; ~1–2 days | one cycle ~3 h ≈ $20; until then, fresh epochs bound the log |
