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
      against a scripted gateway. Before production: a `loadgen-pool`, an image with node 20 the pool can pull, the
      risk policy's position limit at or above each buy account's peak (the run's total orders ÷ 8), and a kind run.
- [ ] FIX session rebalancing after a scale-up (sessions already connected stay where they are): not decided.
