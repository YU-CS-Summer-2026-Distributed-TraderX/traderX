---
title: "State YU09-ops-hardening: Ops Hardening"
---

# State YU09-ops-hardening Learning Guide

## Position In Learning Graph

- Previous state(s): [YU08-execution-algo-engine](/docs/learning/state-YU08-execution-algo-engine)
- Dotted-line parent(s): none
- Next state(s): [YU10-fix-ingress](/docs/learning/state-YU10-fix-ingress)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU09-ops-hardening](https://github.com/finos/traderX/tree/code/generated-state-YU09-ops-hardening)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU08-execution-algo-engine`: [code/generated-state-YU08-execution-algo-engine...code/generated-state-YU09-ops-hardening](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU08-execution-algo-engine...code%2Fgenerated-state-YU09-ops-hardening)

## Plain-English Code Delta

- **Added:** `mariadb-credentials` and `auth-secrets` Kubernetes Secrets, created out-of-band and never committed, so a repo checkout never exposes a working credential.
- **Added:** Journal rotation at every snapshot boundary when `journal.archive.enabled` is true (`Journaler.rotate()`), closing the active file off as an immutable, timestamped segment.
- **Added:** A `JournalArchiver` that uploads each closed segment to the GCS bucket named by `journal.archive.bucket`, so journal history survives loss of the pod's own volume.
- **Added:** HMAC-authenticated uploads over GCS's S3-compatible XML API — the same interoperability mode the tick-store capture already uses.
- **Added:** A dedicated background upload thread, so the journaler thread servicing the input Disruptor ring never waits on network I/O.
- **Added:** A closed segment that fails to upload, kept on local disk — deletion follows only a confirmed upload, so archival never loses journal data.
- **Added:** A `journal.archive.enabled` flag defaulting to `false`, so the shipped default reproduces the parent state's single growing journal file exactly.
- **Added:** An optional `order-matcher-journal-gcs-hmac` Secret whose absence disables only the upload leg — pod startup and journal rotation are unaffected.

## Run This State

```bash
./scripts/start-state-YU09-ops-hardening-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU09-ops-hardening](/specs/YU09-ops-hardening)
- Architecture: [/specs/YU09-ops-hardening/system/architecture](/specs/YU09-ops-hardening/system/architecture)
- Flows / topology: [/specs/YU09-ops-hardening/system/runtime-topology](/specs/YU09-ops-hardening/system/runtime-topology)
- Research: [link](/specs/YU09-ops-hardening/research)
- Data model: [link](/specs/YU09-ops-hardening/data-model)
- Quickstart: [link](/specs/YU09-ops-hardening/quickstart)

