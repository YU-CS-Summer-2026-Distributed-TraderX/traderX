# Projector heap-OOM exit specification

Owner: Codex RI24. Status: implemented and locally verified; coordinator review pending, 2026-10-07. Dependencies: Java 21 HotSpot container runtime, explicit YU18 generation and rebuilt service images for image-launcher changes.

## Requirements

- FR-POE-01: The generated YU18 trade-processor plain and Compose image launchers include `-XX:+ExitOnOutOfMemoryError` before `-jar`. A real JVM heap OOM exits with a nonzero process status before an application catch block can continue.
- FR-POE-02: The YU18 full-runtime deployment preserves inherited `-XX:MaxRAMPercentage=75` and adds the exit flag. All other deployment fields remain equal to its YU12 ancestor.
- FR-POE-03: The current YU18 GKE demo strategic patch adds the exit flag through `JAVA_TOOL_OPTIONS`, sufficient with the original pinned image's `java -jar` entrypoint. The image digest and other patch fields remain unchanged. This source baseline has no prior JVM env options; operators must review any live custom options before applying a patch.
- NFR-POE-01: Source and generated inputs agree byte for byte. Earlier state inputs remain unchanged. No memory sizing, probes, core, schema, feed or recovery changes accompany the flag.
- SC-POE-01: A disposable JVM with a 16 MiB heap catches a real heap allocation OOM and reaches `POST_CATCH_ALIVE` without the flag; the same fixture using effective launch arguments/env terminates nonzero with the heap-OOM diagnostic and reaches neither catch nor post-catch marker.
- SC-POE-02: A caught ordinary application exception still reaches the post-catch marker with the flag enabled.

## Scope and interfaces

The runtime interface is a JVM option in the image exec entrypoint and/or deployment environment. No new application/wire interface is introduced. Existing Compose and kind/GKE container commands do not override the image entrypoint; tests render their effective configuration. Raw YU17 cluster manifests retain their historical env and image references. The launcher fix requires a newly built YU18 trade-processor image selected through existing deployment procedures. The GKE demo env patch also supports its current pinned image without relying on a rebuild.

Bare host `java -jar`, Gradle `bootRun`, test child JVMs and external custom launchers are outside this container-launch slice. JVM heap OOM is distinct from OS/container memory kill, native allocation failure and arbitrary application faults. Process exit permits a supervisor to observe termination; it does not prove a restart, successful recovery, bounded memory or recovery of missing NATS events. RI24 and RI27 remain open.

## Executable acceptance

Generate `YU18-risk-integration`, then select JDK 21 using `JAVA_HOME` and run `python3 specs/YU18-risk-integration/tests/projector-oom-exit/test_projector_oom_exit.py`. Tests require Ruby/Psych, kubectl for offline Kustomize, and Docker Compose for offline `config`. Each owned JVM has a 15-second timeout and fixed 16 MiB heap; no container, daemon, retained rig or cluster is started. Failure or missing prerequisites must produce a nonzero test result.
