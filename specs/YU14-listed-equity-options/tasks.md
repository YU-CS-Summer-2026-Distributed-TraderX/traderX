# Tasks: YU14-listed-equity-options

- [ ] T-LEO01 — Scaffold the state: spec pack, pipeline hook/render pair, catalog entry,
      runtime-harness + CI-assets registration, runtime wrapper scripts.
- [ ] T-LEO02 — `OccSymbol` parser (unpadded OCC symbols → type/underlying/expiry/callPut/
      strike/multiplier) with unit tests, including non-option and malformed tickers.
- [ ] T-LEO03 — Widen the SBE symbol-registration ticker field to 32 bytes (schema + codec);
      registration round-trips a 19-char OCC symbol.
- [ ] T-LEO04 — Multiplier-aware `BlpRiskState`: dense per-security multiplier, applied in
      reserve, market-trade, executed-exposure, and concentration math; overflow rejects
      ORDER_NOTIONAL; unit tests prove the multiplied cap fires where the un-multiplied
      notional would pass.
- [ ] T-LEO05 — Cluster wiring: `onSymbolRegister` derives and installs the multiplier;
      snapshot format 3 carries it per security; restore fails closed on multiplier < 1 and on
      non-3 formats; round-trip tests.
- [ ] T-LEO06 — Reference data: `instruments.csv` (seeded chain, derived columns, currency) and
      `counterparties.csv` (accountId → counterparty/netting set/currency).
- [ ] T-LEO07 — Seed + smoke: `scripts/bench/seed-option-chain.sh` seeds the chain through
      `/seed` and proves one option cross books on a live kind cluster (the silent-reject
      admission gate exercised first).
- [ ] T-LEO08 — Full regression: `test`, `noGcTest`, `riskNoGcTest`, all four allocation gates.
- [ ] T-LEO09 — Short bench: booked throughput with option contracts in the universe shows no
      regression against the YU13 baseline.
- [ ] T-LEO10 — `generation/implementation-status.md` written with verification evidence; state
      docs synced.
