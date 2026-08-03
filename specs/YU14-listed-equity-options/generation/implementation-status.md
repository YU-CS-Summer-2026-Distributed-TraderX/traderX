# Implementation Status: YU14-listed-equity-options

Status as of 2026-07-21. JVM proofs, the live kind option-chain smoke, and the no-regression
bench are complete.

## Generation

- `bash pipeline/generate-state.sh YU14-listed-equity-options` exits 0, recursively generating
  the full YU13→…→YU02→014 ancestry and overlaying the YU14 instrument-model/risk assets
  last-wins. All five registration points are wired (hook/render pair, catalog entry,
  runtime-harness case, CI-assets allow-list, wrapper scripts).
- Shared-override markers verified in the generated `order-matcher` tree — every ancestor's
  behavior survives the overlay alongside YU14's:
  - YU14: `OccSymbol`, `contractMultiplier` (9 references in `BlpRiskState`),
    `SNAPSHOT_FORMAT = 3`, `ticker32` in the SBE schema.
  - YU13: `LimitBook`, `FLAG_RESTING_UPDATE`. YU12: `aeron-cluster` dependency,
    `SymbolRegister` codec. YU03: `decideAndReserve` pipeline.

## The change surface (deliberately small)

- `lmax/OccSymbol.java` (new): unpadded OCC symbol parse; `multiplierFor(ticker)` is a pure
  function — option → 100, other → 1 (ADR-052).
- `risk/BlpRiskState.java` (YU03 ancestor): dense `contractMultiplier[]`;
  `decideAndReserve`/`decideMarketTrade` compute qty x price x multiplier inside the existing
  overflow-checked chain; `consume` accumulates multiplied executed exposure; the concentration
  projection multiplies the projected quantity (bounded by the position cap, no overflow path).
  `securityTuples()`/5-arg `bootstrapSecurity` unchanged, so every `LmaxEngine` ancestor
  compiles and behaves identically (multiplier is always 1 on the non-cluster path).
- `cluster/MatchingEngineClusteredService.java` (YU13 ancestor): `onSymbolRegister` derives and
  installs the multiplier from the committed ticker; snapshot format 3 appends the multiplier
  as T_SECURITY column 6 (effective value, never 0); restore fails closed on multiplier < 1 and
  on any non-3 format.
- `sbe/blp-replication.xml` + `lmax/AeronReplicationCodec.java` (YU12 ancestors): ticker field
  16 → 32 bytes (blockLength 24 → 40) so ~19-char OCC symbols register.
- `ClusterGatewayMain`: **zero changes** (parallel-lane boundary honored). Option seeding flows
  through the existing `/seed`; option orders through the existing `/orders`.

## Behavioral proof (all green on the JVM)

Full generated-tree suite: **242 tests, 0 failures** (YU13 carried 228; the YU14 additions all
pass alongside every inherited proof).

- `OccSymbolTest` — standard symbols (multi-char and 1-char roots), derived
  underlying/expiry/call-put/strike, equity tickers and eight malformed near-misses (bad C/P
  flag, short tail, non-digit strike, month 13, day 0, lowercase, digit-bearing root) all
  classify multiplier 1.
- `BlpRiskStateTest` (YU14 additions) — reservation at qty x price x 100; **the substantive
  acceptance**: qty 200 x $100 passes as an equity and rejects ORDER_NOTIONAL as a
  100-multiplier option; credit line walls after exactly 10 multiplied option reservations
  (the same flow un-multiplied sits at 1% utilization); `consume` accumulates multiplied
  executed exposure and releases the remaining reservation pro rata; the concentration cap
  fires on the multiplied projection with the un-multiplied control accepted; an unset
  multiplier behaves as exactly 1 (equity behavior bit-identical to YU13).
- `ClusterSnapshotCodecTest` (YU14 additions) — multiplier 100/1 derived through the real
  committed-ingress registration path round-trips the format-3 snapshot (restore reads the
  column, not re-derivation); an option contract registered, enabled, ticked at premium scale
  crosses identically on source and restored members (trade counters and book hashes equal);
  a T_SECURITY record with multiplier 0 fails closed; a format-2 legacy header fails closed.
  Every inherited format-2-era proof (band anchors, per-level FIFO, eviction order, generator
  invariants, corruption matrix) passes against format 3 unchanged.

## Zero-allocation and banned-API

All four allocation gates (`allocationGateTest`, `riskAllocationGateTest`,
`aeronAllocationGateTest`, `clusterAllocationGateTest`) executed fresh (`--rerun-tasks`) and
pass; `noGcTest` + `riskNoGcTest` (Epsilon) pass. The decision-path delta is one dense-array
read and one multiply; registration-path parsing is cold.

## Live kind proof (2026-07-21)

3-member cluster + 3 gateways (spread one per worker) on `kind-traderx-yu12-cluster`, image
`traderx/cluster-node:yu14`, fresh epoch (namespace + PVCs wiped first — band anchors are
first-limit sticky).

- **Option-chain seeding + smoke (the OSFF-1 silent-reject gate, exercised first):**
  `scripts/proofs/seed-option-chain.sh` seeded the packaged 24-contract chain (AAPL/MSFT x
  2 expiries x 3 strikes x call/put, intrinsic+$2 premiums) through the unchanged `/seed`,
  then booked one option cross on `AAPL260918C00240000` @ $3.80 — fills 0 → 2 (both sides).
- **No-regression bench** (in-cluster bench pod, conc 48 x batch 200, SIDES=alternate,
  leader trade-counter deltas over 60 s):

  | Run | Booked trades/s |
  |---|---|
  | Equity JPM/GS/COF, cold JIT | 6,600 |
  | **Options-only** (3 OCC contracts @ $4.00 premiums, mid-warmup) | **7,626** |
  | Equity JPM/GS/COF, warm | **11,340** |

  The warm figure exceeds the recorded YU13 kind baseline (10,533 booked/s, T-LOB14, same
  parameters and cluster class) — no regression handed to the throughput lane, and option
  contracts cross at full engine speed (the cold→warm spread is JIT, not instrument type).

## Reference data (extract surface)

- `reference-data/instruments.csv` — 26 instruments; type/underlying/expiry/callPut/strike
  derived from the OCC identifier, multiplier, currency USD.
- `reference-data/counterparties.csv` — the 7 real SQL accounts mapped to counterparty +
  netting-set identifiers, currency USD.
- Derived notional documented as position qty x last price x multiplier — agrees with
  in-cluster accounting by construction (reservations are stored already-multiplied).

## Traps confirmed / notes for the next lane

- The multiplied notional caps cannot realistically fire on a LIVE cluster at the shipped
  production limits (credit and order-notional sit at Long.MAX/4) — the behavioral cap proof
  is the JVM suite, where caps are constructor-set. A live demo of the cap firing needs a
  policy-control path to lower limits (none exists by design today).
- Gateway `/metrics` exposes fill/accept counters only; the multiplied notional is not
  surfaced there. The extract's notional is the derived reference-data field.
- One `/seed` call per option contract (each carries its own premium price) — 24 calls for the
  packaged chain, all cold-path.
- The bench harness side-alternation trap carries forward: use an ODD ticker count.

## Lineage reconciliation with YU13 (2026-07-22)

YU14 was cut before the YU13 gateway/GKE campaign. Both halves are now reconciled.

**Code** — cherry-picks `cdb62dc0` (complete gateway batches on the applied high-water fence) and
`77ab0b15` (1 MiB gateway Aeron term buffers) landed on 2026-07-21 but were never regenerated or
tested. Verified 2026-07-22: `generate-state.sh YU14-listed-equity-options` EXIT=0, generated-tree
suite **242 / 0** (the recorded baseline, unchanged), all four allocation gates and both Epsilon
gates (`noGcTest` + `riskNoGcTest`) green. The 4 MiB experiment pair (`2ca90189` → `5273a881`) was
correctly skipped — it cancels.

**Manifests** — hand-merged, not cherry-picked. `gke/gateway.yaml` and `gke/statefulset-emptydir.yaml`
are shadowed at this layer, so a pick would have applied cleanly and changed nothing that runs.
Merged deltas: gateway `replicas: 3 → 4` (`d3fb2016`, supersedes `d39df719`), hard hostname
anti-affinity (`2228e313`), C4D pool selector + `blp-c4d-tuned-pool` (`a90e5be7`), and Guaranteed QoS
through the restore init container (`3b28a61e`). Only the `cluster-node:yu14` image tag now differs
from YU13's copies — verified by diff. Confirmed in the **rendered** output
(`kubectl kustomize` on the generated tree, EXIT=0): `cluster-gateway` replicas 4,
`requiredDuringSchedulingIgnoredDuringExecution` hostname anti-affinity on both workloads,
`nodeSelector: blp-c4d-tuned-pool`, member cpu/memory requests == limits ("3"/4Gi), restore-init
requests == limits (100m/256Mi), and the `:yu14` tag throughout.

**`c4d-node-system-config.yaml` — the brief's trap, corrected.** It was not "inherited but
unreferenced": it was **absent from this branch entirely** (it arrived with `a90e5be7`, which YU14
never received), so it is now copied in — byte-identical to YU13's. It is deliberately **not** added
to `gke/kustomization.yaml`'s `resources`: it is a kubelet config for
`gcloud container node-pools create --system-config-from-file`, not a Kubernetes object, and listing
it fails the entire kustomization with `missing Resource metadata` (verified). A comment in
`kustomization.yaml` now records that, so the file stops reading as an accidental orphan.
