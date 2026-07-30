# Data Model: YU02-lmax-kubernetes

## State-Level Entities

- `YU02-lmax-kubernetes` state pack
- inherited `014` Kubernetes/C3/FDC3 runtime artifacts
- ported `YU01` LMAX matcher/Gateway/runtime override set
- Kubernetes storage artifacts for journal, snapshot, and checkpoint data
- implementation-status and port-matrix records

## Persistent Runtime Concerns

- journal stream
- snapshot files
- projection checkpoints
- readiness state during replay and warm-up
