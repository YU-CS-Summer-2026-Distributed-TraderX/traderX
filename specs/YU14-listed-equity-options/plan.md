# Implementation Plan: YU14-listed-equity-options

## Goal

Trade listed equity options on the unchanged crossing limit-order book by treating an option
contract as a security identifier (its OCC symbol), and make the risk gate's notional math
contract-multiplier-aware. The state is reference-data and notional-math: the matching engine,
book structure, gateway, and run harness are inherited from YU13 untouched.

## Workstreams

1. **Instrument identity** — `OccSymbol`: deterministic parser for unpadded OCC option symbols
   (`root + yymmdd + C|P + 8-digit strike`), yielding type, underlying, strike, expiry,
   call/put, and multiplier (100 for options, 1 otherwise). Widen the SBE symbol-registration
   ticker field 16 → 32 bytes so OCC symbols flow through the unchanged gateway paths.
2. **Multiplier as cluster state** — `MatchingEngineClusteredService.onSymbolRegister` derives
   the multiplier from the committed ticker and installs it in `BlpRiskState`; snapshot format 3
   carries it in the security record; restore fails closed on multiplier < 1.
3. **Multiplier-aware risk math** — `BlpRiskState`: a dense per-security multiplier array read
   in `decideAndReserve`, `decideMarketTrade`, `consume`, and the concentration projection;
   overflow of the multiplied notional rejects ORDER_NOTIONAL.
4. **Reference data** — `reference-data/instruments.csv` (the seeded option chain with derived
   fields and currency) and `reference-data/counterparties.csv` (accountId → counterparty ID,
   netting set, currency); notional documented as the derived field
   quantity x price x multiplier.
5. **Proof surface** — OCC parser tests, multiplied-notional-cap risk tests, format-3 snapshot
   round-trip and fail-closed tests, an option-cross smoke path (seed chain → one cross books),
   the inherited suite, allocation gates, and a short no-regression bench.

## Key decisions

- The consensus log carries only what matching and risk need: the multiplier (via the committed
  registration's ticker) — not strike, expiry, call/put, counterparty, or currency (ADR-052).
- Multiplier derivation happens once, at registration, in-cluster from the committed ticker —
  no message-schema field, no gateway change, no second registration path (ADR-052).
- Notional math multiplies inside the existing overflow-checked path; concentration applies the
  multiplier on the projected-quantity side, bounded by construction (ADR-053).
- Counterparty, netting set, and currency are extract-time reference data keyed by accountId
  and instrument; positions join them downstream (ADR-054).

## Exit Criteria

- All spec-pack success criteria SC-LEO01..06 hold with recorded evidence in
  `generation/implementation-status.md`.
- `bash pipeline/generate-state.sh YU14-listed-equity-options` exits 0 and the generated tree
  carries every ancestor marker on shared files.
- A seeded option chain crosses on a live kind cluster; the multiplied notional cap is observed
  firing through the REST path.
