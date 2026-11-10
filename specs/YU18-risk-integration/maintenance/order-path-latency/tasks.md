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
