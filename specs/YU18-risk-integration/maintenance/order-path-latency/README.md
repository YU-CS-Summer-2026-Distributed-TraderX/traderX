# Order-path latency measurement

Status: measured on GKE 2026-11-10 (latency L0, one rig, one session; disposable `pa-proof-400k`). The result and the
order of the latency work items it supports are in the shared note `latency-l0-baseline-20261110.md`; nothing on the
order path was changed.

Sources:
- `specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/.../cluster/AeronPingPong.java`: `MODE=ping` /
  `MODE=pong`, the wire floor (FR-OPL-01).
- `scripts/bench/latency/binary-latency-probe.mjs`: the constant-rate binary probe (FR-OPL-02).
- `scripts/bench/latency/l0-baseline.sh`: `floor`, `probe`, `block`, `alloc` (FR-OPL-01, 03, 04).
- `scripts/bench/latency/l0-report.py`: one row per window from the evidence.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
