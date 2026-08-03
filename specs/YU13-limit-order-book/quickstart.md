# Quickstart: YU13-limit-order-book

## 1. Generate

```bash
bash pipeline/generate-state.sh YU13-limit-order-book
```

Confirm the generated state and crossing-book artifacts:

```bash
rg -n "YU13-limit-order-book|LimitBook|FLAG_RESTING_UPDATE|SNAPSHOT_FORMAT = 2" \
  generated/code/target-generated/YU13-limit-order-book \
  generated/code/target-generated/order-matcher
```

## 2. Crossing-book behavior

The crossing book runs inside the generated order-matcher test suite: price-time priority,
partial fills, market orders, cancel unlink, grid/band admission, replay determinism, and a
snapshot round-trip that restores the resting book with its per-level FIFO intact.

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.lmax.LimitOrderBookTest'
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.ClusterSnapshotCodecTest'
```

## 3. Single- and three-member cluster proofs

The cluster recovery proofs boot in-process Aeron Clusters, cross orders through the consensus
log into the book, snapshot, restart, and assert strict no-ID-reuse and trade-counter continuity
across recovery and two failovers on the crossing engine.

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.*'
```

## 4. Match-latency histogram

The engine's own number — the match operation on the BLP thread, full nanosecond percentiles for
resting inserts, limit crosses, and market orders:

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.lmax.MatchLatencyBenchmarkTest' -i \
  | rg 'MATCH-LATENCY'
```

## 5. Full order-matcher regression + allocation gates

```bash
cd generated/code/target-generated/order-matcher
./gradlew test
./gradlew --no-daemon noGcTest
```

## 6. State checks

```bash
TRADERX_SKIP_GENERATE=1 bash scripts/test-state-YU13-limit-order-book.sh
```
