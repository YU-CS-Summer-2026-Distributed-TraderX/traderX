# Smoke Tests: lmax-kubernetes

Current status: initial generated-state contract smoke is implemented in `scripts/test-state-lmax-kubernetes.sh`.

Verified closeout commands:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
bash generated/code/target-generated/scripts/test-state-lmax-kubernetes.sh
```

Most recent result:

- generation completed successfully
- generated-state contract smoke passed
- runtime harness still delegates to `014-fdc3-intent-interoperability`

Smoke split:

1. inherited `014` Kubernetes/C3/FDC3 regression checks
2. generated-state contract checks for:
   - Postgres-backed matcher/trade-service overlays
   - LMAX readiness probe wiring
   - persistent `order-matcher` storage manifest presence
3. live LMAX replay/readiness checks once a cluster is running
4. messaging and UI regression checks after the trading-path port lands
