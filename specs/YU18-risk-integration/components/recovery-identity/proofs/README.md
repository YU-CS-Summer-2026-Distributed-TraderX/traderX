# RI-06 diagnostic fixtures

These are intentionally outside runtime `src/test`: `TradeEpochCollisionTest` asserts the desired fresh-epoch safety and fails on the accepted base. It must not be hidden as a passing production gate. `TradeEpochWireTest` characterizes the actual encoder collision. No broker, container or retained rig is needed.

Reproduce in a new disposable generated output using JDK 21:

```bash
TRADERX_GENERATED_ROOT=/private/tmp/ri06-repro bash pipeline/generate-state-YU18-risk-integration.sh
cp specs/YU18-risk-integration/components/recovery-identity/proofs/TradeEpochCollisionTest.java /private/tmp/ri06-repro/code/target-generated/trade-processor/src/test/java/finos/traderx/tradeprocessor/service/
cp specs/YU18-risk-integration/components/recovery-identity/proofs/TradeEpochWireTest.java /private/tmp/ri06-repro/code/target-generated/order-matcher/src/test/java/finos/traderx/ordermatcher/cluster/
(cd /private/tmp/ri06-repro/code/target-generated/trade-processor && ./gradlew test --tests '*TradeEpochCollisionTest' --no-daemon --max-workers=2)
(cd /private/tmp/ri06-repro/code/target-generated/order-matcher && ./gradlew test --tests '*TradeEpochWireTest' --no-daemon --max-workers=2)
```

SQL command expected exit 1, exactly 3 tests / 1 assertion failure: `expected fresh-1 but was old-1`; same-epoch duplicate and distinct-counter controls pass. Any dependency/context/SQL setup error is not a reproduction. Encoder command expected exit 0; XML stdout shows identical `1-B` keys with distinct `old-1` and `fresh-1` sourceOrderIds. H2 SQL is retained across actual TradeService reconstruction and committed transactions, not across host loss. No live MariaDB or consensus recovery claim. Preserve logs/XML before reusing generated test output.
