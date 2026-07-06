# Architecture: YU02-lmax-kubernetes

`YU02-lmax-kubernetes` is intended to be the point where the `009b` LMAX business-path architecture and the
`014` Kubernetes/C3/FDC3 platform line meet.

At a high level:

- inherit deployment substrate, ingress, cluster tooling, and frontend interop scaffolding from `014`
- port `009b` matcher/Gateway/runtime behavior into the corresponding services on that substrate
- model the hot-path node as a stateful Kubernetes workload with replay and warm-up semantics

This first scaffold intentionally documents the intended architecture before the runtime assets are ported.
