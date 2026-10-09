# Generation derivation specification

Status: local implementation and acceptance complete; review/integration pending. Owner: Codex RI20-GENERATION-DERIVATION. Parent issue/task/index changes are coordinator-owned proposals. No live rig is required to qualify this pre-build source guard; LR05 remains separate.

## Requirements

- FR-GD01: Before any selected builder compiles or performs image operations, require its complete generated component context to equal independently generated current selected-state output. Include file/directory membership, executable bits and bytes; exclude only the accepted component build/.gradle/.git outputs from context identity. Missing, deleted, added, injected or stale inputs refuse.
- FR-GD02: Generate through the actual recursive state pipeline in an owned source snapshot, covering full ancestry, templates, patches, runtime overrides, pruning and wrapper/dependency composition. Current dirty source is authoritative. YU18 leaf parity alone is insufficient. Capture current source inputs before/after copying and after generation; source changes refuse. No supplied derivation/preparation manifest is authoritative.
- FR-GD03: Keep checkout generated files and source docs unchanged during admission. Reference generation has its own source, Git administration/index and generated outputs. Local shallow HEAD-object transfer provides revision metadata without overwriting dirty snapshot bytes; source byte identity, not the revision label, controls admission.
- FR-GD04: Admit all requested service components before the first service build. Cluster/service compilation uses only the private admitted contexts, with no copied prior build/cache outputs. Default build state remains compatible with existing state-pack selection; explicit catalog ancestor selection and the YU18 legacy alias are supported. Tag/pinning policy is unchanged.
- FR-GD05: Preserve `OM_DIR` using explicit `OM_ROLE=fix|pre`. Independently reproduce the known STP transform with the accepted patch; arbitrary patched copies refuse. STP prepare and each build role also independently validate derivation, including direct helper calls with caller-forged prepared manifests. Preserve the RI18 image provenance schema and packaged source/payload/role assertions.
- NFR-GD01: The reference is local and private. Offline npm resolution must fail closed when installed/cached dependencies are insufficient. No Docker, Gradle compilation, Kubernetes/GKE/cloud/image-tag or retained-data mutation is needed to qualify the guard. Actual operator builder invocations retain their existing image operations only after admission.
- NFR-GD02: Never infer deployment/runtime correctness, production identity, financial validity or retained compatibility from derivation. No automatic render into canonical, cache receipt blessing, tag repair or integration occurs.

## Input and evidence contract

The helper's `check` and `execute` actions select nonempty unique component names and a known catalog state. Optional candidate/role selection is limited to order-matcher's supported fix/pre relation. `execute` exposes the private admitted root to the fixed build body via `DERIVED_BUILD_ROOT`; an inherited caller value cannot replace it. A supplied `--evidence` directory receives the receipt and private generation log only after successful admission, and is not read to authorize later work.

`traderx-generation-derivation-v1` records canonical state, ordered catalog ancestry, conservative source-input SHA256 and file count, role, per-component context hashes and generation log hash. Hashes identify the measured source/render relation; they do not authenticate arbitrary third-party generators or producers. The repository's current pipeline/program bytes are trusted executable source. No preexisting receipt/cache is consulted.

Source inventory excludes directories `.git`, `generated`, `node_modules`, `.gradle`, `build`, `__pycache__`, `.parent-src`, `.run` at any depth. These are not accepted derivation dependencies: the reference is created without them and generation must still succeed. Symlinks and other unsupported objects refuse. The actual pipeline's source-root writes occur only in the private snapshot. Its environment clears inherited `TRADERX_*` generation controls, pins its own output root and skip-lockfile-refresh profile and enforces offline npm resolution. The resulting context must still equal the supplied selected context, so a different generation policy fails rather than being declared equivalent.

Compilation does not establish this relation. The independent generator must finish successfully, component population must be nonempty/present, and both candidate and current source must remain unchanged through admission. Build outputs are not copied into ordinary private build contexts. Generated omissions cannot be patched by recording current composition next to old bytes.

## Scenarios

- SC-GD01: Current fresh contexts and unchanged sources with differing mtimes/cached outputs admit through actual cluster/service/STP entrypoints.
- SC-GD02: Leaf owner changes plus rebuild without render, stale ancestor despite leaf parity, template/pipeline changes, owner additions/removals and pruning refuse before external build effects; full render from current dirty owners then admits.
- SC-GD03: Missing/deleted generated inputs and injected files refuse. A stale later requested service refuses before an earlier service compiles/builds.
- SC-GD04: Explicit inherited build state and validated fix/pre overrides admit. Unknown custom transform, absent explicit override role, forged prepared manifest and forged private-context environment do not bypass the guard.
- SC-GD05: An original-builder omission control reaches Gradle/Docker on demonstrably stale owner/generated bytes, proving the negative fixture discriminates.
- SC-GD06: Actual full YU18 ancestry generation and independent reference comparison cover order-matcher and the three selected services. Persist nonempty ancestry/source/context hashes and source/render evidence. Synthetic generator controls do not substitute for this real pipeline witness.
