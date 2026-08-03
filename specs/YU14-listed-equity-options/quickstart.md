# Quickstart: YU14-listed-equity-options

## 1. Generate

```bash
bash pipeline/generate-state.sh YU14-listed-equity-options
```

Confirm the generated state and option-instrument artifacts:

```bash
rg -n "YU14-listed-equity-options|OccSymbol|contractMultiplier|SNAPSHOT_FORMAT = 3" \
  generated/code/target-generated/YU14-listed-equity-options \
  generated/code/target-generated/order-matcher
```

## 2. Instrument model + multiplied notional behavior

The OCC parser, the multiplier-aware risk math, and the format-3 snapshot round-trip run inside
the generated order-matcher test suite:

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.lmax.OccSymbolTest'
./gradlew test --tests 'finos.traderx.ordermatcher.risk.BlpRiskStateTest'
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.ClusterSnapshotCodecTest'
```

## 3. Option contracts crossing on the cluster book

The cluster suite registers OCC option symbols through committed ingress, seeds and enables
them, crosses one contract, and proves the multiplied notional cap and snapshot recovery:

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.*'
```

## 4. Live option chain on kind

Bring the state up and seed the packaged chain (two underlyings x two expiries x three strikes
x call/put, premium-scale prices), then cross one contract:

```bash
bash scripts/start-state-YU14-listed-equity-options-generated.sh
bash scripts/proofs/seed-option-chain.sh          # seeds accounts, underlyings, and the chain
```

The script finishes by submitting a resting sell and a crossing buy on one contract and
printing the booked cross plus a multiplied-notional rejection probe (an order sized to pass at
premium notional but reject at contract notional).

## 5. Full order-matcher regression + allocation gates

```bash
cd generated/code/target-generated/order-matcher
./gradlew test
./gradlew --no-daemon noGcTest
```

## 6. State checks

```bash
TRADERX_SKIP_GENERATE=1 bash scripts/test-state-YU14-listed-equity-options.sh
```
