# Generation Hook: YU12-aeron-cluster

- Hook script: `pipeline/generate-state-YU12-aeron-cluster.sh`
- Render script: `pipeline/render-state-YU12-aeron-cluster.sh`
- Feature pack: `specs/YU12-aeron-cluster`
- Parent state: `YU11-aeron-replication`
- Overlay model: generate YU11 and its complete YU02…YU10 ancestry, then apply YU12
  `generation/runtime-overrides/` last-wins to the shared component tree and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Delegate direct invocation through `pipeline/generate-state.sh YU12-aeron-cluster`.
2. Generate parent `YU11-aeron-replication` from a clean target root.
3. Overlay YU12 order-matcher cluster assets.
4. Copy the complete state pack, including generated architecture and ADRs, into
   `generated/code/target-generated/YU12-aeron-cluster/spec-source/`.
5. Install YU12 start/stop/status/test wrappers in the generated scripts directory.

## Shared-override surface

YU12 uses full-file overlays, so every shared file must carry all ancestor behavior forward:

- `order-matcher/build.gradle`: the complete YU11 copy (YU09 isolated allocation tasks, YU10
  QuickFIX/J, YU11 Aeron/Agrona/SBE generation) plus the Aeron Cluster dependency.
- New cluster source/test files under `finos/traderx/ordermatcher/cluster/` have no ancestor
  versions and carry no clobber risk.

Generation verification checks markers from every ancestor that owns each shared file, then the
YU12 markers (`aeron-cluster` dependency, `ClusteredService` hosting class, cluster spike test).
