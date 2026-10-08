# STP image provenance specification

Status: local implementation and tests. This is build/proof apparatus, not a matching-engine feature or live deployment claim.

## Requirements

- FR-SIP01: Admission compares build-input bytes, paths and executable bits, never mtimes or Docker Created. Dirty source bytes count independently of the recorded Git revision.
- FR-SIP02: Fingerprint the complete generated order-matcher context except top-level `build`, `.gradle`, and `.git` outputs/administration. Include Dockerfile, dependency declarations/locks/local inputs, wrapper, resources, schemas, sources and build scripts. Reject symlinks and special files.
- FR-SIP03: Bind generation composition, build recipe and selected strict revert transformation to each role. Include pipeline programs, canonical wrapper assets, dependency targets, mixed-component generation patchsets and order-matcher runtime layers. Include atomic patchsets conservatively; a change to an unrelated hunk in one such patchset also requires rebuilding. Component prose, issues, proof-runner cleanup, deployment manifests and unrelated runtime trees are outside the build fingerprint.
- FR-SIP04: Compile private pre/fix snapshots through one recipe (`./gradlew --no-daemon -q clean bootJar`). Strictly apply the appropriate pre patch with zero fuzz. Refuse input movement while preparing/building, absent jars, or identical transformed roles. YU18 ownership-group decisions select the YU18 patch; older decisions keep the legacy behavioral removal; a trailing route context line is narrowed so current inherited gateways apply with zero fuzz.
- FR-SIP05: Record schema, role, selected/role/recipe/composition/patch hashes, selected patch path, source revision, host Java and Gradle-properties identity, platform, immutable base references, boot-jar hash and packaged classes/resources/dependencies digest in the image's `dev.traderx.stp.provenance` label. Pin external Dockerfile FROM references to inspected repository digests. A Git SHA is historical context, not proof of the working-tree bytes.
- FR-SIP06: Through actual proof/builder entrypoints, refuse absent/malformed/unknown provenance, wrong role, transformed-content mismatch, changed inputs, unavailable images/bases, changed host inputs and packaged-payload mismatch. Validate the packaged payload using the inspected immutable image ID and stopped private containers (`create`/`cp`/`rm`); never run the image during admission.
- FR-SIP07: Leave STP/replace proof assertions and builder marker/class-difference checks intact. No admission bypass, mtime fallback, or forced no-cache workaround. Identical input bytes produce deterministic fingerprints; cached configs with old Created timestamps pass.
- NFR-SIP01: No shared image/node cleanup, retained-rig mutation, cloud, propagation or Git push. Temporary snapshots and private copy containers are cleaned up on ordinary success/failure. An uncatchable host/process death can leave an owned stopped copy container; it cannot start the engine.

## Provenance and limits

This is a local builder attestation, not a signed producer or supply-chain authentication scheme. Resolved dependency bytes shipped in the boot jar are recorded and rechecked against the image. Admission does not re-resolve Maven, prove upstream repository immutability, or financially validate dependencies. Source declarations, local dependency inputs and generation recipes remain content-bound. External base tag movement deliberately refuses until rebuilt; recorded repository digests identify the bases used.

The guard concerns locally resolved images and selected source. Node resolvability, deploy-time tag races and general GKE provenance are RI-20. Epoch cleanup/recovery is RI-17. A synthesized pre/fix pair crosses a behavioral boundary; historical retained-format recovery remains separate. Tests using fake tools are source/process evidence. A scratch-image roundtrip proves actual Docker labeling, cache and packaged-byte verification only; its synthetic jar is not compiled production Java.

## Acceptance

SC-SIP01: unchanged sources with newer mtimes pass; changed bytes with original mtimes fail through actual proof admission.
SC-SIP02: Dockerfile, resource, dependency/build configuration, wrapper, generation recipe, patch, mode and role changes refuse.
SC-SIP03: absent/malformed/wrong-role provenance and changed packaged dependency bytes refuse. Positive controls reach the post-admission kind-load sentinel; negative controls make no Kubernetes calls.
SC-SIP04: actual builder dispatch attaches labels, checks payloads and preserves behavioral pair checks. Broken transforms refuse before building; identical inputs require no no-cache option.
SC-SIP05: real disposable Docker image passes same-content/new-mtime/cache checks and refuses wrong role and changed bytes. Report its synthetic-input limitation.
