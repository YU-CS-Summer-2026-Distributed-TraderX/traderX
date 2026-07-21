# Functional Delta: YU14-listed-equity-options (vs YU13-limit-order-book)

Everything in YU13 is carried forward unchanged: the crossing book, price-time priority,
grid/band admission, market-order semantics, egress ack classes, and snapshot completeness
discipline all apply verbatim to option contracts.

## Added

- FR-LEO01 — option contracts are securities identified by unpadded OCC symbols; they trade
  through the inherited paths with no matching-engine change.
- FR-LEO02 — deterministic multiplier derivation at symbol registration (option → 100,
  other → 1), identical on every member and replay.
- FR-LEO03 — all risk-gate notional math (reserve, market trade, executed exposure,
  concentration projection) multiplies by the contract multiplier; overflow rejects
  ORDER_NOTIONAL.
- FR-LEO04 — the multiplier is cluster state: format-3 snapshot security records carry it, and
  restore fails closed on multiplier < 1.
- FR-LEO05 — strike/expiry/call-put/underlying and counterparty/netting-set never enter the
  consensus log; they are reference data (derived and joined respectively).
- FR-LEO06 — instrument currency (USD) and derived position notional
  (quantity x price x multiplier) exposed at the reference-data layer.
- FR-LEO07 — the SBE symbol-registration ticker field carries at least 19 ASCII characters.

## Changed

- Snapshot format identifier 2 → 3: the security record gains the multiplier column; a format-2
  header fails closed on load (the inherited unknown-format rule, applied to the new format).

## Removed

- Nothing.
