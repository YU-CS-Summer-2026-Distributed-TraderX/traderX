# Feature Specification: EOD Risk Extract

**Feature Branch**: `YU15-eod-risk-extract`
**Created**: 2026-07-21
**Status**: In implementation
**Input**: EOD risk-extract direction brief, parented on `YU14-listed-equity-options`

## User Stories

- As a risk-engine consumer, I want a portfolio snapshot pulled when the EOD batch is ready rather
  than a stream I have to assemble, so my engine reads a file and prices it.
- As a risk-engine consumer, I want every account frozen at the same instant, because a portfolio
  assembled from accounts sampled at different moments is not one the firm ever held and its VaR is
  not a meaningful number.
- As a risk-engine consumer, I want our official closing marks and our computed P&L in the extract,
  so my base NPV has something exact to reconcile against.
- As a risk-engine consumer, I want rows un-netted at account and security with the counterparty
  identifier attached, so my engine applies netting and CSA treatment itself rather than receiving
  an aggregation it cannot undo.
- As a risk-engine consumer, I want the extract for a given identifier to return identical bytes
  forever, so accuracy runs across CPU, GPU, and TPU score the identical portfolio and only
  numerical precision varies.
- As the availability owner, I want a member that restarts during the EOD window to rejoin the
  Service, because a quiet cluster is the normal state at end of day.

## Functional Requirements

- FR-RXT01: The producer SHALL take `eod.pnl.done` as its only trigger, over a durable JetStream
  consumer, so it fires after closing prices are final and after our own P&L exists as the
  consumer's reconciliation target.
- FR-RXT02: Positions SHALL be materialised from the replicated state machine at a consensus
  sequence, and SHALL NOT be read from the SQL `positions` read model.
- FR-RXT03: A risk-extract marker SHALL be ordinary sequenced cluster ingress carrying only the
  extract's stamp, SHALL mutate no replicated state, and SHALL cause every member to render the
  position cut at the sequence it lands at.
- FR-RXT04: The rendered cut SHALL be byte-identical on every member and on every replay to the
  same sequence — rows ordered by `(accountId, securityId)`, fixed columns, integer ticks, no
  wall-clock value, no map iteration order.
- FR-RXT05: Only the leader SHALL publish the cut, and publication SHALL NOT block the deterministic
  apply thread.
- FR-RXT06: The cut SHALL travel as a single message carrying its own row count, so a truncated
  delivery is detectable rather than indistinguishable from a complete one.
- FR-RXT07: Each row SHALL be marked from the published closing-price snapshot for the stamped
  `(sessionDate, version)` where one exists, and from the engine's last trade at the cut sequence
  otherwise, and SHALL record which source and what quality it used.
- FR-RXT08: The producer SHALL send a second marker after building and SHALL refuse to emit unless
  it landed at exactly one sequence past the first, proving nothing was sequenced during the build.
- FR-RXT09: A row with neither a published close nor a last trade, or whose account has no
  counterparty mapping, SHALL abort the entire extract rather than emit a gap or a zero.
- FR-RXT10: The fixture SHALL be a pure function of the cut plus immutable reference data, and the
  producer SHALL support rebuilding it from a stored cut alone, reproducing identical bytes.
- FR-RXT11: Rows SHALL be un-netted at `(accountId, security)` grain and SHALL carry counterparty
  identifier, netting set, and currency as attributes.
- FR-RXT12: Notional and P&L SHALL be contract-multiplier aware, using the multiplier the cluster
  holds as replicated state, and SHALL be computed in exact decimal arithmetic.
- FR-RXT13: The delivered object SHALL be write-once under a key derived from
  `(sessionDate, priceVersion, consensusSequence)`, and delivery SHALL be announced on
  `risk.extract.ready` carrying the URI and the stamp.
- FR-RXT14: The cut SHALL be stored beside the fixture so the fixture can be rebuilt and verified
  without the cluster.
- FR-RXT15: Cluster readiness SHALL be determined by a member's consensus-log position, so a member
  restored from a snapshot into an idle cluster reports itself caught up.
- FR-RXT16: Every instrument-identifier column in the SQL schema SHALL be wide enough to hold an
  unpadded OCC option symbol, so an option fill published by the trade bridge persists to the read
  model rather than being rejected. The migration SHALL widen an already-populated database, not
  only a freshly created one.

## Non-Functional Requirements

- NFR-RXT01: The marker SHALL be routed by template id ahead of the order-flow branch, and SHALL
  introduce no allocation on the ordinary apply path — `noGcTest` and all four allocation gates
  stay green.
- NFR-RXT02: The extract SHALL be safe to take while the cluster is serving traffic; correctness of
  the emission is enforced by the quiescence witness, not by stopping the engine.
- NFR-RXT03: A failed extract SHALL leave no partial object and no announcement, and SHALL be
  retried by durable redelivery.
- NFR-RXT04: The producer SHALL tolerate its dependencies being unavailable at start, and SHALL open
  a fresh cluster session per batch rather than hold one idle between runs.
- NFR-RXT05: Decimal values SHALL be exact — integer ticks and `BigDecimal`, never floating point —
  so identical inputs cannot produce differing bytes across JVMs.

## Technical Debt Register

- TD-RXT01: The cut is one NATS message, bounded by the 1MB default payload at roughly 15k position
  rows. The row count in the header detects an overrun; chunking or writing the cut directly to the
  object store is the path past it.
- TD-RXT02: Listed options are marked from the engine's last trade because no published close
  exists for them — `PriceHistoryStore` is fed only by the synthetic `pricing.*` feed, which
  carries no option contracts. The schema half of this is fixed (FR-RXT16); the price-feed half
  is not, so options still have no published close to reconcile against.
- TD-RXT03: The mapping from account to counterparty is one-to-one reference data. Whether that is
  sufficient for the consumer's netting logic, or whether a counterparty entity with its own
  attributes is needed, is an open question with the consumer.

## Success Criteria

- SC-RXT01: Publishing `eod.pnl.done` — and nothing else — produces a delivered object and a
  `risk.extract.ready` announcement carrying its URI and stamp.
- SC-RXT02: All three cluster members log the identical cut SHA-256 for the same consensus sequence.
- SC-RXT03: The announcement's quiescence witness sequence is exactly one past the stamped sequence.
- SC-RXT04: Rebuilding the fixture from its stored cut reproduces the delivered bytes exactly.
- SC-RXT05: A member deleted and restarted replays to the stamped sequence, re-renders the identical
  cut SHA-256, and rejoins the Service.
- SC-RXT06: An equity row carries the published close and its quality; an option row carries the
  cluster last trade at the cut sequence, with multiplier-aware market value.
- SC-RXT07: An unmarkable row, an unmapped account, and a truncated cut each abort the extract.
- SC-RXT08: The order-matcher suite and all allocation and epsilon-GC gates pass.
- SC-RXT09: An option cross booked on the cluster persists both trade rows and both position rows
  with its 19-character OCC symbol intact, through the real bridge → NATS → trade-processor chain.
- SC-RXT10: Applying the state's `900-migrations.sql` to a database carrying an older state's
  narrow columns widens them in place.
