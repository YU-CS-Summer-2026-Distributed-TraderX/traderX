# Non-Functional Delta: YU16 over YU15-eod-risk-extract

Every inherited non-functional requirement is retained.

## Determinism and the core

- NFR-CDM01: The deterministic core SHALL NOT change — no snapshot field, no new command type,
  no risk-gate or matching change; `MatchingEngineClusteredService`, `BlpRiskState` and the
  codecs are byte-identical to the YU15 layers. Instrument semantics live in reference-data,
  pricing, post-trade and display layers.
- NFR-CDM02: `SNAPSHOT_FORMAT` SHALL remain 4 and `MIN_READABLE_SNAPSHOT_FORMAT` SHALL remain 3.
- NFR-CDM08: Bond arithmetic SHALL be exact — integer ticks and `BigDecimal`, never floating
  point — in the read model and the extract; the engine's integer arithmetic is unchanged.
- NFR-CDM09: `TRADERX_FIXED_UTC_INSTANT` SHALL be honored by reference-data's `matured` flag and
  price-publisher's Treasury clock, so maturity behavior is testable at a chosen instant.

## Rollability

- NFR-CDM03: The state SHALL NOT require a fresh epoch or a PVC wipe. Because the replicated
  state machine is unchanged, there is no mixed-version divergence window and the state's image
  can roll onto the standing rig.

## Non-regression

- NFR-CDM04: Every inherited proof SHALL remain green — the full `scripts/yu15/run-proofs.sh`
  suite, the order-matcher suite, the allocation and no-GC gates, and the YU04 control-feed
  pair (migrated to the general route).

## Contracts and dependencies

- NFR-CDM05: The adopted CDM subset SHALL be documented in `data-model.md` with enum literals
  quoted from CDM source; the runtime record stays flat.
- NFR-CDM06: No messaging subject SHALL be added, removed or renamed; the durable control feed
  keeps `TRADERX_CONTROL_SECURITY` / `traderx.control.security.deltas`.
- NFR-CDM07: No external symbology dependency, no CUSIP/ISIN values, no live Treasury API, no
  credential; identifiers and auction prices are baked offline. Ported HTTP clients keep
  2 s connect / 5 s read timeouts, configurable where the source pack made them so.
