# GKE member runtime tasks

- [x] `order-matcher-archive` StorageClass and the generic ephemeral `/data` volume (lifecycle superseded by ADR-087).
- [x] StorageClass emitted by the CompSci render.
- [x] `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on both StatefulSet variants.
- [x] `ClusterNodeMain` closes the barrier and exits 143; `ClusterNodeShutdownTest`.
- [x] A SIGTERM during the startup election exits 143, not halt(70) (e3adcf9b); the test signals inside the election.
- [x] Promtail in the CompSci render.
- [x] Admission population 21 images / 24 slots, defined once in `admit.py`.
- [x] `/ready` refuses until the member's election is CLOSED (FR-GMR-08); `ReadinessDiagnosticsTest`.
- [x] Member `/data` on a retained per-member claim, and the render refuses an ephemeral one (FR-GMR-01, ADR-087).
- [x] No negative DNS caching on member JVMs (FR-GMR-09); `MemberDnsCacheTest`.
- [x] `yu18-minority-no-commit.sh` freezes the minority; `yu18-member-disk-survives.sh` for the disk-loss path.
- [x] Production moves from ephemeral to persistent claims: the roll plan in ADR-087 §4, done in roll 4 (2026-10-28, 11:05–11:08 am ET; `docs/deployment/traderx-demo-rollout-20261026.md` §13).

- [x] FR-GMR-11: `GATEWAY_IDLE_STRATEGY` on the gateway's driver, default `lowpark`; `GatewayDriverIdleTest`
      (2026-11-10, RI35 lane, branch `binary-acceptor-pipelining`).
- [x] FR-GMR-11 measured on GKE, 2026-11-10 2:45–4:45 pm ET (yaakov: "Yes, run it"; ~$35), one rig and one session:
      the `p4k` rig on `traderx-demo`, the ADR-103 render with L0's three member lines, members unchanged
      (`cluster-node-member:bat-eeedef16`), the 24 gateways on `cluster-node:gis-4b8e9348`. L0's harness, unmodified:
      a 500/s probe over 16 binary sessions, background binary-load 8 × 64 sessions, 3 × 60 s windows per rate.
      The default stands: `lowpark`.

      | `GATEWAY_IDLE_STRATEGY` | 0: probe p50 / p99 / p99.9 µs | 300K: probe p50 / p99 / p99.9 µs | 500K setting: achieved orders/s |
      | --- | --- | --- | --- |
      | `backoff` (before) | 1,101–1,145 / 1,777–1,805 / 1,934–1,946 | 735–740 / 2,303–2,543 / 3,274–3,343 | 430K |
      | **`lowpark` (default)** | **569–572 / 716–725 / 1,502–1,864** | **579–581 / 1,690–1,815 / 2,482–2,777** | 414K (−4%) |
      | `busyspin` | 489–496 / 1,119–1,128 / 1,303–1,605 | 536 / 1,704 / 2,495, then fell behind: 3.3–4.0 ms / 145–151 ms | 302K (−30%) |

      Against L0's noise (p50 ±1%, p99 ±2–5%, p99.9 ±15–20%):
      - At idle, `lowpark` is −535 µs at p50 and −1,070 µs at p99, about 40× the noise. That is better than L0's
        spin point (614 / 1,041).
      - At 300K it is −158 µs at p50 and −25% at p99.
      - At saturation it costs 4% of capacity.
      - `busyspin` has the lowest idle p50, but a worse p99 than `lowpark`. It cost 30% of capacity, more than L0's
        8% (smaller, more packets; the leader's receiver at 0.83 core against 0.47). At 300K it fell behind after
        its first window.

      This build pipelines binary sessions (FR-BSP-01), so the `backoff` arm, not L0, is its baseline:
      - Leader commit at 300K: p50 ~520 µs (L0 ~345).
      - The 500K setting is queue-bound at 200–320 ms. Each load session keeps up to 800 orders in flight, where
        L0's serial gateway held it to one. Only the achieved rate compares there.

      Throttling, for L0's ~28–30 ms max:
      - The gateways cannot be CFS-throttled: `cpu.max` is `max`, on 4 exclusive CPUs (static CPU manager).
      - The members (15-CPU quota, CPUs 0–15) were throttled in 0 periods in every window.
      - Clean 300K windows here had a gateway max of 3–8 ms. The outliers (135 ms, one 4.6 s at idle) are not CFS.

      Evidence: shared notes `gis-gke-20261110/` (per-arm tables, `extras.md`, `cgroups.txt`, `teardown-proof.txt`).

- [x] FR-GMR-12/13: per-agent idle (`CLUSTER_IDLE_STRATEGY_<AGENT>`) and `CLUSTER_ARCHIVE_THREADING`, default
      unchanged; `ClusterNodeConfigTest` (SC-GMR-07). 2026-11-10, branch `claude/latency-l1-l2`. Measured by
      `l1-ab.sh` (order-path-latency FR-OPL-07).
- [x] FR-GMR-14: `CLUSTER_MEDIA_DRIVER=external`, default `embedded`; `Dockerfile.aeronmd` (C driver, 1.51.0) and
      `aeronmd-sidecar.py`; SC-GMR-08. 2026-11-11, branch `claude/l6a-c-driver`. Local proof on the Mac's arm64
      `aeronmd`: one member elected and acked a proof-client order. The linux/amd64 image builds locally (not pushed).
- [ ] FR-GMR-14 measured on GKE (latency L6a): an `l1-ab.sh` arm with the sidecar applied (`kubectl get sts -o json |
      aeronmd-sidecar.py --image ... | kubectl replace -f -`, one member at a time), against the same session's Java
      driver arm at the same driver idle, at 0, ~300K and the 500K setting. Needs the image pushed to the bench
      registry and a decision on the C driver threads' idle.

## Still open


- The archive purge sidecar is not wired into the GKE tier (ADR-079 §6). With retained claims the archive grows
  for the life of the claim, not of the pod.
