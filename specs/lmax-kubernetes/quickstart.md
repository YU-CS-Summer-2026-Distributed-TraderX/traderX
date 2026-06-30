# Quickstart: lmax-kubernetes

This state now generates a real overlay on top of `014`, but it is still an in-progress LMAX-on-Kubernetes port.

Generate the state:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
```

Run the current inherited runtime harness:

```bash
bash generated/code/target-generated/scripts/start-state-lmax-kubernetes-generated.sh --provider kind --without-sail
```

Check the generated state contract:

```bash
bash scripts/test-state-lmax-kubernetes.sh
```

Validated closeout path:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
bash generated/code/target-generated/scripts/test-state-lmax-kubernetes.sh
```

Current runtime notes:

- The runtime still reuses the `014` lifecycle harness.
- `order-matcher` now carries LMAX-specific actuator readiness and persistent journal/snapshot storage definitions.
- Postgres remains the durable persistence baseline.
- JIT warm-up replay is still deferred, so readiness is currently recovery-gated rather than warm-up-gated.
