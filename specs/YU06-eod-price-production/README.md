# YU06-eod-price-production

End-of-day price production plus an overnight batch dependency chain, gated by a durable
`EOD_PRICES_READY` JetStream event. Adds the trading-session-close concept TraderX lacks: assemble
official closing prices, run quality checks, publish a versioned immutable snapshot, gate all
downstream jobs on it, and drive one real consumer (EOD position marks / P&L) off it.

- **Parent state:** `YU05-post-trade-compliance` (chain `014 → YU02 → YU03 → YU04 → YU05 → YU06`)
- **Design baseline:** ADR-026 (last-trade closing price + versioned immutable snapshot), ADR-027
  (lightweight JetStream event-chain orchestration), ADR-028 (producer/consumer split + fail-safe
  halt-and-alert).
- **Read first:** `spec.md` (scope + the consistency/fail-safe principles), `research.md` (why
  producer=trade-processor, consumer=position-service, and the generation gotcha), `data-model.md`
  (tables + event payloads), `generation/implementation-status.md` (done vs. deferred + verification).

Generate:

```bash
bash pipeline/generate-state.sh YU06-eod-price-production
(cd generated/code/target-generated/trade-processor && ./gradlew test)
(cd generated/code/target-generated/position-service && ./gradlew test)
```

Producer changes live in `trade-processor` (reuses YU05's `PriceHistoryStore` price feed, MariaDB,
JWT auth); the consumer lives in `position-service` (new durable JetStream subscriber). Both new
tables plus the consumer output table are added to the k8s database-init ConfigMap. Everything else
(deploy/runtime harness, observability stack) is inherited unchanged from `YU05-post-trade-compliance`.
