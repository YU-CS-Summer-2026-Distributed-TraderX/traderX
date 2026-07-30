# RECAP — YU14 listed equity options session (2026-07-21)

> Session recap, untracked working note. Home: `traderX-YU14-listed-equity-options`,
> `docs/handoff/`. State complete: 3 commits on branch `YU14-listed-equity-options`
> (`8899d4a` scaffold → `853fd5f` code → `08963b5` proofs/docs), tree clean, **not pushed**.

## What the state is

Listed equity options trade on the YU13 crossing book as ordinary securities. The matching
engine, book, gateway, and run harness are untouched — this is a reference-data and
notional-math state, exactly as the brief framed it. The one substantive engine change: the
risk gate's notional math is now contract-multiplier-aware (a $2.50 option controlling 100
shares consumes $250 of credit/order-notional/concentration budget, not $2.50).

## The design decision (ADR-052/053/054)

**An option's identity IS its unpadded OCC symbol** (`AAPL260918C00240000`). Consequences:

- Underlying, strike, expiry, call/put are *derivable from the identifier* — they never enter
  the consensus log, the snapshot, or any store.
- The **only new cluster state is the contract multiplier**, derived at symbol registration as
  a pure function of the committed ticker (`OccSymbol.multiplierFor`: OCC option → 100, else
  1), identical on every member and replay. Snapshot format 3 carries it per security
  (T_SECURITY column 6, effective value never 0); restore fails closed on multiplier < 1 and
  on legacy format 2.
- Counterparty ID, netting set, and currency are extract-time reference data
  (`specs/YU14-listed-equity-options/reference-data/counterparties.csv` for the 7 real
  accounts, `instruments.csv` for the 26-instrument universe). Derived notional =
  qty x last price x multiplier — agrees with in-cluster accounting by construction because
  reservations are stored already-multiplied.

## Lane boundary honored

`ClusterGatewayMain` has **zero changes** and GKE was never touched. The only wire change is
the SBE symbol-registration ticker field widened 16 → 32 bytes (YU14-layer overrides of the
YU12 schema + codec), which is what lets ~19-char OCC symbols flow through the *existing*
`/seed` and `/orders` endpoints. `BlpRiskState`'s 5-column tuple API is unchanged, so every
non-cluster ancestor (`LmaxEngine` checkpoint path) compiles and behaves bit-identically.

## Files changed (all in the YU14 spec-pack layer)

- `lmax/OccSymbol.java` — new: unpadded OCC parse + multiplier.
- `risk/BlpRiskState.java` (YU03 ancestor) — dense `contractMultiplier[]`; multiplied notional
  in `decideAndReserve`/`decideMarketTrade`/`consume`; multiplied concentration projection
  (bounded by the position cap, no new overflow path); overflow → ORDER_NOTIONAL.
- `cluster/MatchingEngineClusteredService.java` (YU13 ancestor) — multiplier installed at
  `onSymbolRegister`; SNAPSHOT_FORMAT 3; fail-closed T_SECURITY restore.
- `sbe/blp-replication.xml` + `lmax/AeronReplicationCodec.java` (YU12 ancestors) — ticker32.
- Tests: `OccSymbolTest` (new), `BlpRiskStateTest` + `ClusterSnapshotCodecTest` extended.
- Ops: `specs/.../generation/kubernetes/cluster/` (YU13 manifests retagged `cluster-node:yu14`),
  `scripts/yu14/{build-cluster-image,start-cluster-kind,stop-cluster-kind}.sh` (mirror yu12
  trio), `scripts/bench/seed-option-chain.sh` (chain seed + one-cross smoke).
- Five-place pipeline registration + doc sync (CLOUD-ARCHITECTURE, HANDOFF-FOR-TEAMMATE,
  specs/README, catalog).

## Proof (all green)

| Proof | Result |
|---|---|
| Generated-tree suite | **242 / 0** (YU13 carried 228) |
| Epsilon gates | `noGcTest` + `riskNoGcTest` pass |
| Allocation gates | all four, executed fresh (`--rerun-tasks`) |
| The substantive test | qty 200 x $100 passes as equity, **rejects ORDER_NOTIONAL as a 100-multiplier option**; credit walls after exactly 10 multiplied reservations; concentration fires on the multiplied projection; unset multiplier ≡ 1 (equity bit-identical to YU13) |
| Snapshot | multiplier round-trips format 3 via the real ingress registration path; option cross identical on source vs restored member; multiplier 0 and format 2 fail closed |
| Live kind smoke | 24-contract chain seeded through unchanged `/seed`; option cross booked both sides (fills 0 → 2) on `AAPL260918C00240000` @ $3.80, fresh epoch |

## Bench (no confound handed to the throughput lane)

In-cluster bench pod, conc 48 x batch 200, SIDES=alternate, booked/s from the leader
trade-counter delta over 60 s, 3 gateways spread one per worker:

| Run | Booked/s |
|---|---|
| Equity (JPM/GS/COF), cold JIT | 6,600 |
| Options-only (3 OCC contracts @ $4 premiums, mid-warmup) | 7,626 |
| Equity, warm | **11,340** |

Warm exceeds the recorded YU13 kind baseline (10,533, T-LOB14, same parameters) — **no
regression**, and options cross at full engine speed. The cold→warm spread is JIT, not
instrument type.

## Traps hit / notes for the next lane

- **The silent-reject gate was exercised first**, per the brief: `seed-option-chain.sh` runs
  the chain seed + one-cross smoke before anything else, and fails loudly with the triage hint
  if orders 200 but nothing fills.
- **Multiplied caps cannot fire on a live cluster at shipped limits** (credit/order-notional =
  Long.MAX/4). The behavioral cap proof is JVM-level, where caps are constructor-set. A live
  cap demo would need a policy-control endpoint that deliberately doesn't exist today.
- Gateway `/metrics` has fill/accept counters only — multiplied notional is not surfaced
  anywhere live; the extract's notional is the derived reference-data field.
- One `/seed` call per contract (each carries its own premium; band anchors on first limit —
  always seed a fresh epoch at real premium scale).
- Odd-ticker-count bench trap carries forward (side alternation by index).
- The kind gateway kustomization does NOT include `gateway.yaml` — apply it separately (the
  yu14 start script inherited this from yu12; bring-up here did it manually).

## Open / next

- **Blocks the risk-extract state** (Alex's EOD positions/marks/P&L feed) — build it against
  this instrument model. Open question from the brief still pending: whether
  `accountId → counterparty` suffices for his netting/CSA logic or he needs a counterparty
  entity with its own attributes.
- Kind cluster `kind-traderx-yu12-cluster` left running with YU14 (3 members + 3 gateways,
  chain seeded) — `scripts/bench/seed-option-chain.sh` re-runs the smoke;
  `scripts/yu14/stop-cluster-kind.sh` tears it down.
- GKE packaging (image `--platform linux/amd64`, kustomize gke overlay) exists in the spec
  pack but was not deployed — the parallel lane owns the GKE cluster.
