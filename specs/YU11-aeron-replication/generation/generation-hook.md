# Generation Hook: YU11-aeron-replication

- Hook script: `pipeline/generate-state-YU11-aeron-replication.sh`
- Render script: `pipeline/render-state-YU11-aeron-replication.sh`
- Feature pack: `specs/YU11-aeron-replication`
- Parent state: `YU10-fix-ingress`
- Overlay model: generate YU10 and its complete YU02…YU09 ancestry, then apply YU11
  `generation/runtime-overrides/` last-wins to the shared component tree and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Delegate direct invocation through `pipeline/generate-state.sh YU11-aeron-replication`.
2. Generate parent `YU10-fix-ingress` from a clean target root.
3. Overlay YU11 order-matcher, sidecar, Kubernetes, compose, and proof assets.
4. Copy the complete state pack, including generated architecture and ADRs, into
   `generated/code/target-generated/YU11-aeron-replication/spec-source/`.
5. Install YU11 start/stop/status/test wrappers in the generated scripts directory.

## Shared-override surface

YU11 uses full-file overlays, so every shared file carries all ancestor behavior forward:

- `order-matcher/build.gradle`: YU09 isolated allocation tasks plus YU10 QuickFIX/J dependency,
  with Aeron/Agrona/SBE generation added.
- `order-matcher/.../lmax/LmaxEngine.java`: YU03 risk, YU04 control feed, YU05 entitlement,
  YU09 health/archive, YU10 FIX handler registration, plus YU11 transport/watermark/failover
  wiring.
- `order-matcher/.../lmax/Journaler.java`: inherited force/journal/archive behavior plus the exact
  follower watermark exposure used by the ACK agent.
- `order-matcher/application.properties`: every inherited property plus YU11 transport/policy
  defaults.
- Kubernetes order-matcher StatefulSet/Services: inherited probes, credentials, archive, FIX,
  resources, and anti-affinity plus the sidecar/UDP/Secret/NetworkPolicy/Archive wiring.
- build/deploy files: every YU10 image and release mapping plus the sidecar image/schema identity.

Generation verification checks markers from every ancestor that owns each shared file, then YU11
markers (`BLP_REPLICATION_TRANSPORT`, `AeronReplicator`, SBE schema checksum, sidecar image,
fast-witness mode).
