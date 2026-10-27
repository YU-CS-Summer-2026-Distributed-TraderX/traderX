# Full stack on kind from the YU18 tip — 2026-10-26

**Status: kind half done, then torn down at yaakov's request (9:20 pm ET; the Mac could not carry it). The
remaining proof moves to GKE (`traderx-demo`, a new namespace).** Times are US Eastern.

Source: `traderX-risk-integration` at `de5e5d53`, rendered into an empty root
(`TRADERX_GENERATED_ROOT=<scratch>/gen-yu18`, `TRADERX_SKIP_LOCKFILE_REFRESH=1`). One kind cluster
(`traderx-yu12-cluster`, 1 control plane + 3 workers), images round-tripped through `docker save` +
`kind load image-archive` and checked with `crictl` on every node.

## What ran

Every pod 1/1 Running (27 pods): 3 order-matcher members, cluster-gateway, risk-extract, feed-adapter, nats,
eod-price-db, trade-processor, position-service, price-publisher, reference-data, execution-algo-engine,
account/people/trade-service, web-front-end-angular, api-explorer, edge-proxy, OTel collector, Tempo,
Prometheus, Loki, Grafana, promtail. The demo console (`server.mjs`, :4321) and the Desk (`ng serve`, :4320)
ran on the host against edge-proxy, as `web-front-end-console-combined-prototype/start-rig.sh` does.

Members read `applied` from their own `/health`, not just Running: all three moved together throughout.
`CLUSTER_IDLE_SLEEP_MS=1` was set on the members after the first suite run starved the kind API server
(load average 21, TLS handshake timeouts) while the in-process VaR/ES cluster test ran beside it. It is a
correctness-only knob, so no number here is a performance number.

## Results

| Proof | Result |
| --- | --- |
| Order through the demo console ticket (after `12a0a21b`) | **PASS** at the effect end |
| SQL projection `GET /v2/projection-feed` | **PASS**: backlog 0, dropped 0, missingTradeSides 0, lastTradeSeq tracked every trade |
| Trace in Tempo for a placed order | **PASS**: 5 spans, gateway `order`/`gateway.queue`/`cluster.consensus`, member `cluster.commit`/`cluster.apply` |
| Logs in Loki | **PASS**: 19 services labelled, gateway lines for the traded ticker |
| `yu15-option-persistence`, `yu15-risk-extract` | PASS |
| `yu16-bond-position`, `yu16-accrued-interest`, `yu16-book-grid`, `yu16-ready-tracks-commit`, `yu16-liveness-restarts-wedge` | PASS |
| `yu17-swaption-terms`, `-fnma-collar`, `-option-collar`, `-fine-grid`, `-book-retick`, `-session-closed-rejects`, `-preopen-queue-open`, `-swap-netting`, `-fx-credit`, `-keyed-ack-correlation` | PASS |
| `yu16-treasury-pricing` | FAIL — real finding 3 below |
| `yu17-retick-determinism` | FAIL — stale premise, finding 4 |
| `yu17-taq-replay`, `yu17-replay-attribution` | FAIL — environment: the replay extract lives in `gs://traderx-501015-tick-store`, which `aseif@mail.yu.edu` cannot read (403), so the rig had no tape |
| `yu17-session-opens-from-close` | SKIP — no published session before today on a fresh rig |
| `yu17-halt-survives-failover`, `yu17-closed-survives-restart` | SKIP — destructive, off by default |
| `yu18-var-es-e2e` | TraderX half PASS (cut seq 23, sha `c493d7a6…`, 6 positions + 2 contracts, receipt written). Engine half reports FAILED by design: Alex's `f9cc4c1` priced the Treasury bill (×2) and the GBP swap (VaR/ES finite, non-zero; portfolio VaR99 109,925, ES99 124,838) and refused equity ×2, option ×2 (no product) and the USD-SOFR swap (ACT/360 not implemented), each by name; negative control 400 as expected |
| `yu18-networkpolicy-enforced` | Not run — GKE-only by design |

EOD close → marks → extract: `yu15-risk-extract` ran the real chain on kind (session 2026-10-27 v1 PUBLISHED,
67 instruments, 0 flagged; P&L 6 accounts, 19 rows; extract schema 3 with `priceSnapshotVersion: 1`; the cut
byte-identical on all three members at sequence 11304, witness 11305; member 2 restarted, replayed to 11304
and re-rendered the same cut). `yu16-accrued-interest` read the same extract. A row-by-row comparison of
extract marks against `eod_price_snapshot` was not done before teardown; it goes into the GKE half.

### The console order, at the effect end (9:07 pm ET)

Signed in to the demo console, Trading ticket: 42422 Sell 5 MSFT @ 393 → `orderRef 537 accepted`; 22214 Buy 5
@ 394 → `orderRef 538 accepted`. Members 320 → 322 trade sides, all three at applied 54008. SQL trades
`321-S` (42422, Sell 5 @ 393) and `322-B` (22214, Buy 5 @ 393). Orders: 538 FILLED; the older resting 536
(Sell 10 @ 393, placed by the console chat while verifying its fix) PARTIALLY_FILLED ahead of 537, which is
price-time priority. Positions 22214 +15 / 42422 −15 (+10/−10 from an earlier authenticated gateway cross).
The blotter showed `live · message bus` and the new trade.

The Desk loaded, created accounts 65000/65001 through its own path (SQL row + engine admission), and
**refused to send an order**: it requires a managed run (`/order-matcher/run/status` →
`RUN_DESCRIPTOR_NOT_CONFIGURED`), and the kind bring-up provisions none. That is the Desk's documented gate,
not a crash, but it means no stock bring-up can trade from the Desk.

## Fixes made (committed on `traderX-risk-integration`, not pushed)

- `9fc12f69` — the yu15 build/bring-up scripts honour `TRADERX_GENERATED_ROOT` (an empty-root render could
  not be built or deployed before); `start-cluster-kind.sh` gains `SKIP_KIND_LOAD=1`, checked per node; the
  runner's symbol-table probe sends the S2 operator pair (it read the 401 as "no usable answer" and refused
  to start).
- `c8eb9233` — 22 YU15–YU17 proofs called gateway routes bare and failed 401 since ENGINE-REVIEW S1/S2;
  `scripts/proofs/lib-gateway-auth.sh` mints one admin JWT and every bare call sends it.
  `yu15-option-persistence` now parks the >16-character catalog rows in a table: an interrupted run had
  deleted every bill, STRIP and two corporates from reference-data for good (finding 2).
  `yu16-treasury-pricing` leaves matured bonds off the curve.
- `bbe9b040` — **gateway control feed (S4 regression)**: after `5ca69f3f` the control feed used the lookup-only
  `resolveSecurityId`, so 276 catalog tickers failed and were redelivered once a second forever (15 of 534
  applied). It registers again; afterwards 534 applied, 0 failures, and `yu17-book-retick` passes. Gateway
  class only; the deterministic core is unchanged.
- `12a0a21b` (console chat, from this run's report) — demo console order entry signs the gateway request
  for a signed-in operator.

## Findings not fixed here

1. **Legacy UI order path is broken by S1.** trade-service forwards to the gateway's `/trades` without a
   bearer, gets 401, and answers the user 500. Needs a service credential decision for trade-service.
2. (fixed, above) A proof deleting reference data without a durable park.
3. **Offline bill prices never accrete.** Without a FRED key the publisher random-walks Treasury prices from
   the auction seed, so a 13-week bill 16 days from maturity still prints 0.98975 and a 25% yield. With the
   FRED curve, bills are priced by remaining term. Kind (and any rig without `fred-api`) shows it.
4. **`yu17-retick-determinism` step 1 needs a decision.** It records the "registered but not enabled" window
   via `/resolve`; S4 made `/resolve` a lookup, so that window is no longer reachable from any client path.
   The proof's owner should decide whether the step is retired or re-expressed.
5. **`build-images.py`'s derivation guard refuses while other chats commit** ("current source changed during
   derivation admission", twice). The images here were built by hand from the empty-root render.

Evidence (outside Git): `<scratchpad>/evidence/` — proof logs (`proofrun3`, `proofrun4`), VaR/ES output,
bring-up logs, the final pod/image list and the order/trade rows above.

## GKE follow-up: `traderx-demo`, namespace `traderx-tip` (2026-10-26, 10:23–11:21 pm ET)

yaakov moved the rest of the proof to the cloud. The run used the `traderx-demo` cluster in a side namespace,
`traderx-tip`; the October 11 deployment in `traderx` was not touched. The coordinator approved the nodes:
engine-pool 3× c4d-standard-8 and default-pool 2× e2-standard-4, for about an hour (≈ $1.30). Both pools are
back to 0 and 1, confirmed with `gcloud compute instances list`.

Images: all 12 rebuilt from an empty-root render of `8ea15b88`, as `tip-8ea15b88`, linux/amd64. The derivation
guard admitted every one, they were pushed to `us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/`,
and the manifests pinned them by digest (the digests are in the rollout plan, §7).

| Check | Result on GKE |
|---|---|
| Bring-up | 24 pods ready, leader and 2 followers on a fresh epoch, one member per engine node |
| Reference data | 557 securities (544 enabled), fence at sequence 1291 applied on all three members, after the gateway fix below |
| Console order, real entry point | signed out → `401 admin_auth_required`; signed in → Sell 5 MSFT @393 (42422) × Buy 5 @394 (22214), orderRefs 1/2 |
| Effect end | members applied 1293 / trades 2 on all three; SQL `1-S`, `2-B` at 393; positions +5/−5 @393 |
| Projection feed | trades backlog 0, dropped 0, missingTradeSides 0, applied 2; orders backlog 0, applied 4 |
| Tempo | one 5-span order trace: gateway `order`, `gateway.queue`, `cluster.consensus` + member `cluster.commit`, `cluster.apply` |
| `yu15-risk-extract.sh` | PASS: seq 1296 cut byte-identical on 3 members, witness 1297, rebuild identical, member 2 replays to the same cut |
| Extract marks vs `eod_price_snapshot` | both rows (22214 +8, 42422 −8 MSFT) mark 392.68 `EOD_SNAPSHOT/OK` = snapshot 2026-10-27 v1 392.680000 OK; MV and P&L recompute exactly |
| Both consoles | demo console and legacy Angular UI serve their pages (200) |

Found and fixed (`8ea15b88`):

1. **Operator security control could not register a ticker (S4 regression, second path).** On a fresh epoch
   the CompSci reference initialization got `422 unknown instrument` for every catalog ticker, so the engine
   had zero securities and no order could trade. This profile has no control-feed adapter, so `bbe9b040` did
   not cover it. `/risk/control` `action=security` now registers; it is operator-only (S2), the same trust as
   `/seed`. Restrictions stay lookup-only.
2. **The CompSci renderer and init scripts were bound to `traderx`.** `TRADERX_NAMESPACE` now selects a side
   namespace. In one, the member and ingress addresses follow the namespace: unchanged, they pointed the side
   members at the live namespace's peers. risk-extract also writes the local file sink there instead of the
   demo's write-once extract bucket.

Found, not fixed:

- **The `order-matcher-archive` StorageClass is not emitted by the renderer.** Members stay Pending until it
  exists. It was applied once by hand (cluster-scoped, harmless to `traderx`) and is still on the cluster.
- **Loki got no logs in this profile** (the collector has no logs pipeline, and no promtail shipped).
  **Fixed afterwards** (11:45 pm ET, yaakov approved the cluster-wide apply): `prepare.py` now renders
  promtail, with a per-namespace ClusterRole/Binding (`promtail-<namespace>`), a scrape glob of
  `/var/log/pods/<namespace>_*`, and an engine-taint toleration so it also runs on member nodes. Proven on
  `traderx-demo`'s default node, with only Loki and promtail in a fresh `traderx-tip`: a marker pod's line came
  back from Loki under `{namespace="traderx-tip"}`, and Loki held zero `traderx`-namespace streams. Only the
  default node was up, so shipping from an engine node is covered by `test_prepare.py`, not seen live.
  The proof objects were removed, including both cluster-scoped ones.
- **NetworkPolicy cannot be enforced here.** `traderx-demo` has no Dataplane V2 and the CompSci render ships
  no NetworkPolicy, so `yu18-networkpolicy-enforced.sh` does not apply.
- **The derivation guard needs `SOURCE_DATE_EPOCH`** (as `docs/deployment/compsci-gke.md` says). Without it
  the UI metadata timestamp differs from the guard's own render and admission refuses. Running from a private
  detached worktree avoided the concurrent-commit refusals.
- VaR/ES was not re-run on GKE: its TraderX half is the extract proven above, and the engine half runs locally.
