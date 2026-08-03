# ADR-054: Counterparty, currency, and derived notional are extract-time reference data

## Status

Accepted (YU14-listed-equity-options)

## Context

The downstream risk extract needs three fields the engine never consumes: a counterparty
identifier (with netting-set grouping) per account, a currency per instrument, and a notional
per position. Placing them in cluster state would put replication, snapshot, and determinism
cost on attributes no in-cluster decision reads.

## Decision

They are reference data in this pack, joined downstream:

- `reference-data/counterparties.csv` maps `accountId` → `counterpartyId`, `nettingSetId`,
  `currency`. Positions join it by accountId at extract time.
- `reference-data/instruments.csv` materializes the instrument view (type, underlying, strike,
  expiry, call/put, multiplier, currency) for the seeded universe; every derivable column is a
  pure function of the ticker, and currency is USD across the traded universe.
- Notional is a derived field, never stored:
  `position quantity x last price x contract multiplier`. Because in-cluster reservations and
  executed exposure are stored already-multiplied, the extract's derivation and the engine's
  accounting agree by construction.

## Consequences

- The consensus log and snapshot stay instrument-agnostic beyond the multiplier (ADR-052).
- The extract joins flat files by key — no cluster round-trip, no new endpoint, no coupling of
  extract cadence to engine load.
- Counterparty/netting attributes can change (a re-papered CSA) by editing reference data,
  with no cluster migration.
