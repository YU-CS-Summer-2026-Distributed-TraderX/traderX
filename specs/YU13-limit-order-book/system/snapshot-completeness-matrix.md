# Snapshot Completeness Matrix: YU13-limit-order-book

Audit artifact for the cluster snapshot design (ADR-046) produced against
`MatchingEngineClusteredService` on the crossing engine (snapshot format 2). YU13 makes the
resting book a full future-output generator: the entire two-sided book must survive recovery so
a restored member answers the next crossing order — including per-level FIFO time priority —
identically to a never-restarted member. The format-2 additions are book geometry (header) and
per-security band anchors (`T_BOOK`) written before order rows; open rows in ascending-reference
order rebuild each price level's exact FIFO on restore. Every parent-state item and its
fail-closed guarantee is carried forward.

## Recovery contract

- **Authoritative log**: the Raft-committed consensus log. Every input — orders, cancels,
  ticks, control updates — is a committed ingress message; the service applies on one thread.
- **Snapshot boundary**: `onTakeSnapshot` runs at the service's applied log position; the
  cluster records the snapshot against exactly that position. No transport marker can be
  overwritten by upstream run-ahead — the parent state's slice-5 register has no analogue here
  because the callback and the position are bound by the consensus module itself.
- **Resume**: recovery loads the newest valid snapshot and the container replays the committed
  log strictly after its position; sessions/timers are restored by the cluster.
- **Modes**: local restart = snapshot + log tail; wiped member = snapshot retrieval + log
  replay (3-member phase); zero-tail recovery proven on the single member.
- **Fail-closed**: truncated stream (end-of-stream before the END record), unknown format
  (including a legacy format-1 snapshot — cross-format restore is unsupported), unknown or
  out-of-order record types, any restored identifier at or beyond the restored generator, and
  any open order row that is off-grid or outside the restored band all throw during load — the
  service refuses to start.

## State matrix

Evidence columns: capture = record type in `writeSnapshot`; load = `onSnapshotRecord`; replay =
log tail through `onSessionMessage` (identical on every member and replay by construction —
ADR-045 removes all side-channel input). Tests: `AeronClusterSpikeTest` (cluster-level,
"spike"), `ClusterSnapshotCodecTest` (buffer-level, "codec").

| State item | Owner | Capture | Load | Load invariant | Behavioral proof | Verdict |
|---|---|---|---|---|---|---|
| Open order book incl. per-order reservations AND price-level FIFO membership | `MatchingEngine.ordersByRef` + per-security `LimitBook` | `T_ORDER` (open rows in ascending-ref = arrival order) | `bootstrapOrder` re-appends each open row into its band level | ref < restored generator; on-grid; inside restored band | spike: book/refs/fills across two recoveries; codec: `restoredBookPreservesPriceTimePriority` (post-restore crossing sweep reproduces identical fills, FIFO preserved) | Complete |
| Book geometry (band width + price grid) | service via engine (`bookLevels`/`bookTickPx`, config identity) | `T_HEADER` (format 2) | `adoptBookGeometry` before any book exists | must be identical across members | codec: `headerCarriesBookGeometryAndBandAnchorsRoundTrip`; `legacyFormatOneFailsClosed` | Complete |
| Per-security band anchor (`baseLevel`) | `LimitBook.baseLevel` | `T_BOOK` (before that book's order rows) | `bootstrapBook` | open rows must fall inside the restored band | codec: band-anchor round-trip; `openOrderOutsideRestoredBandFailsClosed` | Complete |
| `nextOrderRef` generator | service field, advanced at apply | `T_HEADER` | restore + assert | > every restored ref and `highestIssuedRef` | spike: zero-tail recovery issues 8 then 9, never reuses; codec: inconsistent header refused | Complete |
| `highestIssuedRef` | service field | `T_HEADER` | restore | pairs with generator assert | codec: `generatorNotAboveHighestIssuedFailsClosed` | Complete |
| Trade counter | `MatchingEngine.tradeCounter` | `T_HEADER` | `bootstrapTradeCounter` | monotonic max | spike: trades continue 2..8 across recoveries | Complete |
| Applied sequence (output correlation) | service field | `T_HEADER` | restore | none (correlation only) | spike egress carries it | Complete |
| Terminal retention ring — content AND eviction order | `MatchingEngine.terminalRing` | `T_ORDER` terminal rows written in eviction-FIFO order via `terminalOrderRefsFifo()` | `bootstrapOrder` re-marks in arrival order | n/a | codec: `terminalRetentionRestoresInEvictionFifoOrder` | Complete — **was Defect F1, fixed here** |
| Net positions | `PositionBook` | `T_POSITION` | `bootstrapPosition` | n/a | spike/codec state equality | Complete |
| Engine last TRADE prices (the mark) | `lastPxBySecurity` | `T_PRICE` | `bootstrapPrice` | n/a | spike: mark survives recovery; the mark is the last trade, ticks only seed it (ADR-051) | Complete |
| Risk policy (version, kill switch, limits) | `BlpRiskState` | `T_POLICY` | `bootstrapPolicy` | n/a | codec: policyVersion equality | Complete |
| Risk accounts (enabled, executed exposure) | `BlpRiskState` | `T_ACCOUNT` | `bootstrapAccount` | n/a | spike: executedNotional survives both recoveries | Complete |
| Risk securities (enabled, restricted, price freshness) | `BlpRiskState` | `T_SECURITY` | `bootstrapSecurity` | n/a | spike: post-recovery orders accepted (would reject UNKNOWN_SECURITY if lost) | Complete |
| Reservation aggregates | `BlpRiskState` account/exposure arrays | deliberately NOT captured | rebuilt via `reaccumulateReservation` from order rows | aggregates ≡ per-order rows by construction | spike: reservedNotional exact across snapshot+tail, zero-tail, duplicate-retry, and fill-out | Complete (derived by design, FR-IMRG21) |
| Idempotency keys/decisions/refs + retention (eviction) order | `BlpRiskState` | `T_IDEMPOTENCY` in retention order | `bootstrapIdempotency` in write order | n/a | spike: key retried after TWO recoveries answers original ref 2, reserves nothing; codec: `idempotencyEntriesSurviveRestore` | Complete |
| Idempotency evicted-history count (`idempotencyInsertions`/frontier) | `BlpRiskState` | not captured | resets to restored size | n/a | no external consumer | Non-authoritative (telemetry) |
| Symbol identity (ticker ↔ securityId) | `SymbolTable` at the ingress edge (`symbols.tab`) | not cluster state | — | — | — | **Defect F2 (open)**: see findings |
| Gateway control-feed admission state | `GatewayReplicaStore` etc., outside the deterministic core | excluded by ADR-045 split-readiness contract | — | admission waits for validity at/beyond recovery boundary | 3-member/kind phase | Needs ground-truth check (workstream 4) |
| Engine/risk sizing and limit constants | service constants (spike) | not captured | must be identical across members | — | 3-member phase | Needs ground-truth check (F3) |
| Engine/risk/hot-path telemetry counters | `HotPathMetrics`, `RiskMetrics`, engine counters | not captured | reset on recovery | n/a | — | Non-authoritative |
| Timer state | none registered (`onTimerEvent` empty) | n/a | cluster restores timers when used | — | revisit when EOD/time-driven logic lands | Non-authoritative (currently none) |

## Findings

- **F1 — terminal eviction order (Defect, FIXED this state).** Bounded terminal retention
  evicts the oldest retained terminal in transition order. Every prior snapshot layer restored
  terminal rows in ascending-ref order, silently reordering the ring: a recovered replica would
  later evict a different order than a never-restarted one and answer cancel-of-terminal
  differently (not-found vs returned-unchanged) — replicated-state divergence. Fixed by
  `MatchingEngine.terminalOrderRefsFifo()` (YU12 override; YU03 base verified byte-identical to
  the generated tree before extending) and FIFO-ordered terminal rows in `writeSnapshot`.
  Proof: `ClusterSnapshotCodecTest.terminalRetentionRestoresInEvictionFifoOrder` (ring `[3,1]`
  survives restore; ref-ordered restore would flip it).
- **F2 — symbol identity (Defect, OPEN — gateway workstream).** `securityId` assignment is
  first-seen at the ingress edge, persisted to a single `symbols.tab`. The consensus log stores
  numeric IDs, so the mapping is an admission dependency the cluster does not yet own: a
  gateway rebuilt without the file would remap tickers while the log's IDs keep their old
  meaning. Resolution when the gateway tier lands (ADR-047 scope): symbol registration becomes
  a sequenced ingress event so the mapping is replicated/snapshotted state, and gateways read
  it from the cluster. Until then the single-gateway file persistence carries it — the same
  guarantee as the parent state, unchanged by this workstream.
- **F3 — configuration identity (open check).** Pool sizes, risk limits, idempotency capacity,
  and now the book geometry (`BOOK_LEVELS`, `BOOK_TICK_PX`) shape deterministic behavior — a
  differing grid or band would place the same order at a different level or reject a limit one
  member accepts. Members with differing values would diverge on identical logs. Book geometry
  is mitigated for recovery by capturing it in the snapshot header (a restored member adopts the
  values its state was built with), but a freshly joining member with divergent config remains
  the 3-member phase's check (the parent state's schema-checksum handshake pattern applies).
- **F5 — resting book completeness (this state).** The book is now a future-output generator:
  a restored member must answer the next crossing order identically, so the snapshot serializes
  band geometry, band anchors, and every open row, and restore rebuilds each level's FIFO from
  ascending-reference (= arrival) order. An off-grid or out-of-band restored open row fails
  closed rather than silently landing in the wrong level. Proof:
  `ClusterSnapshotCodecTest.restoredBookPreservesPriceTimePriority` (a post-restore crossing
  sweep reproduces the source's exact fills) and `openOrderOutsideRestoredBandFailsClosed`.
- **F4 — duplicate retry consumes a generator value (accepted behavior).** A retried
  `ORDER_NEW` advances `nextOrderRef` before the engine answers from idempotency. This is
  deterministic on every member and replay, never reuses a reference, and costs only reference
  density. Proven: spike asserts the post-retry fresh order takes 9, not 8.

## Verdict

The deterministic core is **Complete** at single-member scope: every authoritative item has
capture/load/replay evidence and a behavioral proof, including the adversarial zero-tail case.
Open items are F2 (symbol identity — lands with the gateway tier), F3 (config identity check —
lands with the 3-member phase), and the promotion-continuity column of every row, which by
definition needs the 3-member cluster (`traderx-ha-recovery-proof` acceptance).
