# Functional Delta: YU15-eod-risk-extract (vs YU14-listed-equity-options)

Everything the parent state does is carried forward — the matching engine, the three-member Aeron
cluster, listed equity options and their contract multipliers, and the overnight EOD price and P&L
chain, that last one with the adjustments named under Changed. What this state adds is an end-of-day
risk extract: one immutable portfolio fixture, cut from the cluster at an exact consensus sequence —
every account frozen at the same instant — that an external pricing and risk engine reads and
computes VaR from. The inherited behaviours that change do so only so that options survive the whole
chain intact and a quiet end-of-day cluster stays operable.

## Added

- A risk-extract producer whose only trigger is the `eod.pnl.done` event, held on a durable
  JetStream consumer so a failed extract is redelivered rather than lost.
- Idempotent creation of the `TRADERX_EOD` stream at both ends, so the producer need not start
  after `position-service` and neither side has to be first.
- A sequenced risk-extract marker, ordinary cluster ingress that mutates no state, at whose sequence
  every member renders positions from the replicated state machine, not the SQL `positions` table.
- A malformed marker dropped without advancing any sequence, exactly as an unrecognised input event
  is, so a corrupt marker never names a sequence.
- Canonical cut rendering — rows ordered by `(accountId, securityId)`, fixed columns, integer ticks,
  no wall-clock value — so the bytes are identical on every member and on every replay.
- Leader-only publication of the cut as a single NATS message carrying its own row count, off the
  deterministic apply thread, with every member recording the SHA-256 it rendered.
- Per-row marks taken from the published closing-price snapshot for the stamped
  `(sessionDate, version)`, falling back to the engine's last trade at the cut sequence.
- A snapshot row whose quality is `MISSING` or whose price is null counted as absent, falling
  through to the last-trade mark.
- Un-netted `(accountId, security)` rows carrying counterparty identifier, netting set, currency,
  mark source and mark quality as attributes rather than as an applied aggregation.
- Market value and unrealised P&L in exact decimal arithmetic, scaled by the contract multiplier the
  cluster holds as replicated state, not by reference data joined at build time.
- Valuation and cost-basis conventions stated in the fixture's own header, so a methodology
  discrepancy with the consumer starts from a written convention rather than a guess.
- A quiescence witness — a second marker that lands exactly one sequence past the first, or the
  extract refuses to emit — recorded in the delivery announcement.
- Write-once delivery keyed by `(sessionDate, priceVersion, consensusSequence)`, with the cut stored
  beside the fixture.
- The fixture as a pure function of the cut plus immutable reference data, so a rebuild from the
  stored cut alone, with no cluster involved, reproduces identical bytes.
- An announcement on `risk.extract.ready` carrying the URI, the stamp, the row count, and both the
  fixture and cut SHA-256 hashes.
- Fail-closed aborts for an unmarkable row, an account with no counterparty mapping, or a cut whose
  row count or sequence disagrees with its stamp: no object is written and nothing is announced.
- A position held on a security with no registered ticker aborting the render, because an extract
  that silently omits a position is worse than no extract.

## Changed

- Cluster readiness and a member's reported applied position now come from its consensus-log
  position, so a member restored from a snapshot into an idle cluster reports itself caught up. The
  engine's own applied counter remains visible as a separate field.
- Instrument-identifier columns in the SQL schema are wide enough for an unpadded OCC option symbol,
  and the migration widens an already-populated database rather than only a freshly created one.
- The market-data feed quotes every listed option contract, deriving each quote from its
  underlying's current tick instead of walking an option's premium independently.
- The EOD quality gate's maximum-move threshold is instrument-aware, so a leveraged contract's
  ordinary day-over-day move no longer blocks publication of the whole session. Staleness and
  missing-price checks are unchanged.
- EOD P&L market value applies the contract multiplier, so an option's recorded market value is its
  real exposure and agrees with the extract rather than differing by the multiplier.

## Removed

- Nothing.
