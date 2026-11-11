# Order-path latency measurement

Status: measured on GKE 2026-11-10 (latency L0, one rig, one session; disposable `pa-proof-400k`). The result and the
order of the latency work items it supports are in the shared note `latency-l0-baseline-20261110.md`; nothing on the
order path was changed. L1/L2 (2026-11-10): knobs and harness ready, the GKE A/B not yet run.

Sources:
- `specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/.../cluster/AeronPingPong.java`: `MODE=ping` /
  `MODE=pong`, the wire floor (FR-OPL-01).
- `scripts/bench/latency/binary-latency-probe.mjs`: the constant-rate binary probe (FR-OPL-02).
- `scripts/bench/latency/l0-baseline.sh`: `floor`, `probe`, `block`, `alloc` (FR-OPL-01, 03, 04).
- `scripts/bench/latency/l0-report.py`: one row per window from the evidence.
- Latency L1/L2 (FR-OPL-05..07): `l0-baseline.sh` `static`, `arm`, `gw-idle`, `seed` and the per-window
  `leader_state`; `AeronCounters.java` (YU12 cluster overrides); `l1-report.py` (`test_l1_report.py`); `l1-ab.sh`, the
  five-arm A/B; `scripts/gcp-compsci/rig/node-engine.yaml` (static CPU manager). The knobs themselves are
  gke-member-runtime FR-GMR-12/13.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
