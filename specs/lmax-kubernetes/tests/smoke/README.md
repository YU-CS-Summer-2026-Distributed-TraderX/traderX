# Smoke Tests: lmax-kubernetes

Current status: generated-state contract smoke implemented in `scripts/test-state-lmax-kubernetes.sh`.

Verified closeout commands:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
bash generated/code/target-generated/scripts/test-state-lmax-kubernetes.sh
```

Most recent result:

- generation completed successfully
- generated-state contract smoke passed
- runtime harness delegates to `014-fdc3-intent-interoperability` with lmax-kubernetes overlays applied

Smoke split:

1. inherited `014` Kubernetes/C3/FDC3 regression checks
2. generated-state contract checks for:
   - MariaDB-backed matcher/trade-service overlays (`jdbc:mariadb://`)
   - LMAX readiness probe wiring (`lmaxRecovery` in readiness group)
   - snapshot interval, journal batch records, BLP terminal retain configuration
   - persistent `order-matcher` storage manifest presence (PVC + deployment mount)
3. live LMAX replay/readiness checks once a cluster is running (`TRADERX_RUN_LIVE_CHECKS=1`)
4. messaging and UI regression checks (future)
