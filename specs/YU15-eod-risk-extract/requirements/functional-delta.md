# Functional Delta: YU15 over YU14-listed-equity-options

Every inherited functional requirement is retained. This state adds the end-of-day risk extract.

## Trigger

- FR-RXT01: The producer SHALL consume `eod.pnl.done` over a durable JetStream consumer as its only
  trigger, and SHALL ensure the `TRADERX_EOD` stream idempotently so it need not start after
  `position-service`.
- FR-RXT01a: A failed extract SHALL leave the trigger message unacked so JetStream redelivers it.

## The cut

- FR-RXT02: Positions SHALL be materialised from the replicated state machine at a consensus
  sequence, and SHALL NOT be read from the SQL `positions` read model.
- FR-RXT03: The risk-extract marker SHALL be ordinary sequenced cluster ingress carrying only the
  extract's stamp, SHALL mutate no replicated state, and SHALL cause every member to render the
  position cut at the sequence it lands at.
- FR-RXT03a: A malformed marker SHALL be dropped without advancing any sequence.
- FR-RXT04: The rendered cut SHALL be byte-identical on every member and on every replay to the
  same sequence: rows ordered by `(accountId, securityId)`, fixed column order, integer ticks, no
  wall-clock value, no reliance on map or probe iteration order.
- FR-RXT04a: Each member SHALL record the SHA-256 of the cut it rendered, so cross-member agreement
  is verifiable from the members themselves.
- FR-RXT05: Only the leader SHALL publish the cut, and publication SHALL NOT block the
  deterministic apply thread.
- FR-RXT06: The cut SHALL be published as a single message carrying its own row count.
- FR-RXT06a: A position held on a security with no registered ticker SHALL abort the render.

## Marks and valuation

- FR-RXT07: Each row SHALL be marked from the published closing-price snapshot for the stamped
  `(sessionDate, version)` where a usable price exists, and from the engine's last trade at the cut
  sequence otherwise.
- FR-RXT07a: Each row SHALL record its mark source and mark quality.
- FR-RXT07b: A snapshot row whose quality is `MISSING` or whose price is null SHALL be treated as
  absent, falling through to the last-trade mark.
- FR-RXT12: Market value SHALL be `quantity × closingMark × contractMultiplier` and unrealised P&L
  SHALL be `(closingMark − costBasis) × quantity × contractMultiplier`, using the multiplier the
  cluster holds as replicated state, computed in exact decimal arithmetic.
- FR-RXT12a: The valuation and cost-basis conventions SHALL appear in the fixture's own header.

## Consistency

- FR-RXT08: The producer SHALL send a second marker after the join and SHALL refuse to emit unless
  it landed at exactly one sequence past the first.
- FR-RXT08a: The witness sequence SHALL be recorded in the delivery announcement.

## Fail closed

- FR-RXT09: A row with neither a published close nor a last trade SHALL abort the entire extract.
- FR-RXT09a: A row whose account has no counterparty mapping SHALL abort the entire extract.
- FR-RXT09b: A cut carrying a different number of rows than it declares, or naming a different
  sequence than the stamp, SHALL abort the entire extract.
- FR-RXT09c: An aborted extract SHALL write no object and publish no announcement.

## Content and delivery

- FR-RXT11: Rows SHALL be un-netted at `(accountId, security)` grain and SHALL carry counterparty
  identifier, netting set, and currency as attributes rather than as an applied aggregation.
- FR-RXT13: The delivered object SHALL be write-once under a key derived from
  `(sessionDate, priceVersion, consensusSequence)`, and delivery SHALL be announced on
  `risk.extract.ready` with the URI, the stamp, the row count, and both hashes.
- FR-RXT14: The cut SHALL be stored beside the fixture.
- FR-RXT10: The fixture SHALL be a pure function of the cut plus immutable reference data, and the
  producer SHALL support rebuilding it from a stored cut alone, reproducing identical bytes.

## Cluster readiness

- FR-RXT15: A member's readiness and its reported applied position SHALL be determined by its
  consensus-log position, so a member restored from a snapshot into an idle cluster reports itself
  caught up. The engine's own applied counter SHALL remain visible as a separate field.
