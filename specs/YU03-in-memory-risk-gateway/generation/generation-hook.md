# Generation Hook: YU03-in-memory-risk-gateway

- Hook script: `pipeline/generate-state-YU03-in-memory-risk-gateway.sh`
- Render script: `pipeline/render-state-YU03-in-memory-risk-gateway.sh`
- Feature pack: `specs/YU03-in-memory-risk-gateway`
- Parent state: `YU02-lmax-kubernetes`
- Overlay model: generate parent (which itself renders onto `014-fdc3-intent-interoperability`),
  then overlay this state's `generation/runtime-overrides/` onto the shared component tree —
  the same per-file overlay mechanism `YU02-lmax-kubernetes` uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU03-in-memory-risk-gateway`.
2. Generate parent `YU02-lmax-kubernetes` from a clean target root.
3. Overlay the order-matcher risk-gateway overrides (risk package, control events, screening,
   snapshot v3, control-plane REST surface, bootstrap, config, tests).
4. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU03-in-memory-risk-gateway`.
5. Inherit everything else (runtime harness, manifests, GKE deploy scripts, observability
   stack) unchanged from `YU02-lmax-kubernetes`.

## Build / verify

```bash
bash pipeline/generate-state.sh YU03-in-memory-risk-gateway
(cd generated/code/target-generated/order-matcher && ./gradlew test)
```

Deploy uses the inherited `YU02-lmax-kubernetes` GKE scripts/CI (the state changes only the
order-matcher image content).
