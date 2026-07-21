# Generation Hook: YU14-listed-equity-options

- Hook script: `pipeline/generate-state-YU14-listed-equity-options.sh`
- Render script: `pipeline/render-state-YU14-listed-equity-options.sh`
- Feature pack: `specs/YU14-listed-equity-options`
- Parent state: `YU13-limit-order-book`
- Overlay model: generate YU13 and its complete YU12…YU02 ancestry, then apply YU14
  `generation/runtime-overrides/` last-wins to the shared component tree and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Delegate direct invocation through `pipeline/generate-state.sh YU14-listed-equity-options`.
2. Generate parent `YU13-limit-order-book` from a clean target root.
3. Overlay YU14 order-matcher instrument-model/risk assets.
4. Copy the complete state pack, including generated architecture and ADRs, into
   `generated/code/target-generated/YU14-listed-equity-options/spec-source/`.
5. Inherit the YU13 runtime harness (start/stop/status/test wrappers) unchanged — YU14 changes
   the instrument model and risk arithmetic inside the service, not the process, deploy, or
   runtime surface.

## Shared-override surface

YU14 uses full-file overlays, so every shared file must carry all ancestor behavior forward.
The overridden files and their youngest ancestor owner:

- `risk/BlpRiskState.java` — extended from the YU03 copy (sole ancestor owner): adds the dense
  per-security `contractMultiplier` array, multiplier-aware notional/concentration math, the
  multiplier column in `securityTuples()`, and the fail-closed `bootstrapSecurity` multiplier
  path. All YU03 decision-pipeline, idempotency, reservation, and snapshot-tuple behavior is
  carried forward.
- `cluster/MatchingEngineClusteredService.java` — extended from the YU13 copy: snapshot format
  3 (6-column T_SECURITY), multiplier derivation in `onSymbolRegister`. All YU13 crossing/ack/
  bridge behavior and all YU12 hosting behavior carried forward.
- `lmax/AeronReplicationCodec.java` — extended from the YU12 copy: 32-byte ticker buffer and
  length check. All YU11/YU12 codec behavior carried forward.
- `sbe/blp-replication.xml` — extended from the YU12 copy: `ticker32` type,
  `SymbolRegisterMessage` blockLength 40.
- `lmax/OccSymbol.java` — new file, owned by this state.
- Test overrides `risk/BlpRiskStateTest.java` (YU03 ancestor),
  `cluster/ClusterSnapshotCodecTest.java` (YU13 ancestor) — extended, preserving every
  inherited proof; new `lmax/OccSymbolTest.java` and cluster option-cross coverage owned here.

After any regeneration, grep the generated `order-matcher` tree for ancestor markers on the
shared files (YU13 `FLAG_RESTING_UPDATE`, YU12 cluster hosting, YU03 `decideAndReserve`,
YU14 `contractMultiplier`) to confirm the overlay carried everything.
