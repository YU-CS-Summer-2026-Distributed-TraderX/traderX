# STP image provenance

Status: integrated locally, 2026-10-07; see the evidence and limits below.
Owner: RI-18 Codex image-freshness lane.

The STP boundary builder records content provenance on its pre/fix images. The proof checks that provenance and packaged payload before contacting the rig. Identical regeneration and Docker cache hits are admissible; source timestamps have no role.

Sources: `scripts/yu15/build-stp-boundary-images.sh`, `scripts/yu15/stp-image-provenance.py`, `scripts/yu15/stp-boundary-revert*.patch`, and admission in `scripts/proofs/yu13-stp-and-replace.sh`. These repository scripts are authoritative; no runtime override copies exist. Shared state generation remains in the parent pack.

Run offline controls:

```sh
python3 scripts/tests/stp-image-provenance/test_provenance.py
```

Optional real Docker label/payload control, on one uniquely named scratch image with synthetic compiler inputs:

```sh
python3 scripts/tests/stp-image-provenance/real_image_roundtrip.py
```

Build the actual pair after generating the intended state:

```sh
bash scripts/yu15/build-stp-boundary-images.sh
```

Images built before this contract must be rebuilt. `STP_PRE_TAG`/`STP_FIX_TAG` and `IMAGE_PRE`/`IMAGE_FIX` retain their existing meanings. The helper requires Python 3, strict `patch`, Java, Gradle wrapper dependencies, and Docker. External base images must be locally inspectable with immutable repository digests; fetch them deliberately if absent. Ambient Gradle init scripts and JVM/platform injection are refused. This component does not grant rig preparation, reset, deployment or recovery authority.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), and the [state component contract](../../../../docs/spec-kit/state-components.md).

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
