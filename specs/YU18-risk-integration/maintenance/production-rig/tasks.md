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
- [ ] Rehearsal on production (yaakov: "Rehearse on production"), needing his approval in session and a spend
      projection:
      - `pools`;
      - `up` on the backport build (G0–G4 pass);
      - `gateways.sh 2 → 4 → 2` with FIX clients connected through the internal LB: new connections reach the new
        gateways, every drained gateway ends `inflight=0`, resume times recorded;
      - autoscale up under load and back down with no order lost (every order answered once, recon mismatch 0);
      - `down` with three exit-143 members and zero instances.
- [ ] Production run in rollout 5a (yaakov approves in session).
- [x] Load clients CLIENT2–CLIENT8 (FR-PRG-11), opt-in in the render, each on its own test account; passwords in
      Secret `fix-test-clients` and `~/traderx-private/fix-test-clients.json` only. `test_prepare` 46/46.
- [ ] Clean up the rehearsal's load: yaakov approved real load on dedicated test accounts, leaving their trades in
      production's database to clear later. Accounts 9900002–9900008, CompIDs CLIENT2–CLIENT8; the ET time window is
      recorded here when the run happens. Then delete exactly those accounts' orders, trades and positions, render
      without `--fix-test-clients`, and delete Secret `fix-test-clients`.
- [ ] FIX session rebalancing after a scale-up (sessions already connected stay where they are): not decided.
