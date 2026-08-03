# Generation Hook: YU05-post-trade-compliance

- Hook script: `pipeline/generate-state-YU05-post-trade-compliance.sh`
- Render script: `pipeline/render-state-YU05-post-trade-compliance.sh`
- Feature pack: `specs/YU05-post-trade-compliance`
- Parent state: `YU03-in-memory-risk-gateway`
- Overlay model: generate parent (which itself renders onto `YU02-lmax-kubernetes` →
  `014-fdc3-intent-interoperability`), then overlay this state's `generation/runtime-overrides/`
  onto the shared component tree — the same per-file overlay mechanism every prior state in this
  lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU05-post-trade-compliance`.
2. Generate parent `YU03-in-memory-risk-gateway` from a clean target root.
3. Overlay the order-matcher trade-blotter/recon overrides (`TradeOrder` id fix, `TradeBlotter`,
   `TradeBlotterHandler`, `ReconController`, config, tests) and the trade-processor settlement/recon
   overrides (`TradeService` idempotency, `SettlementService`, `ReconciliationService`, controllers,
   config, tests).
4. Overlay the k8s database-init ConfigMap with the added `settlementdate` column (see
   research.md's "generation pipeline gotcha" note — the real runtime schema is the ConfigMap under
   `kubernetes-runtime/manifests/base/`, not `database/initialSchema.sql`).
5. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU05-post-trade-compliance`.
6. Inherit everything else (runtime harness, manifests, GKE deploy scripts, observability stack)
   unchanged from `YU03-in-memory-risk-gateway`.

## Build / verify

```bash
bash pipeline/generate-state.sh YU05-post-trade-compliance
(cd generated/code/target-generated/order-matcher && ./gradlew test)
(cd generated/code/target-generated/trade-processor && ./gradlew test)
```

Deploy uses the inherited `YU03`/`YU02` GKE scripts/CI (the state changes only order-matcher and
trade-processor image content, plus the database init ConfigMap).
