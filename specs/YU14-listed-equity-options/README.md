# Feature Pack: YU14-listed-equity-options

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `architecture`
Lineage role: `optional`
Previous state: `YU13-limit-order-book`

This pack adds listed equity options as tradeable instruments. An option contract is a security
identifier — its unpadded OCC symbol — with a two-sided book, so it registers, quotes, crosses,
partially fills, and cancels through exactly the inherited YU13 paths; the matching engine is
unchanged. What changes is the instrument model and the risk gate's notional math: symbol
registration derives a contract multiplier (100 for OCC option symbols, 1 otherwise)
deterministically from the committed ticker, the risk gate computes every notional as
quantity x price x multiplier, and the multiplier rides the format-3 cluster snapshot with
fail-closed restore. Underlying, strike, expiry, call/put, counterparty, and currency stay out
of the consensus log as reference data.

Primary intent:

- trade option contracts on the crossing book as ordinary securities, identified by OCC symbol,
- make the risk gate multiplier-aware: a $2.50 option controlling 100 shares consumes $250 of
  credit, order-notional, and concentration budget,
- carry the multiplier in cluster state and the snapshot (format 3), restored fail-closed,
- model the instrument reference data — type, underlying, strike, expiry, call/put, multiplier,
  currency — derived from the OCC identifier, plus the accountId → counterparty/netting-set
  mapping and derived notional for the positions extract.

Core artifacts:

- `generation/runtime-overrides/order-matcher/` — `OccSymbol`, multiplier-aware `BlpRiskState`,
  format-3 snapshot in `MatchingEngineClusteredService`, 32-byte SBE ticker field
- `reference-data/` — seeded option chain (`instruments.csv`) and counterparty mapping
  (`counterparties.csv`)
- `system/adr-052` … `adr-054` — consensus-log split, multiplier math, extract-time reference data
- `system/architecture.model.json` — generated architecture flow for the option instrument model

Target runtime behavior:

- a seeded option contract behaves exactly as an equity on the book: rests, crosses at the
  resting price, partially fills, cancels — one matching path for both instrument types,
- the risk gate reserves and executes multiplied notional; caps fire at the contract's economic
  exposure, not its premium,
- every member derives the identical multiplier from the identical committed registration, and a
  snapshot-restored member enforces identical caps or fails closed,
- the positions extract resolves counterparty, currency, and derived notional from reference
  data without touching the cluster.
