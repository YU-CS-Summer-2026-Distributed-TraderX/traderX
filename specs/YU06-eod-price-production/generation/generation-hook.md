# Generation Hook: YU06-eod-price-production

- Hook script: `pipeline/generate-state-YU06-eod-price-production.sh`
- Render script: `pipeline/render-state-YU06-eod-price-production.sh`
- Feature pack: `specs/YU06-eod-price-production`
- Parent state: `YU05-post-trade-compliance`
- Overlay model: generate parent (which renders onto `YU04-durable-control-feeds` →
  `YU03-in-memory-risk-gateway` → `YU02-lmax-kubernetes` → `014-fdc3-intent-interoperability`),
  then overlay this state's `generation/runtime-overrides/` onto the shared component tree — the
  same per-file overlay mechanism every prior state in this lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU06-eod-price-production`.
2. Generate parent `YU05-post-trade-compliance` from a clean target root.
3. Overlay the trade-processor EOD producer overrides (`EodPriceService`, `EodQualityChecker`,
   `EodPriceSnapshotRepository`, `EodEventPublisher`, `EodController`, model classes, config, tests).
4. Overlay the position-service EOD consumer overrides (`EodPnlConsumer` — self-contained NATS
   wiring, no separate config bean — `EodPriceSnapshotReader`, `EodPnlRepository`, model classes,
   `build.gradle` NATS dep, config, tests).
5. Overlay the k8s database-init ConfigMap with the new `eod_price_session` / `eod_price_snapshot` /
   `eod_position_pnl` tables, and the new `eod-session-close` CronJob manifest + the
   `traderx-eod-batch-chain.json` Grafana dashboard ConfigMap.
6. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU06-eod-price-production`.
7. Inherit everything else (runtime harness, other manifests, GKE deploy scripts, observability
   stack) unchanged from `YU05-post-trade-compliance`.

## Shared-file override caution (see research.md)

These files are overridden by an ancestor **and** by YU06; each YU06 copy must start from the
latest ancestor version and add on top, never replace:

- `trade-processor/src/main/resources/application.properties` (YU02 driver + YU05 auth/tca/recon → + `eod.*`)
- `trade-processor/.../service/PriceHistoryStore.java` (YU05 `record`/`twap`/`priceAtOrBefore` → + `tickers()`)
- `position-service/.../application.properties`, `build.gradle` (009b/YU02 → + NATS dep + `eod.*`)
- `kubernetes-runtime/manifests/base/database-init-configmap.yaml` (YU05 `settlementdate` → + EOD tables)

Verify empirically after generating: regenerate, then grep the generated output for an ancestor
marker (`settlementdate`, YU04 control-feed property names, the YU02 MariaDB driver class) **and**
a YU06 marker (`eod_price_snapshot`, `eod.quality`) in each shared file.

## Build / verify

```bash
bash pipeline/generate-state.sh YU06-eod-price-production
(cd generated/code/target-generated/trade-processor && ./gradlew test)
(cd generated/code/target-generated/position-service && ./gradlew test)
```

Deploy uses the inherited `YU05`/`YU02` GKE scripts/CI (the state changes only trade-processor and
position-service image content, the database init ConfigMap, one CronJob, and one Grafana dashboard).
