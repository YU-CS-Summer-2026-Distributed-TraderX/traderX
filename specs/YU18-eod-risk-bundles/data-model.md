# Data Model: EOD Risk Bundles

## Source records

Positions retain YU17 schema 3 at `(accountId, security)` grain. OTC records retain schema 2 at `contractId` grain. IDs become globally scoped through manifest clusterEpoch. The wrapper preserves net holdings and individual OTC bookings exactly as exported.

## Bundle

`manifest.json`, `positions.csv`, `contracts.csv` are the only bundle entries. The manifest fields are defined in `contracts/contract-delta.md`. A new EOD version or corrected file yields a different content identity. Origin is `synthetic` or `export`; it is caller-declared, not inferred from prices.

## Mock result

`results.json` contains the input bundle ID, epoch, valuation time and one item per input row. Position items carry account/security; OTC items carry account/contractId. Each item carries currency, source kind, NOT_PRICED, MOCK_ONLY, null NPV and empty Greeks. The result contains no portfolio valuation total.
