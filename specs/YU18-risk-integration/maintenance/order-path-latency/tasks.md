# Order-path latency measurement tasks

- [x] `AeronPingPong` ping/pong RTT mode; loopback self-test (both idle strategies).
- [x] `binary-latency-probe.mjs`; mock-gateway self-test found and fixed the early-send undercount.
- [x] `l0-baseline.sh` / `l0-report.py`; the load precondition added after a block measured an idle leader
      (`void-no-policy/`).
- [x] GKE 2026-11-10: wire floor; blocks A, B (0 / 300K / 500K setting) and C (gateways on a spinning driver);
      engine-node CPU budget. Note `latency-l0-baseline-20261110.md`.
- [ ] Re-measure with the gateway driver on a parking strategy (`lowpark`), all three rates: pure spin cost 8% capacity
      at saturation.
- [ ] Next session: read `cpu.stat` throttling on gateway and member cgroups for the ~28–30 ms max seen in every loaded
      gateway window.
- [x] `fix-latency-probe.mjs` (FR-OPL-08): ack and fill stages over FIX; `test_fix_latency_probe.py` against a mock
      acceptor (injected delays on their stage, a dropped session refused); `l0-baseline.sh fix-probe`.
- [ ] GKE: the FIX probe on the acceptance rig, carried by acceptance run 4 (`CREDS=... l0-baseline.sh fix-probe T PX
      SECS`); record the windows and the coverage row.
