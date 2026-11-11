# Order-path latency measurement tasks

- [x] `AeronPingPong` ping/pong RTT mode; loopback self-test (both idle strategies).
- [x] `binary-latency-probe.mjs`; mock-gateway self-test found and fixed the early-send undercount.
- [x] `l0-baseline.sh` / `l0-report.py`; the load precondition added after a block measured an idle leader
      (`void-no-policy/`).
- [x] GKE 2026-11-10: wire floor; blocks A, B (0 / 300K / 500K setting) and C (gateways on a spinning driver);
      engine-node CPU budget. Note `latency-l0-baseline-20261110.md`.
- [x] Re-measure with the gateway driver on `lowpark`: RI35's GKE A/B, 2026-11-10 (gke-member-runtime FR-GMR-11). It is
      the gateways' default, and `l1-ab.sh` holds it throughout.
- [x] `cpu.stat` throttling, for the ~28–30 ms max: RI35's run read 0 throttled periods on every member in every
      window, and the gateways have no quota. `leader_state` now records it per window (FR-OPL-06).
- [x] L1/L2 harness: `static`, `arm`, `gw-idle`, `seed`, `leader_state` (l0-baseline.sh), `AeronCounters`,
      `l1-report.py`, `l1-ab.sh` (FR-OPL-05..07).
- [x] `l1-ab.sh` on slot A, 2026-11-10/11: five arms × three rates (L12 500K: 2 of 3 windows). Result and
      take/leave calls in the shared note `latency-l1-l2-20261110.md`.
- [x] Order control, GKE 2026-11-11 (N′ S′ N″ S″): static costs 4–15% of saturation capacity in every static arm;
      FR-PRG-05's static precondition is held out of production (shared note `latency-static-cpu-20261111.md`).
- [ ] Busy-spin (L1) on SHARED cores: last night's L1 gain was measured on static, so it carried static's capacity
      cost. Add it to the next GKE session (with L6a, busy-poll, pinning and wmem).
- [x] binary-load retries also restarted a load that stopped itself at a phase boundary. Both exits are code 1, so no
      Job failure policy can tell them apart: the Job is back to `backoffLimit: 0`, and binary-load retries its own
      `/resolve` (no order sent) for up to a minute.
- [ ] A member whose archive filled cannot restart: `ENOSPC` left `archive-mark.dat` with `activityTimestamp =
      Long.MAX_VALUE`, which the 60 s mark-file wait never clears. Treat MAX_VALUE with a dead pid as stale (own spec
      row and test). Production's 600Gi archive has no purge on the GKE tier either (~4 h at 450K/s).
- [x] `fix-latency-probe.mjs` (FR-OPL-08): ack and fill stages over FIX; `test_fix_latency_probe.py` against a mock
      acceptor (injected delays on their stage, a dropped session refused); `l0-baseline.sh fix-probe`.
- [ ] GKE: the FIX probe on the acceptance rig, carried by acceptance run 4 (`CREDS=... l0-baseline.sh fix-probe T PX
      SECS`); record the windows and the coverage row.
- [x] Session 3 on GKE (2026-11-11): busy-spin on shared cores, busy-read, wmem, static, pinning, and the C media driver
      at an equal member share. Calls in the shared note `latency-s3-20261111.md`.
- [ ] Confirm `busy_read`/`busy_poll`=50 once more at the next roll, interleaved, before production takes it.
- [ ] wmem: repeat interleaved with its control (this session's +115 µs step outlived the arm).
- [ ] Pinning at saturation with the other threads on 4–5 cores (this session's one window logged 3,815 cycles > 1 ms).
- [ ] Long runs: plan epochs under the 2³¹ order-ref ceiling (~80 min at 450K/s) until that issue is fixed.
