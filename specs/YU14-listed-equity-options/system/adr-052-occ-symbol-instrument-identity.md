# ADR-052: OCC symbol as instrument identity; multiplier is the only new cluster state

## Status

Accepted (YU14-listed-equity-options)

## Context

Listed options must trade on the inherited crossing book, and the risk gate must know each
contract's multiplier. The cluster's consensus log and snapshot are the system's most expensive
surfaces: everything in them must be deterministic, replicated, snapshotted, and carried by
every future state. Option contracts carry five descriptive attributes (underlying, strike,
expiry, call/put, multiplier), a counterparty join lives on the account, and a currency lives on
the instrument — but the matching engine consumes none of them, and the risk gate consumes
exactly one: the multiplier.

## Decision

An option contract's security ticker IS its unpadded OCC symbol:
`<root><yymmdd><C|P><8-digit strike x 1000>`, e.g. `AAPL260918C00240000`. A ticker whose
fixed-width 15-character tail parses as expiry + call/put + strike, with a non-empty leading
root, is an option; anything else is not.

The contract multiplier is derived from the committed ticker inside
`MatchingEngineClusteredService.onSymbolRegister` — a pure function evaluated identically on
every member and replay — and installed in `BlpRiskState` beside the security's control row. It
is the only option attribute that enters cluster state, and it rides the format-3 snapshot's
security record with fail-closed restore (multiplier < 1 aborts recovery).

Underlying, strike, expiry, and call/put are never stored: they are re-derivable from the
identifier at the reference-data layer. No message schema gains an option field; the only wire
change is widening the SBE ticker field 16 → 32 bytes so OCC symbols fit.

## Consequences

- One registration path, one identifier namespace, zero gateway changes: `/seed` and order entry
  handle option tickers as ordinary strings.
- The consensus log and snapshot grow by one long per security — not five attributes — and the
  hot decision path gains one dense-array read.
- The multiplier survives restarts on its own snapshot column rather than by re-derivation, so
  recovery does not depend on the parser's version.
- Instruments whose real-world multiplier differs from 100 (non-standard deliverables after
  corporate actions) are outside the registered symbology: they would need an explicit
  multiplier source before such a contract could be listed here.
