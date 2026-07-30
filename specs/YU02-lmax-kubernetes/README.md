# Feature Pack: YU02-lmax-kubernetes

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Draft  
Track: `architecture`  
Lineage role: `optional`  
Previous state: `014-fdc3-intent-interoperability`

This pack defines the Kubernetes-native continuation of the LMAX trading line on top of the latest
Kubernetes/C3/FDC3 runtime line.

Primary intent:

- preserve the `014-fdc3-intent-interoperability` Kubernetes/C3/FDC3 platform baseline,
- forward-port the `YU01-lmax-sequencer` hot-path and service-role changes onto that baseline,
- create a durable state home for cloud deployment work,
- keep inherited frontend/FDC3 behavior unless the LMAX port requires an explicit contract change.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `generation/generation-hook.md`
- `generation/port-matrix.md`
- `generation/implementation-status.md`
- `tests/smoke/README.md`

Target runtime behavior:

- Kubernetes/C3 runtime inherited from `014-fdc3-intent-interoperability`.
- LMAX matcher/Gateway/runtime semantics inherited from `YU01-lmax-sequencer`.
- Stateful Kubernetes treatment for journal, snapshot, replay, and warm-up readiness.
- Honest scaffold-first generation until the real forward port lands.
