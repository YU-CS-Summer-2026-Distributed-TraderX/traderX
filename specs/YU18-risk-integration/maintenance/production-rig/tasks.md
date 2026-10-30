# Production rig tasks

- [x] Parity tiers, member heap and gateway shape rendered and validated by `prepare.py` (FR-PRG-05/06/07);
      `test_prepare` 38/38, and the three new tests fail on the previous renderer.
- [x] Member heap kept at 12 GB, on generational ZGC, with `MAX_ACTIVE_BOOKS=1024` pinned. The bench's 6 GB would cut
      the derived book cap from 1,024 to ~436, an apply-path change (ADR-079 §6).
- [x] Gateway drain in one shutdown hook (FR-PRG-07). `FixSessionPersistenceIT` 9/9. Its drain test fails with the
      old stop-at-Logout behaviour: nothing stored, B logs on at 3.
- [x] `rig.sh` pools/up/down/status with the restore gate (FR-PRG-01..04), `gateways.sh` (FR-PRG-06), HPA and pool
      autoscaler (FR-PRG-09). `test_rig.py` 16/16, three runs; with the gate removed, 9 of 10 `up` tests fail.
- [x] Gate G2 aligned with the backport's `/recon/shadow-state` and `/health?digest=1` (FR-ERH-F13,
      `claude/snapshot-reader-failclosed-00b31d33`).
- [x] G-pre: before the members start, each claim's past error log is read in a read-only pod, and a past partial
      restore stops the bring-up. Members are held at 0 first, because production's StatefulSet was left at 3 with
      Pending pods. A re-run on a gated rig stops nothing. `test_rig` 22/22.
- [x] G-pre reads the service container's error log too. `ClusterTool errors` (Aeron 1.51) throws on the absent
      driver's `cnc.dat` before it reaches the service mark files, and maps the files read-write, so on production it
      would have failed every bring-up and never read the service log. The pod now greps both mark files' bytes on the
      read-only claim. Tested on mark files Aeron itself wrote; `test_rig` 24/24.
- [x] Quota: regional CPUS 3,000 (no separate C4D quota); a full rig with 18 gateways is ~196 vCPU.
- [x] Declared two-commit population for 5a (FR-PRG-10): members `cluster-node-member` from `c562bbab`, the rest
      from the tip. Every stage refuses neither-commit images and undeclared mixes; `test_population` 23/23,
      `test_prepare` 42/42. The tip's ingress decodes on the members' codec (`rig/codec-compat.sh`, 518 checks).
- [x] 5a population declared (2026-10-29): tip `9c63c4ca`, exception `order-matcher-cluster` → `cluster-node-member`
      from `c562bbab`. Admitted: 13 payloads equal to independent reference builds, 22 images / 24 slots, 23 controls
      including the four population refusals on the measured pack.
- [x] First production `up` (2026-10-29, 1:22–1:30 pm ET): pools, G-pre (both mark files, 3 members clean), members 3
      Ready, then REFUSED G0 on `engineApplied -1`. The members agreed (applied 3469, trades 18, both hashes); -1 is
      the documented reading after a restore into an idle cluster. G0 and G4 corrected; gateways never started.
- [x] Rehearsal on production, 2026-10-29 1:22–4:02 pm ET (ADR-096 Evidence, production rehearsal): pools, up and the
      restore gate, ILB spread, grow/shrink with live FIX sessions, autoscale up and down (at a 15% target for the run;
      60% restored), exactly-once over 3,498,116 orders. `down` 4:04–4:07 pm: gateways drained, barrier (snapshots
      past 28), baseline applied 3646350 / trades 3392664, members exited 143, client VM TERMINATED; every rig
      instance gone by 4:11 pm. Its instance check had passed vacuously (gcloud crashed on the filter): fixed.
- [ ] FIX session store: a `ResetSeqNumFlag=Y` Logon racing the lease handover is undone by the store load (ADR-086;
      CLIENT4/6/8 wedged 50 minutes). FIX session lane.
- [ ] Autoscale signal: 7 FIX sessions cannot reach 60% CPU; evaluate the in-flight gauge (§3) or more sessions.
- [ ] Read model under load: the orderbook projection is about 1.2K rows/s (one UPDATE per order event); the
      downstream redesign's partitions or `ORDER_FEED_PERSIST=false` (G3 must then read the in-memory view).
- [ ] Production run in rollout 5a (yaakov approves in session).
- [x] Load clients CLIENT2–CLIENT8 (FR-PRG-11), opt-in in the render, each on its own test account; passwords in
      Secret `fix-test-clients` and `~/traderx-private/fix-test-clients.json` only. `test_prepare` 46/46.
- [ ] Clean up the rehearsal's load: yaakov approved real load on dedicated test accounts, leaving their trades in
      production's database to clear later. Accounts 9900002–9900008, CompIDs CLIENT2–CLIENT8; window 2026-10-29
      1:51 pm (accounts created) to 4:02 pm ET (last leftover order cancelled): 3,498,113 orderbook rows, their trades
      and positions, all on those accounts. Plus the smoke orders on 42422 and the TLS smoke's two orders (42422/22214). Then delete exactly those accounts' orders, trades and positions, render
      without `--fix-test-clients`, and delete Secret `fix-test-clients`.
- [x] Binary load for the throughput run (FR-PRG-16, 2026-10-29): `rig/binary-load.mjs` + `binary-load.sh`, offline
      against a scripted gateway. `rig.sh pools` creates `loadgen-pool`,
      `rig.sh loadgen N` sizes it, the pods use the candidate's console image (node 20.19.2). Before production:
      `--phase-secs` short enough that one phase's buys per account (aggregate rate ÷ 2 ÷ 4 × phase) stay under the
      position limit; the policy itself stays as it is. Kind run done:
      every accepted order traded (members' trade sides rose by exactly the accepted count).
- [x] G1 above the full-history index cap defers to G2 (FR-PRG-18, 2026-10-29).
- [ ] Log retention for production (yaakov 2026-10-29: "we can also delete older stuff"). Not tonight. Estimate about
      1 day, plus a kind proof and one production cycle.
      - Purge the members' log behind a snapshot (archive purge, the bench's B2, proven on hxb at 250K/s). This bounds
        B1's growth and G2's replay time.
      - Once the log is purged, G1 and G2 cannot replay from 0. They switch to snapshot plus tail: replay from the
        latest snapshot, then compare against the members. An empty snapshot-plus-tail is INCONCLUSIVE, never a pass
        (as the hxb purge arm, `88681eae`). G4 is unchanged.
      - Clear the test accounts' SQL rows only together with `rig.sh unprojected` (or a per-account G3 variant), or G3
        refuses.
- [x] Snapshot read-model profile (`prepare.py --read-model snapshot`, FR-PRG-20) and its G3 (FR-PRG-21), offline
      (`test_prepare.SnapshotReadModel`, `test_rig.Up.*snapshot*`); measured on throwaway VMs (ADR-097 Evidence).
- [x] The G3 dispatch and the snapshot G3 fail closed; `rig.sh snapshot-base` writes the base once (FR-PRG-22).
- [x] G-pre and G4 fail closed on a log or baseline they could not read (FR-PRG-23, codeX review 2026-10-30).
- [x] The gateway shrink deletes only nodes a successful listing shows empty (FR-PRG-24, codeX review 2026-10-30).
- [x] G1 and G2 mint a fresh admin token per member, so a long replay cannot expire the next member's (FR-PRG-25).
- [ ] Before the profile runs on production: create `readmodel-pool` and `db-pool` in `rig.sh pools` and size them
      in `up`/`down` (rollout lane); then the cutover in ADR-097 §5, which records the base with `rig.sh snapshot-base`,
      snapshots `eod-price-db-data` with the database at 0, and replaces `TRADERX_FEED` with `TRADERX_FEED_P<p>`.
- [ ] FIX at scale (2026-10-29 night):
      - The KV session store caps 600 sessions at ~29K/s answered.
      - Long reconnects resend past the idempotency window: ~1,600 double-bookings on 9900002–08. Needs ADR-090
        sequencing on the members (rollout 5b) or a resend bound.
      - Clean up those accounts' rows with the rehearsal's.
- [ ] The members' StatefulSet is RollingUpdate, not OnDelete. A template apply rolls the members at once, so any
      member change must go through the snapshot barrier first (issue: engine roll needs a snapshot barrier).
- [ ] The binary load's measured bound is the client in-flight window (16 pods reached ~430K/s). For the real
      ceiling: more pods, or `--max-in-flight` above 2,000.
- [x] Review and land `450c195b` (FR-PRG-22, branch `claude/g3-fail-closed`).
- [ ] FIX session rebalancing after a scale-up (sessions already connected stay where they are): not decided.
