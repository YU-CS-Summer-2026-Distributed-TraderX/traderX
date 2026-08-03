# Generation Hook: YU13-limit-order-book

- Hook script: `pipeline/generate-state-YU13-limit-order-book.sh`
- Render script: `pipeline/render-state-YU13-limit-order-book.sh`
- Feature pack: `specs/YU13-limit-order-book`
- Parent state: `YU12-aeron-cluster`
- Overlay model: generate YU12 and its complete YU11…YU02 ancestry, then apply YU13
  `generation/runtime-overrides/` last-wins to the shared component tree and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Delegate direct invocation through `pipeline/generate-state.sh YU13-limit-order-book`.
2. Generate parent `YU12-aeron-cluster` from a clean target root.
3. Overlay YU13 order-matcher crossing-book assets.
4. Copy the complete state pack, including generated architecture and ADRs, into
   `generated/code/target-generated/YU13-limit-order-book/spec-source/`.
5. Inherit the YU12 runtime harness (start/stop/status/test wrappers) unchanged — YU13 changes
   the matching policy inside the service, not the process, deploy, or runtime surface.

## Shared-override surface

YU13 uses full-file overlays, so every shared file must carry all ancestor behavior forward.
The overridden files and their youngest ancestor owner:

- `order-matcher/build.gradle` — YU12 copy (all YU09/YU10/YU11 build behavior plus the Aeron
  Cluster dependency), with the base allocation gate's C2-only pinning (`-XX:-TieredCompilation
  -XX:CompileThreshold=10000`) extended to match the aeron/cluster gates.
- `lmax/MatchingEngine.java` — reworked from the YU12 copy into the crossing engine (carries the
  YU12 `OutputPublisher` drain override contract and the risk/idempotency/terminal-retention
  behavior forward).
- `lmax/OutputEvent.java`, `lmax/RestingOrder.java` — extended from the YU03 copies (add
  `FLAG_RESTING_UPDATE`, and the book-membership links / `isResting()` respectively).
- `cluster/MatchingEngineClusteredService.java`, `cluster/ClusterGatewayMain.java` — reworked
  from the YU12 copies (snapshot format 2, resting-class egress byte, ack correlation).
- Test overrides `lmax/AllocationGateTest.java`, `lmax/HotPathBannedApiTest.java`,
  `lmax/OutputDisruptorHandlersTest.java`, `lmax/LmaxHotPathParityTest.java`,
  `cluster/AeronClusterSpikeTest.java`, `cluster/ThreeMemberClusterTest.java`,
  `cluster/ClusterSnapshotCodecTest.java` — reworked from their youngest-ancestor copies to
  crossing semantics, preserving each test's original proof intent.
- New file `lmax/LimitBook.java` and new tests `lmax/LimitOrderBookTest.java`,
  `lmax/MatchLatencyBenchmarkTest.java` have no ancestor versions and carry no clobber risk.

Generation verification checks markers from every ancestor that owns each shared file, then the
YU13 markers (`LimitBook` class, `FLAG_RESTING_UPDATE`, `SNAPSHOT_FORMAT = 2`).
