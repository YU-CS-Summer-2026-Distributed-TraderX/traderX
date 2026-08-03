# Feature Specification: Listed Equity Options

**Feature Branch**: `YU14-listed-equity-options`
**Created**: 2026-07-21
**Status**: In implementation
**Input**: Listed-equity-options direction brief, parented on `YU13-limit-order-book`

## User Stories

- As the trading-platform owner, I want listed equity options registered and traded as ordinary
  securities on the crossing limit-order book — same price-time priority, partial fills, and
  cancel semantics as equities — so option flow needs no second matching path.
- As the risk owner, I want the pre-trade notional math to be contract-multiplier-aware, so a
  $2.50 option controlling 100 shares consumes $250 of credit, order-notional, and concentration
  budget rather than $2.50.
- As a risk-engine consumer, I want each instrument's type, underlying, strike, expiry, call/put
  flag, contract multiplier, and currency available from the instrument model, so portfolio
  valuation and Greeks can be computed downstream without venue-side pricing.
- As a risk-engine consumer, I want every account mapped to a counterparty identifier and
  netting set, and every position's notional derivable as quantity x price x multiplier, so the
  positions extract joins exposure to counterparty without engine involvement.
- As the availability owner, I want the contract multiplier carried in the cluster snapshot and
  restored fail-closed, so a recovered member enforces exactly the same notional caps as a
  never-restarted member.

## Functional Requirements

- FR-LEO01: An option contract SHALL be identified by its unpadded OCC symbol —
  `<root><yymmdd><C|P><strike x 1000, 8 digits>` (e.g. `AAPL260918C00240000`) — and SHALL
  register, enable, price-seed, quote, cross, partially fill, and cancel through exactly the
  inherited security paths, with no matching-engine change.
- FR-LEO02: Symbol registration SHALL derive the instrument type deterministically from the
  ticker: a ticker parsing as an OCC option symbol registers with contract multiplier 100; any
  other ticker registers with contract multiplier 1. Every member and every replay SHALL derive
  the identical multiplier for the identical committed registration.
- FR-LEO03: The risk gate SHALL compute all order notional as
  quantity x validation price x contract multiplier — in the reserve path, the market-trade
  path, and executed-exposure accumulation on fill — and SHALL apply the contract multiplier in
  the concentration projection. Arithmetic overflow of the multiplied notional SHALL reject
  ORDER_NOTIONAL, never wrap.
- FR-LEO04: The contract multiplier SHALL be cluster state: carried per security in the
  format-3 snapshot security record and restored before any post-restore decision. A restored
  security record with multiplier < 1 SHALL fail closed (recovery aborts).
- FR-LEO05: Underlying, strike, expiry, and call/put SHALL NOT enter the consensus log or the
  snapshot; they are derived from the OCC identifier at the reference-data layer. Counterparty
  identity and netting set SHALL NOT enter the consensus log; they are reference data joined to
  positions by accountId at extract time.
- FR-LEO06: Every instrument SHALL carry a currency in the reference-data model, populated USD;
  notional SHALL be exposed as a derived field — position quantity x last price x contract
  multiplier — wherever positions are surfaced from reference data.
- FR-LEO07: The SBE symbol-registration ticker field SHALL carry at least 19 ASCII characters so
  unpadded OCC symbols register through the unchanged gateway seeding and order paths.

## Non-Functional Requirements

- NFR-LEO01: The multiplier-aware risk gate SHALL preserve the zero-allocation steady state: the
  Epsilon no-GC runs and all four allocation gates pass with option securities in the traffic mix.
- NFR-LEO02: Determinism is unchanged: identical committed logs produce identical books,
  multipliers, reservations, and executed exposure on every member and replay; multiplier
  derivation uses no clock, locale, or iteration-order dependence.
- NFR-LEO03: Sustained booked throughput SHALL show no regression against the YU13 baseline
  under the same bench parameters; the multiplier lookup adds one dense-array read to the
  decision path.

## Success Criteria

- SC-LEO01: A seeded option contract crosses on the book with price-time priority, partial
  fills, and cancel — proven through the cluster ingress/egress path with the same suite shape
  the equity book has.
- SC-LEO02: The notional cap fires at the multiplied level: an option order whose
  quantity x price x 100 exceeds a cap rejects (ORDER_NOTIONAL / CREDIT_LIMIT /
  CONCENTRATION_LIMIT respectively) where the same quantity x price alone would pass; the
  identical order on a multiplier-1 security is accepted.
- SC-LEO03: A snapshot round-trip preserves each security's contract multiplier and the option
  book; restore fails closed on a security record carrying multiplier < 1 and on the format-2
  legacy header.
- SC-LEO04: `test`, `noGcTest`, `riskNoGcTest`, and all four allocation gates pass.
- SC-LEO05: A short bench run reproduces YU13-class booked throughput with option contracts in
  the seeded universe.
- SC-LEO06: The reference-data model resolves, for every seeded instrument: type, underlying,
  strike, expiry, call/put, multiplier, currency; and for every seeded account: counterparty ID
  and netting set.
