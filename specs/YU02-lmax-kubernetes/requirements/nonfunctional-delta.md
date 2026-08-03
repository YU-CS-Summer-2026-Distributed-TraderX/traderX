# Non-Functional Delta: YU02-lmax-kubernetes

- keep Postgres as the inherited durable database baseline
- add Kubernetes-aware statefulness for journal, snapshot, and replay
- preserve deterministic generated outputs
- keep observability and readiness first-class in the LMAX runtime
- separate scaffold readiness from true runtime readiness
- expose actuator liveness/readiness probes that include LMAX recovery state
- mount persistent storage for `order-matcher` journal/snapshot files instead of ephemeral pod-only storage
- keep warm-up semantics explicit: recovery-gated readiness is implemented now, JIT warm-up replay remains deferred
