# Generation Hook: YU17-otc-rates

- Hook script: `pipeline/generate-state-YU17-otc-rates.sh`
- Render script: `pipeline/render-state-YU17-otc-rates.sh`
- Feature pack: `specs/YU17-otc-rates`
- Parent state: `YU16-cdm-instruments`
- Overlay model: generate YU16 and its complete YU15…YU02 → 014 ancestry, then apply YU17
  `generation/runtime-overrides/` last-wins to the shared component tree, and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Generate the parent chain (`pipeline/generate-state.sh YU16-cdm-instruments`).
2. Overlay `generation/runtime-overrides/` onto `generated/code/target-generated/` (tar-copy,
   file-level, last-wins).
3. Copy the spec pack into `spec-source/`.

This state carries no `reference-data/*.csv` of its own: the extract's join static is inherited
from YU16 untouched, and `counterparties.csv` — which the contracts artifact reads for
`counterpartyId` and `nettingSetId` — is inherited from YU15 untouched. Copying either here would
shadow the operative version for no delta.

## Overridden Files

Every full-file override names the newest ancestor that owned the path — the house defense against
the shadowed-layer trap: an edit applied to a layer this state overrides is silently inert, and an
override that does not start from the operative copy silently reverts that ancestor's work. Each
file below was copied from the ancestor named and then edited.

| File | Ancestor | YU17 delta |
|---|---|---|
| `order-matcher/.../lmax/SwapConventions.java` | new | the compile-time convention table, and the exercise-style table beside it |
| `order-matcher/.../lmax/InputEvent.java` | YU03 | `TYPE_SWAP_BOOK` (12) and `TYPE_SWAPTION_BOOK` (13), the direction aliases, the packed date pair and the option-terms word |
| `order-matcher/.../risk/BlpRiskState.java` | YU14 | `decideSwapBooking` |
| `order-matcher/.../cluster/MatchingEngineClusteredService.java` | YU16 | contract store, `onSwapBook` (both products), `T_CONTRACT`, `MAX_CONTRACTS`, `SNAPSHOT_FORMAT` 6 with by-format restore width, `KIND_SWAP_BOOKED`, `contracts=` on the cut log line |
| `order-matcher/.../cluster/ClusterGatewayMain.java` | YU16 | `POST /swaps` and `POST /swaptions` over one shared validator, pre-consensus term validation, ack correlation |
| `order-matcher/.../cluster/RiskExtractCut.java` | YU15 | cut schema 3, the `#contracts` section and its option columns |
| `order-matcher/.../cluster/RiskExtractCsv.java` | YU16 | stop at the section marker; schema 3 and every column unchanged |
| `order-matcher/.../cluster/SwapContractCsv.java` | new | the per-trade artifact, schema 2, both products |
| `order-matcher/.../cluster/RiskExtractMain.java` | YU16 | render/write/announce both artifacts; optional fourth `--rebuild` argument |
| `order-matcher/.../cluster/RiskExtractGcsSink.java` | YU15 | deliver both fixtures in one call, return both URIs |
| `order-matcher/.../test/.../SwapBookingTest.java` | new | this state's acceptance proofs without a cluster |
| `order-matcher/.../test/.../RiskExtractTest.java` | YU16 | carried forward for the cut-render signature and the position-section filter |
| `order-matcher/.../test/.../RiskExtractGcsSinkLiveProofTest.java` | YU15 | carried forward for the sink signature; asserts the second object lands |

`MatchingEngine.java` is deliberately NOT overridden. A swap is dispatched before the engine's
apply, so the engine's YU16 copy remains operative and byte-identical — which is what makes the
order hot path, the allocation gates and the Epsilon-GC proofs unchanged by construction rather
than by inspection.

## Registration

Five places, each of which fails late and with a message naming the failing stage rather than the
missing registration:

1. `pipeline/generate-state-YU17-otc-rates.sh` + `pipeline/render-state-YU17-otc-rates.sh`
2. `catalog/state-catalog.json` — the `states[]` entry
3. `pipeline/install-generated-runtime-harness.sh` — both `case` blocks
4. `pipeline/install-generated-ci-assets.sh` — the `state_allowed_roots` case
5. `scripts/{start,stop,status}-state-YU17-otc-rates-generated.sh` and
   `scripts/test-state-YU17-otc-rates.sh`
