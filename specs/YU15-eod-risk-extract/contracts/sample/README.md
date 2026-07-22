# Sample EOD risk extract

A real extract, taken from a live run — not hand-written. It is here so the delivered contract can
be read, loaded against, and verified without standing a cluster up.

| File | What |
|---|---|
| `risk-extract.csv` | The fixture as delivered: `#` metadata preamble, then one un-netted row per `(accountId, security)` |
| `risk-extract.cut` | The position cut it was built from — the leader's render at consensus sequence 1544719 |

Taken at `sessionDate=2026-07-22`, `priceSnapshotVersion=8`, `consensusSequence=1544719`, 18 rows.
Delivered as `seq-1544719.csv` alongside `seq-1544719.cut`.

## It verifies itself

The fixture's preamble records the SHA-256 of the cut it was built from, so the pair can be checked
with nothing but a hash:

```bash
grep '^# cutSha256=' risk-extract.csv          # 9ceeed3652486b71c919ed532e06f01a81a468dd2d23dc122bfcf30b56e3274d
sha256sum risk-extract.cut                     # ...the same value
```

And the fixture is a pure function of that cut, so it rebuilds byte-identically from it with no
cluster involved (FR-RXT10):

```bash
java -cp '/opt/app/classes:/opt/app/lib/*' \
  finos.traderx.ordermatcher.cluster.RiskExtractMain --rebuild risk-extract.cut /tmp/rebuild.csv
cmp risk-extract.csv /tmp/rebuild.csv
```

## Loading it

The preamble is `#`-prefixed, so every mainstream CSV reader skips it with one argument:

```python
import pandas as pd
df = pd.read_csv("risk-extract.csv", comment="#")
```

Read the preamble separately for the stamp and the conventions — it states the market-value and
cost-basis formulas, the netting position, and what each `markSource` means, so a tie-out
discrepancy starts from a written convention rather than a guess.

## What to notice

- **Every row is `markSource=EOD_SNAPSHOT`, `markQuality=OK`** — equities and options alike are
  marked from the same published closing-price version.
- **Rows are un-netted.** Accounts 22214 and 42422 hold the two sides of the same crosses and both
  appear in full; `counterpartyId` and `nettingSetId` are attributes, never an applied aggregation.
- **`marketValue` is multiplier-aware.** `22214,AAPL260918C00240000` is 10 contracts × $10.025 ×
  100 = $10,025 — not $100.25. It equals `eod_position_pnl.market_value` for the same row exactly.
- **Flat positions are emitted, not dropped** (`AAPL261218C00240000`, quantity 0), so a consumer
  sees the whole `(account, security)` universe rather than only what happens to be non-zero.
- **Prices are exact decimals at scale 6** throughout, carried from integer ticks — no float
  rounding can differ between runs or architectures.
- **Every row is state at one consensus sequence**, so this is a portfolio the firm genuinely held
  simultaneously, which is what makes a VaR computed from it meaningful.
