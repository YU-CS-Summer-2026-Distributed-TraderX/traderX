# Replay anchor status specification

Status: implemented locally; review pending. Scope: RI21 replay target, storage evidence, status
and checked command failures. This does not implement managed identity or engine/SQL recovery.

## Requirements

- FR-RA-01: Require an explicit context and namespace in a supported kubectl prefix before any
  anchor query/write; reject malformed/unknown prefixes without ambient target selection.
- FR-RA-02: Verify the selected member-0 cluster-node `/data` mount uses the documented writable
  PVC, with Bound claim/volume and matching PV claimRef name/namespace/UID. Refuse missing,
  unreadable, unused, terminating, invalid or ambiguous evidence before configuration mutation.
- FR-RA-03: Derive `epochStartMs` only from that claim's valid UTC creationTimestamp. Identical
  evidence produces identical milliseconds. Describe this as storage creation evidence.
- FR-RA-04: Permit an explicit disabled choice with a distinct status and no query/write. Never
  infer disabled stamping, synthetic behavior or tape state from failed inspection.
- FR-RA-05: Check dry-run, apply, producer inspection, restart and rollout independently. Do not
  pipeline away an earlier failure. Distinguish stored anchor from producer availability/rollout.
- FR-RA-06: Required-mode failure must propagate through existing caller paths, including proof
  EXIT cleanup; preserve an earlier nonzero proof exit. The after replay proof must refuse
  disabled stamping before queries/mutations. Fetch-secret behavior remains unchanged.
- NFR-RA-01: No real rig/cloud/vendor data in acceptance. Tests use offline command fixtures.

## Acceptance mapping

`test_anchor.py` executes the sourced production function. Prefix/ambient/wrong-target controls
cover FR-RA-01. Missing/failed reads, emptyDir/unused claim and invalid storage controls cover
FR-RA-02. Exact positive/idempotent and malformed/fractional timestamp controls cover FR-RA-03.
Disabled controls cover FR-RA-04. Dry-run/apply/restart/rollout and absent-producer controls cover
FR-RA-05. Full kind-start plus verbatim rebuild/restore/cleanup source tests and byte comparison
of unchanged fetch functions cover FR-RA-06. Fake-command never delegates, covering NFR-RA-01.

Source acceptance is not evidence of live replay position, generation of an engine identity,
atomic read/write fencing, deployment compatibility or financial correctness.
