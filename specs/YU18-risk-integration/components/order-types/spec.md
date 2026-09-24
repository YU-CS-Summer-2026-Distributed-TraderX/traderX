# Feature Specification: Order Types

**State**: `YU18-risk-integration` (component `order-types`)
**Integration branch**: `traderX-risk-integration`
**Created**: 2026-09-23
**Status**: Implemented locally (revision 3 design + implementation notes), READY_FOR_REVIEW; not deployed
**Input**: Delta over the YU17-otc-rates matcher, gateways, read model and console. YU18 overrides none of the operative files today.

Updated: 2026-09-23. Revision 3 answers the revision 2 review
(`coordination/eod-integration/review-evidence/ri01-design-r2-20260923/review.md`):

- **R2-1, peg and stop risk.** Risk prices are side-aware. A sell peg's cap is a floor, so its
  reservation is raised atomically when its executable price rises (FR-OT39). The claim that
  trigger re-decision bounds the stop gap is withdrawn; the limitation is documented (FR-OT16).
- **R2-2, reservations and FOK.** Iceberg replenishment transfers the existing reservation and
  never re-decides (FR-OT21). FOK preflight simulates every rule that affects a fill, so a
  feasible FOK cannot be refused after its first fill (FR-OT31). Internal revalidation is
  separate from submission idempotency (FR-OT38).
- **R2-3, sessions.** A sequenced business-date lifecycle owns DAY orders, so a late DAY_END for
  D1 can never cancel a D2 order (FR-OT08, FR-OT37).
- **R2-4, cascades.** A cascade bound is proven from finite state (FR-OT26), and exhaustion has an
  exact deterministic outcome (FR-OT40).
- **Also:**
  - the PRE_OPEN release tie-breaker is the original submission order (FR-OT11);
  - iceberg conservation (FR-OT20);
  - SC-OT29 is split so the existing PRE_OPEN→CLOSED queue cancel is preserved;
  - PEGGED and TRAILING_STOP are mapped over FIX in this delivery (FR-OT04);
  - percentage rounding and overflow rules, and derived-price validation shared with the UI
    (FR-OT41).

Revision 2 had already adopted the first review's four corrections: actual-trade provenance
(FR-OT09), legacy zero-price inference (FR-OT02), the active console (FR-OT32 to FR-OT34) and
latched, totally ordered cascades (FR-OT10 to FR-OT12).

Revision 3 was cleared for implementation (board 20260923T231403Z). The user approved OPEN-D5 as
proposed (20260923T231432Z). This spec now also records how the r3 review's three implementation
requirements were met:

- **Consensus limits** (FR-OT26): compile-time constants, recorded in every format-11 snapshot;
  a restore under different limits is refused.
- **Reachable exhaustion** (FR-OT40, SC-OT47): the queue is empty at the boundary by construction,
  and the fixture reaches the reduced limit from public commands.
- **Derived-price bounds and exact fixtures** (FR-OT41, SC-OT37/40/41).

Where the implementation settled a detail the design left open, the requirement now says what was
built. Implementation Notes at the end list those details.

The lane settled the ordinary design choices, and the Decisions table lists them for review.

- Owner: Claude order-types lane (RI-01).
- Audited tree: YU18-risk-integration rendered at d6ca3330.
- The composed `MatchingEngine`, `BlpRiskState`, `InputEvent`, `ClusterGatewayMain`,
  `FixGatewayAcceptor` and `MatchingEngineClusteredService` are the YU17-otc-rates layer. Line
  numbers below refer to it.

## User Stories

- As a trader, I want stop and stop-limit orders that stay out of the book until the market actually trades through my stop, so I can limit a loss or enter on a breakout.
- As a trader, I want a trailing stop that follows a favourable move and triggers on a reversal of a set amount or percentage.
- As a trader, I want an iceberg order that shows only part of its size, so a large order does not reveal its full size.
- As a trader, I want a pegged order that tracks this venue's best bid, best offer or midpoint within a cap I set.
- As a trader, I want Day, GTC, IOC and FOK to mean exactly what they say, and an unsupported combination to be refused.
- As a REST, FIX or console client, I want existing requests to keep working unchanged, and an instruction the venue cannot honour to be rejected, never silently turned into a different order.
- As the risk owner, I want every order type reserved and re-checked through the existing pre-trade gate, with no bypass through triggers, repricing or replenishment, and every known limit of that gate written down.
- As an operator, I want every new order state to survive snapshot, restart and failover, and to behave identically on every member and on replay.

## Support Matrix (as built, before this delivery)

Evidence classes:

- **T** = an existing test asserts it.
- **S** = read from source, not executed.
- **X** = executed during this audit.
- **—** = absent.

YU17 paths are relative to `specs/YU17-otc-rates/generation/runtime-overrides/`.

### Order types

| Type | Engine | REST gateway | FIX gateway | Active console (`web-front-end-console`) | Snapshot / replay |
|---|---|---|---|---|---|
| LIMIT | Supported (T): grid and collar checks, price-time priority, fills at the resting price. `LimitOrderBookTest.marketableLimitCrossesRestingOppositeAtRestingPrice`, `bestPriceFirstThenFifoWithinLevel` | `POST /orders {limitPrice > 0}` (S) | NewOrderSingle with Price(44) (S) | `ticket-panel.ts:403-407` posts `limitPrice` directly (S) | Resting orders snapshot and restore (T) |
| MARKET | Supported (T): `limitPx <= Px.NONE (0)` is market, **negative values included**. Fills against depth; the remainder is cancelled. `marketOrderFillsThenCancelsRemainder`; baseline `OrderTypesBaselineTest`, `OrderTypesSessionBaselineTest` | Implicit only: `limitPrice` omitted, 0 or negative (S) | **Defect F1 (X)** | Implicit: `limitPrice` starts at 0 and 0 is sent as market (S). No type selector. | Never rests |
| STOP, STOP_LIMIT, ICEBERG, PEGGED, TRAILING_STOP | — | — (unknown fields ignored, F2) | — (OrdType(40), StopPx(99), MaxFloor(111) and peg fields ignored) | — | — |

The legacy Angular `web-front-end` ticket (`order-ticket.component.ts:248`) refuses
`limitPrice <= 0`. It is not the active console and is out of scope.

### Time in force

There is no TIF field anywhere on the path: no `InputEvent` slot, REST reads none, and FIX
ignores TimeInForce(59).

- **Limit lifetime:** effectively GTC. A resting limit survives CLOSED and the reopen.
  - Measured by `OrderTypesSessionBaselineTest.aRestingLimitSurvivesACloseAndTradesAfterTheReopen`.
  - CLOSED is also the intraday **halt** (ADR-069), so it cannot double as an end-of-day rule.
- **PRE_OPEN queue:** PRE_OPEN→OPEN releases the queue in insertion order; PRE_OPEN→CLOSED
  cancels every queued order with SESSION_CANCELED (ADR-069 decision b,
  `MatchingEngineClusteredService.cancelQueue`) (S).
- **Market remainder:** cancelled, which is IOC-like (T).
- **IOC/FOK on a limit, and Day:** absent. A `timeInForce` field is ignored silently (F2).
- **Business date:** no replicated business date or trading-day state exists.

### Price and risk state

`lastPxBySecurity` is the **risk mark**, not a trade record. Its writers
(`MatchingEngine.java`):

- `cross()` at line 781: an actual match.
- Force-fill at line 1199: an operator action.
- `onPriceTick` at lines 1213-1214: seeds the mark while nothing has traded.
- `bootstrapPrice` at line 624: snapshot restore and sandbox-reset re-seed.

Ticket market trades (`onTradeNew`) execute at the mark and do not write it. **No state today
records whether any trade has happened.**

Risk facts this design depends on (S):

- `BlpRiskState.decideAndReserve` (lines 193-252) prices **both sides** as
  `quantity × validationPrice × multiplier` for order notional, credit and concentration. A
  higher price is more exposure for a sell as well as a buy.
- Lines 197-200: with a nonzero `clientOrderKey`, a remembered decision is returned **before any
  check or reservation**. With key 0 the decision is made in full and not remembered
  (`remember`, line 581).
- `MatchingEngine.marketValidationPx` (lines 730-737) validates a market order at the mark, or
  the opposite best when there is no mark. It does not look at the depth the order will walk.
- `consume` (line 381) releases reservation proportionally per fill; `release` (line 418) releases
  the remainder once; `reaccumulateReservation` (line 538) restores an exact tuple. `onReplace`
  (lines 1138-1148) already uses release, re-decide and exact restore on refusal (ADR-058).
- Fills are counted at the execution price. A sell aggressor fills at the bid, at or above its
  limit, so its executed notional can exceed its reservation today.

### Other surfaces

- **Read model:** trade-processor `OrderFeedHandler` writes the `orderbook` table. `OrderRow` and
  `OrderUpdate` come from the YU13 layer.
  - DDL: the YU17 `mariadb-init/initialSchema.sql` and the init ConfigMaps.
  - Schema validation is enforced (`SchemaValidationFailureAnalyzer`, YU16
    `SchemaMatchesShippedDdlIT`).
  - `OrderUpdate` ignores unknown JSON fields.
- **Composing lifecycle features (present and tested):**
  - atomic replace (ADR-058);
  - STP cancel-oldest (ADR-057), through `cancelUnsolicited`;
  - idempotent client keys;
  - PRE_OPEN queue and CLOSED refusal (ADR-069);
  - restriction cancels (FR-IMRG24);
  - sandbox reset (ADR-073);
  - snapshot format 10.
- **Wire:** SBE. `AeronReplicationCodec` refuses a block-length change, and every ORDER_NEW slot
  is used: `limitPx`, `priceTicks` (the idempotency key) and `side`.
  - New templates have precedent: `SymbolRegisterMessage` and `RiskExtractMessage`.
- **FIX dictionary:** the acceptor runs FIX.4.4 on QuickFIX/J 2.3.1 with its default data
  dictionary. That `FIX44.xml` carries, on both NewOrderSingle and OrderCancelReplaceRequest,
  ExecInst(18) (including `R` primary peg, `M` mid-price peg, `P` market peg, `a` trailing stop
  peg), the PegInstructions component (PegOffsetValue 211, PegMoveType 835, PegOffsetType 836,
  PegLimitType 837, PegRoundDirection 838, PegScope 840), OrdType `P`, MaxFloor(111), StopPx(99)
  and TimeInForce(59) (X, read from the jar's `FIX44.xml`).

### Findings

- **F1: FIX market orders are dropped with no report (executed).**
  - A NewOrderSingle with OrdType=1 and no Price produced no ExecutionReport and no session
    Reject, and never reached the submitter.
  - Control: a following limit order on the same session was acknowledged NEW.
  - Method: probe on the `FixGatewayStatusTest` harness, generated tree only, deleted after.
- **F2: REST and FIX silently ignore type, TIF and stop fields.**
- **F3: no state records whether anything has traded.** Every trigger and trailing requirement
  below depends on it (FR-OT09).

## Functional Requirements

### Vocabulary and boundary

- FR-OT01: **Types.** An order is exactly one of MARKET, LIMIT, STOP, STOP_LIMIT, ICEBERG, PEGGED
  or TRAILING_STOP. Each type's fields are:
  - **MARKET:** no price field.
  - **LIMIT:** `limitPrice`.
  - **STOP:** `stopPrice`.
  - **STOP_LIMIT:** `stopPrice` and `limitPrice`.
  - **ICEBERG:** `limitPrice` and `displayQuantity`.
  - **PEGGED:** `pegReference`, `pegOffset` and a `limitPrice` cap (FR-OT23).
  - **TRAILING_STOP:** either `trailAmount` or `trailPercentBps`.
  - Type and TIF are immutable for an order's life.
- FR-OT02: **Legacy inference, preserved exactly.** A request with no `orderType` behaves as today.
  - `limitPrice` omitted, null or `0` means MARKET.
  - `limitPrice > 0` means LIMIT with GTC.
  - This covers the console, which sends `limitPrice: 0` for a market order.
  - **Negative `limitPrice` with no `orderType`** is today silently a market order; it SHALL be
    rejected as INVALID. This is the only intentional legacy change (decision D2).
  - A legacy request SHALL NOT carry TIF or any new field. Sending one requires `orderType`.
- FR-OT03: **Strict typed validation.** When `orderType` is present, any of the following is
  refused:
  - a field not listed for the type;
  - a missing required field;
  - a non-positive or off-grid price;
  - an ineligible TIF;
  - a value outside the ranges of FR-OT41.

  Examples: `limitPrice` on MARKET, `stopPrice` on LIMIT, `displayQuantity >= quantity`. REST
  answers 422 with a named reason. FIX answers an ExecutionReport with OrdStatus=Rejected and
  Text. **Nothing is sequenced, and nothing is silently ignored.** Checks that need replicated
  state (the trade reference, the book, the business date) are made by the engine at admission
  and answered with a REJECTED order.

  **Presence and exactness (review I3).** The boundary judges what was SENT, before any
  conversion, so nothing is truncated, coerced from a string, or hidden behind a default zero:
  - a field not listed for the type is refused (`field not allowed for this orderType`) even when
    it is sent as `0` or `null`; a required price sent as `0` is a bad price, not a missing one;
  - numeric fields must be JSON numbers (`<field> must be a number`), and `orderType`,
    `timeInForce` and `pegReference` must be strings;
  - `quantity`, `displayQuantity`, `trailPercentBps` and `pegOffset` must be whole numbers
    (`<field> must be a whole number`; `3.0` is whole, `3.8` is not);
  - `limitPrice`, `stopPrice` and `trailAmount` carry at most 6 decimal places, the tick scale
    (`<field> allows at most 6 decimal places`);
  - a value too large for its field saturates and is refused by the FR-OT41 range check; it never
    wraps.

  The console applies the same rules with the same text. The approved untyped compatibility is
  unchanged: FR-OT02 still reads `limitPrice: 0` without `orderType` as MARKET.
- FR-OT04: **FIX mapping: all seven types in this delivery.**
  - OrdType(40): `1` = MARKET; `2` = LIMIT, or ICEBERG when MaxFloor(111) is present; `3` = STOP
    with StopPx(99); `4` = STOP_LIMIT with StopPx(99) and Price(44).
  - **OrdType `P`** with exactly one peg instruction in ExecInst(18):
    - `R` (primary peg) = PEGGED `PRIMARY`; `M` (mid-price peg) = PEGGED `MIDPOINT`.
      Price(44) is the required cap. PegOffsetValue(211) is the offset.
    - `a` (trailing stop peg) = TRAILING_STOP. PegOffsetValue(211) is the trail.
    - PegOffsetType(836): for a PEGGED order, `2` (ticks); a price offset (`0` or absent) is
      accepted only when it is zero, because the grid belongs to the engine and a gateway cannot
      convert a price to ticks. For a TRAILING_STOP, `0` or absent (a price amount) or `1` (basis
      points; it becomes `trailPercentBps`); ticks are refused for the same reason. The trail is
      the absolute value of PegOffsetValue(211).
    - ExecInst(18) must hold exactly one value.
    - Refused with a named Text: ExecInst `P` (market peg, FR-OT23) or any other or additional
      ExecInst value; PegScope(840) other than `1` (local), because this venue has no national or
      global reference; PegMoveType(835) `1` (fixed); PegRoundDirection(838) `1` (more
      aggressive), because rounding is always passive; PegLimitType(837) present; PegOffsetType
      `3`.
  - TimeInForce(59): `0` = DAY, `1` = GTC, `3` = IOC, `4` = FOK. Other values are refused.
  - **Untyped FIX stays untyped:** OrdType `1` or `2` with no TimeInForce, MaxFloor or ExecInst is
    sequenced exactly as before (template 1), so existing FIX flow is byte-for-byte unchanged.
  - OrderCancelReplaceRequest carries the same fields. It may change only FR-OT29's mutable
    fields; a different OrdType, ExecInst peg instruction or TimeInForce is refused with an
    OrderCancelReject.
  - **Units supported, and nothing else:**

    | Instruction | PegOffsetType(836) | PegOffsetValue(211) |
    |---|---|---|
    | PEGGED (`R`, `M`) | `2` (ticks) | a whole number of ticks |
    | PEGGED (`R`, `M`) | `0` or absent | only `0` |
    | TRAILING_STOP (`a`) | `0` or absent (price) | at most 6 decimal places; the sign is ignored |
    | TRAILING_STOP (`a`) | `1` (basis points) | a whole number, 1–5000; the sign is ignored |

    Every other combination is refused with a named Text. The seven type names alone do not
    imply every FIX unit variant.
  - **Exactness on typed FIX orders** follows FR-OT03: OrderQty(38), MaxFloor(111) and a tick or
    basis-point PegOffsetValue(211) must be whole; Price(44) and StopPx(99) carry at most 6
    decimals; MaxFloor, StopPx, Price or peg tags on a type that does not use them are refused
    even at `0`. Untyped FIX keeps today's conversion byte for byte (NFR-OT05).
  - F1 is fixed: every NewOrderSingle receives an ExecutionReport, including every refusal above.
- FR-OT05: **Console and REST parity.** REST accepts the typed fields of FR-OT01. `/replace`
  accepts the per-type mutable fields (FR-OT29) and refuses any change of type or TIF.

### Time in force and the trading day

- FR-OT06: **Eligibility.** Anything outside this matrix is refused by FR-OT03.

  | Type | DAY | GTC | IOC | FOK | TIF absent (typed) |
  |---|---|---|---|---|---|
  | MARKET | refused | refused | yes | yes | IOC |
  | LIMIT | yes | yes | yes | yes | GTC |
  | STOP / TRAILING_STOP | yes | yes | refused | refused | GTC |
  | STOP_LIMIT | yes | yes | refused | refused | GTC |
  | ICEBERG | yes | yes | refused | refused | GTC |
  | PEGGED | yes | yes | refused | refused | GTC |

  - For STOP, STOP_LIMIT and TRAILING_STOP the TIF governs the **pending life** and the life of
    the triggered order. A triggered STOP or TRAILING_STOP executes as MARKET, so its remainder
    is cancelled.
  - IOC or FOK on a *triggered* order is refused, not deferred (decision D6).
  - ICEBERG and PEGGED exist to rest, so IOC and FOK are refused for them.
- FR-OT07: **IOC** executes against eligible liquidity in the same apply, then cancels its
  remainder. **FOK** is atomic (FR-OT17, FR-OT31): fully filled in the same apply, or cancelled
  with no fill and no other effect.
- FR-OT08: **DAY orders belong to a business date.**
  - A DAY order is admitted only while a trading day is open (FR-OT37). Otherwise it is REJECTED
    with NO_TRADING_DAY. This covers admission from REST or FIX, a PRE_OPEN queue entry, and a
    replace.
  - On admission the order records `sessionDate` = the current business date. A replace keeps it.
  - A DAY order is expired only by the end of **its own** date: either a DAY_END for that date or
    the start of a later date (FR-OT37). Expiry cancels it with reason DAY_EXPIRED, releasing its
    reservation exactly once. This covers resting, pending-trigger, suspended and PRE_OPEN-queued
    DAY orders.
  - **Invariant I-S:** every live DAY order has `sessionDate` equal to the current business date,
    and that day is open. Consequently no command about an earlier date can reach a later date's
    order.
  - **Phase transitions never expire DAY orders**, because CLOSED is also a halt. PRE_OPEN→CLOSED
    keeps cancelling the whole PRE_OPEN queue, DAY or not, with SESSION_CANCELED, as today.
  - GTC, IOC, FOK and every legacy order never consult the business date.
- FR-OT37: **Business-date lifecycle.** Replicated state: `businessDate` (an integer `yyyymmdd`;
  0 means none) and `dayOpen`. Two sequenced controls on the existing control template change
  it, both with the `/session` admin credential. Each is acknowledged with its own egress kind
  (105). Every egress kind is unique, because the gateway routes acks by kind alone (review I1:
  sharing the sandbox reset's 104 sent reset acks to the business-day waiter). The gateway checks that the date is a valid
  calendar date; the engine only compares integers.
  - **SESSION_START {D}** (`POST /session/business-day`):
    - If `D > businessDate`, first expire every live DAY order (all of them belong to
      `businessDate` by I-S), then set `businessDate = D` and `dayOpen = true`. A missing or late
      DAY_END for the previous date is therefore harmless.
    - Otherwise it is acknowledged STALE and changes nothing.
  - **DAY_END {D}** (`POST /session/day-end`):
    - If `D == businessDate` and `dayOpen`, expire every live DAY order and set `dayOpen = false`.
    - If `D < businessDate`, or `D == businessDate` and the day is already ended, it is
      acknowledged STALE and changes nothing. **A late DAY_END(D1) after SESSION_START(D2) is
      therefore a no-op.**
    - If `D > businessDate` it is refused OUT_OF_SEQUENCE and changes nothing. A DAY_END never
      opens a date.
  - **Expiry order:** ascending orderRef. The PRE_OPEN queue's DAY entries are removed in the
    same pass, in queue order, after the book orders. Expiry of non-pegged orders can move a peg
    reference, so the cascade loop (FR-OT26) runs afterwards for each affected security in
    ascending security id.
  - **Phases are orthogonal.** CLOSED, PRE_OPEN and OPEN never change `businessDate` or `dayOpen`;
    SESSION_START and DAY_END are accepted in every phase.
  - **Initial state:** a fresh epoch, or a member that restores nothing, has `businessDate = 0`
    and `dayOpen = false`, so DAY orders are refused until the first SESSION_START. Sandbox reset
    keeps both, as it keeps the phase (ADR-073).
  - **Recovery:** `businessDate`, `dayOpen` and each DAY order's `sessionDate` are in the
    snapshot. A restore that finds I-S violated fails closed. A format-10 restore sets
    `businessDate = 0`.
  - The engine never reads a clock. A scheduler that issues these commands is optional and off
    by default (the ADR-069 pattern, decision D11).

### Trade reference, triggers and cascades

- FR-OT09: **Actual-trade reference.** Each security gets new replicated state, separate from the
  risk mark: `lastTradePx` plus a `hasTraded` flag.
  - **Qualifying prints:** each individual fill `cross()` produces, in execution order. That
    includes fills by triggered, pegged, replenished-iceberg and FOK/IOC orders.
  - **Not qualifying:** PRICE_TICK; snapshot or sandbox bootstrap of the mark; operator
    force-fill (decision D3); ticket market trades (`onTradeNew`); swap and swaption bookings.
  - The existing risk-mark semantics (`lastPxBySecurity`, ADR-051) are **unchanged**.
  - The reference is in the snapshot. A format-10 restore starts with `hasTraded = false`.
    Sandbox reset clears it, because it is session state.
- FR-OT10: **Latching per print.** On each qualifying print P for security S, in execution order:
  - a pending buy STOP or STOP_LIMIT latches if P ≥ `stopPrice`;
  - a pending sell latches if P ≤ `stopPrice`;
  - a TRAILING_STOP latches per FR-OT19.

  Latching appends the order to the **trigger queue**. **Once latched, an order is triggered:**
  no later print, in this command or any later one, can un-latch it.
- FR-OT11: **Total order.**
  - Orders latched by the same print are appended in ascending **admission sequence**, regardless
    of side.
  - The admission sequence is the log sequence of the command that **submitted** the order. A
    PRE_OPEN-queued order keeps the sequence of the command that queued it; the queue stores it.
    It does **not** take the shared sequence of the OPEN command that releases it. Every
    submission command carries one order, so the sequence is unique.
  - Orders latched by different prints are appended in print order.
  - The result is a total order on every member and on replay (decision D4).
- FR-OT12: **Drain.** The trigger queue is drained FIFO only after the current aggressor has
  finished all its matching, including an IOC or market remainder cancel and any iceberg step.
  - Each drained order converts (STOP and TRAILING_STOP to MARKET, STOP_LIMIT to LIMIT), passes
    collar revalidation and the internal risk revalidation of FR-OT38, then executes as an
    aggressor.
  - Its own prints latch more orders onto the queue tail.
  - A drained order that fails revalidation is REJECTED with that reason, and its reservation is
    released exactly once.
  - Every egress for an order that is not the command being applied (a trigger, its fills and
    remainder cancel, a peg reprice, a DAY expiry) carries the resting-update class, so the
    gateway never mistakes it for that command's answer.
  - Termination and bounds are FR-OT26.
- FR-OT13: **Admission against the trade reference.** A STOP or STOP_LIMIT whose stop is already
  through `lastTradePx` at admission is REJECTED with STOP_ALREADY_TRIGGERED (decision D1).
  - With `hasTraded = false` it is accepted as pending.
  - The risk mark is never used for this check.
- FR-OT14: **PRE_OPEN release.** Queued orders are released in insertion order, as today. Each
  released order is one **step** (FR-OT26); its cascade completes before the next is released.
  - A released stop is admitted by FR-OT13 against the trade reference **at its turn in the
    release**, so prints from orders released before it can make it already-through.
  - Prints during the release latch stops admitted earlier, and ties among them follow FR-OT11's
    original submission order.
  - Legacy and typed MARKET, IOC and FOK orders queued in PRE_OPEN execute at their turn,
    preserving today's queued-market behaviour.

### Risk

- FR-OT15: **One gate.** Every decision uses the existing `decideAndReserve` / `consume` /
  `release` path; there is no second risk model. Decisions happen at exactly these points:
  - every admission (with the submission's client key);
  - replace (with the replace's key, the existing ADR-058 path);
  - trigger conversion (FR-OT12, internal);
  - a sell peg whose executable price rises above its risk price (FR-OT39, internal).

  **Iceberg replenishment and buy-peg repricing never decide:** their reservation already covers
  them (FR-OT21, FR-OT39). An internal re-decision **releases, then re-decides** in the same apply
  (FR-OT38). On refusal a trigger ends REJECTED, a sell peg is SUSPENDED with its prior
  reservation restored exactly, and a replace leaves the order bit-identical.
- FR-OT16: **Reservation prices.** Exposure is `quantity × price` on both sides, so the
  reservation price must bound the order's own price from **above** on both sides.
  - LIMIT and ICEBERG reserve at `limitPrice` for the full remaining quantity, hidden included.
    STOP_LIMIT reserves at `limitPrice`.
  - PEGGED: FR-OT39.
  - MARKET reserves at `marketValidationPx`, as today.
  - **STOP and TRAILING_STOP (OPEN-D5, approved by the user 2026-09-23):** reserve at admission
    at `stopPrice` (TRAILING_STOP: the stop level at admission); at trigger, release and
    re-decide as a MARKET order at `marketValidationPx`. That decision may refuse. A STOP_LIMIT
    reserves and re-decides at its limit.
  - There is no multiplier.
  - **Limitation (not a guarantee).** Trigger re-decision does **not** bound the fill price.
    `marketValidationPx` is the mark, which the latching print has just set, or the opposite best
    when there is no mark. It is not the depth the order will walk. A buy stop triggered at 101
    with eligible asks 5@101 and 10@120 is decided at 101 and then fills 10@120. Executed
    notional can exceed the reservation. That is exactly today's MARKET behaviour, and the same
    holds for triggered STOP, typed MARKET, and MARKET IOC/FOK. A sell aggressor filling above
    its limit has the same property today. The approved policy accepts this limitation.
- FR-OT17: **FOK and risk.** A FOK is decided and reserved **once, at admission**, before its
  feasibility preflight (FR-OT31). A risk refusal is REJECTED with that reason. An infeasible
  FOK releases its reservation and ends CANCELED with FOK_UNFILLABLE. There are no prints.
- FR-OT38: **Internal revalidation is not submission idempotency.**
  - `decideAndReserve` with a nonzero client key returns a remembered decision before checking
    anything (lines 197-200). **Internal decisions (trigger conversion, sell-peg re-reservation)
    SHALL pass client key 0.** Every check runs, including the kill switch, account, entitlement,
    restriction, order size and notional, credit, position, concentration and staleness. Staleness
    uses the event time of the command being applied. Nothing is inserted into the idempotency
    store.
  - External duplicates keep today's path. A retried `clientOrderKey` maps to its original
    orderRef (`existingOrderRef`), and `onNewOrder` re-emits that order **as it now stands**:
    pending, triggered, filled, or REJECTED at trigger. It never creates a second order or a
    second reservation. A retried replace key maps to its one original replace decision.
  - Release-then-decide is atomic within the apply. On refusal the prior reservation tuple is
    restored exactly with `reaccumulateReservation`, as `onReplace` already does, except for a
    trigger, which ends REJECTED with nothing reserved.

### Trailing stop

- FR-OT18: **Admission.** Requires `hasTraded`; otherwise the order is REJECTED with
  TRAIL_REFERENCE_MISSING (decision D7).
  - The **watermark** starts at `lastTradePx`. It is the highest print since admission for a sell
    and the lowest for a buy.
  - The trail is either `trailAmount` (on the grid, greater than 0) or `trailPercentBps` (an
    integer from 1 to 5000, i.e. 0.01% to 50%).
- FR-OT19: **Stop level and update order.**
  - The effective trail `t` is `trailAmount`, or `floor(watermark × bps / 10000)` in engine
    price units (Px, 10⁻⁶) for a percentage. `t` is raised to at least one grid tick.
  - Sell: `stop = floorToGrid(watermark − t)`. Buy: `stop = ceilToGrid(watermark + t)`. Rounding
    is always away from the market, using non-negative integer division.
  - On each qualifying print P, the engine **first** tests the trigger against the current stop
    level and latches if crossed (FR-OT10). **Only if it did not latch** does it update the
    watermark with P and recompute the stop.
  - **Publication (F3, 2026-09-24).** When that recomputation changes the stop level, the engine
    emits an order update for the pending order carrying the new level, flagged as a resting
    update (never the command's direct answer), and sets the order's updated time to the command's
    sequenced event time. A watermark move that leaves the grid-rounded level unchanged emits
    nothing. This keeps FR-OT33's `stopprice` equal to the CURRENT level. It changes the output
    stream of commands that ratchet, so it is never rolled into a mixed-version cluster.
  - The watermark, trail and stop level are in the snapshot.
  - Overflow and non-positive stops are covered by FR-OT41.

### Iceberg

- FR-OT20: **Accounting and conservation.** At every point:
  - `quantity = cumulativeFilled + displayed + hidden`, and `remaining = displayed + hidden`;
  - `0 ≤ displayed ≤ displayQuantity`, and `hidden ≥ 0`.

  A fill reduces `displayed` (or, for an aggressing iceberg, `remaining`) and increases
  `cumulativeFilled`. A **new tranche**, at first rest or at replenishment, sets
  `displayed = min(displayQuantity, remaining)` and `hidden = remaining − displayed`. A
  partially consumed tranche is not recomputed.
  - Book depth, best bid/ask and the peg reference see **only `displayed`**. Hidden quantity is
    never matched while hidden.
  - As an **aggressor**, an iceberg behaves as a LIMIT for its full remaining quantity. Display
    applies only once it rests.
  - Replace: FR-OT29.
- FR-OT21: **Replenishment is a transfer, not a decision.** When `displayed` reaches 0 and
  `hidden > 0`, a new tranche is moved from `hidden` to `displayed` and appended to the **tail**
  of the same price level. This happens in the same match step, so a level holding hidden
  quantity never becomes empty.
  - The order stays one orderRef, with one reservation and one status. The reservation, taken at
    admission for the full remaining quantity at `limitPrice`, already covers the tranche.
    **Nothing is reserved again, nothing is released, and no risk decision runs**, so
    replenishment cannot be refused and cannot double count.
  - An aggressor still sweeping that level meets the level's other resting orders first, then the
    new tranche if it still has quantity left.
  - Replenishment emits no extra event: the fill that emptied the tranche is already the resting
    update. It is not a new order.
  - Decision D8 records the randomised-display alternative; it is not adopted.
- FR-OT22: **STP.** An aggressor that meets its own account's iceberg tranche cancels the
  **whole iceberg**, displayed and hidden, through `cancelUnsolicited` (ADR-057), releasing its
  full remaining reservation once.

### Pegged

- FR-OT23: **Reference.** The reference is **this venue's local book**, never NBBO. The type is
  named `PEGGED (local book)` in the API, UI and docs. `pegReference` is one of:
  - `PRIMARY`: the same-side best (the best bid for a buy);
  - `MIDPOINT`: `(bestBid + bestAsk) / 2`, rounded to the grid (buy down, sell up).

  Further rules:
  - `MARKET` (opposite-side) pegs are refused, because they are immediately aggressive.
  - The reference is computed from **non-pegged displayed liquidity only**, excluding this order
    and every other peg.
  - The signed `pegOffset`, in grid ticks, must not make the order more aggressive than the
    reference: buy ≤ 0, sell ≥ 0.
  - The `limitPrice` **cap** is required. A buy never prices above it (a ceiling). A sell never
    prices below it (a **floor**). The price is `clip(reference + offset × tick, cap)`.
  - **Consequence used by FR-OT26:** a peg is never marketable against non-pegged liquidity. The
    non-pegged book is uncrossed at rest, offsets are passive, midpoint rounding is passive, and
    the cap only makes a price less aggressive. A MIDPOINT buy and a MIDPOINT sell can meet each
    other when the midpoint is exactly on the grid; they then trade at that price.
- FR-OT39: **Side-aware peg risk.** Each peg carries a snapshotted `riskPx`, and its reservation
  is `remaining × riskPx`.
  - **Buy:** `riskPx = cap`. The cap is a ceiling, so it bounds every price the order can take.
    Buy repricing never decides.
  - **Sell:** the cap is a floor and bounds nothing from above. At admission,
    `riskPx = max(cap, executable price)`. On a reprice to a price p:
    - if `p ≤ riskPx`, it reprices with no decision. `riskPx` never goes down;
    - if `p > riskPx`, then before the order is placed at p it is re-reserved at p (FR-OT38,
      key 0, for `remaining`). If ACCEPTED, `riskPx = p` and the order is placed. If refused, the
      prior tuple is restored exactly and the order leaves the book as SUSPENDED with reason
      RISK:<reason>. It never stays at the old, lower price, because that would be more
      aggressive than its peg.
  - **Suspended for risk:** each later reprice evaluation re-applies the same rule. A target
    price at or below `riskPx` re-enters at the tail with no decision; a higher one tries the
    re-reservation again.
  - **Partial fills** consume the reservation proportionally (`consume`). The next re-reservation
    is for `remaining` only.
  - **Replace** releases and re-decides at the new `riskPx`: the new cap for a buy;
    `max(new cap, current target price)` for a sell. A refusal leaves the order bit-identical.
  - **Recovery:** `riskPx`, the per-order reservation tuple and the suspend reason are in the
    snapshot. Aggregates are rebuilt by `reaccumulateReservation`, as today.
- FR-OT24: **Missing reference.**
  - At admission, a missing reference means REJECTED with PEG_REFERENCE_MISSING. MIDPOINT needs
    both sides.
  - If the reference later disappears, or the derived price is not positive (FR-OT41), the order
    leaves the book and becomes **SUSPENDED** with reason REFERENCE: still live, still reserved.
  - When the reference returns, the order is re-evaluated by FR-OT39 and re-enters at the tail of
    its new level.
- FR-OT25: **Repricing.** In each cascade round (FR-OT26), for each security whose non-pegged best
  bid or ask changed, the engine walks that security's pegs in ascending admission sequence and
  computes each new price.
  - An unchanged price keeps its priority.
  - A changed price moves the order to the tail of its new level.
  - A repriced MIDPOINT peg that meets an opposite MIDPOINT peg executes against it as the
    aggressor, at its position in the walk.
- FR-OT26: **Cascade termination, with a proven bound.**
  - **Step.** A step is the processing of one admitted order, one cancel, replace or expiry
    pass, or one released PRE_OPEN order. Each command is one step, except the OPEN release,
    which is one step per released order. Each step runs its own matching, then **rounds** until
    nothing changes. A round (1) reprices the pegs of every security whose reference changed
    (FR-OT25), then (2) drains the trigger queue to empty (FR-OT12). A further round runs only if
    the previous round latched at least one order or changed at least one reference.
  - **Finite state at the start of a step**, per security: P pending orders (≤ `pendingCapacity`)
    and N non-pegged resting orders (≤ `orderCapacity`).
  - **Lemma 1, latches:** only a pending order can latch, each latches at most once, and a step
    creates at most one new pending order (its own admission). So latches L ≤ P + 1.
  - **Lemma 2, rests:** non-pegged liquidity is added only by the step's own order resting and by
    a drained STOP_LIMIT resting. A drained STOP or TRAILING_STOP never rests; replenishment never
    empties or creates a level (FR-OT21). So rests ≤ 1 + L.
  - **Lemma 3, reference changes:** a best price changes only when a non-pegged level empties or
    a non-pegged order rests at a new best price. Emptying needs a non-pegged order removed (fill,
    STP, expiry or cancel), and a removed order never returns within the step. Pegs are excluded
    from the reference, and by FR-OT23 never fill against non-pegged liquidity. So reference
    changes E ≤ (N + 1 + L) + (1 + L).
  - **Theorem:** rounds ≤ 1 + L + E ≤ 3P + N + 6. As built, the bound is computed when a step's
    cascade starts, from replicated state: P = pending orders in the security plus orders already
    latched, and N = every order in its book (an upper bound on non-pegged ones). The trigger
    queue has capacity `pendingCapacity + 1`, so it cannot overflow. Work per round is at most
    one reprice per peg plus the drains, so a step does finite work.
  - **Round rule, as built:** each round drains the queue to empty, so a latch is always consumed
    in the round that made it. A further round runs only if the non-pegged reference moved since
    this round's reprice. The count above still holds: every round after the first is paid for by
    a reference change.
  - **Consensus limits (review r3 item 1):** `pendingCapacity` (4096), `pegCapacity` (1024) and
    the round-limit override (0 = none) are compile-time constants in `OrderTypes`, like
    `REPLAY_ACCOUNT_BASE`: no environment variable can make two members differ. Tests lower them
    through a test-only engine seam. Every format-11 snapshot records them in T_ORDER_TYPES, and a
    restore under different values is refused. A positive override can only LOWER the per-step
    bound; tests use it to reach FR-OT40.
- FR-OT40: **Exhaustion outcome.** With no override this is unreachable by FR-OT26. The limit is
  checked at the top of a round, after the previous round drained the queue to empty, so at the
  boundary **the trigger queue is already empty** (review r3 item 2). What remains is a reference
  that moved again. On security S the engine then does the following, identically on every member
  and on replay:
  1. **Committed effects stand.** Prints, position updates, reservation consumption, trade
     reference and watermark updates, and every output already emitted in the step are kept.
     Nothing is rolled back.
  2. **Pegs are cancelled.** Every peg of S is CANCELED with CASCADE_LIMIT, in admission order,
     releasing its reservation once, because a peg that was not repriced could rest at a stale,
     crossed price.
  3. **Everything else is unchanged.** Unlatched pending orders stay pending, and other
     securities are unaffected.
  4. **The operator sees it.** The engine counts it (`cascadeLimitEvents`), and each cancelled peg's
     egress carries CASCADE_LIMIT.
  5. **The member keeps running.** No exception is thrown: an exception in the apply would stop
     every member at the same log position.
  6. **Defensive only:** a non-empty queue at the boundary cannot be reached through public
     commands; if one ever existed, its orders would be CANCELED CASCADE_LIMIT in queue order. The
     acceptance test does not manufacture that state.

### Lifecycle

- FR-OT27: **Pending and suspended states.** Two new statuses:
  - `PENDING_TRIGGER`: an untriggered STOP, STOP_LIMIT or TRAILING_STOP. It is never in the book
    and never matches.
  - `SUSPENDED`: a peg out of the book, with reason REFERENCE or RISK:<reason> (FR-OT24,
    FR-OT39).

  TRIGGERED is an emitted lifecycle event, not a status.
- FR-OT28: **Cancel.** Every open status is cancellable, including while CLOSED (ADR-069
  decision c). The reservation is released once.
- FR-OT29: **Replace** is atomic (ADR-058). Mutable fields per type:
  - LIMIT: quantity and price, as today.
  - STOP / STOP_LIMIT (pending): quantity, `stopPrice` and `limitPrice`.
  - TRAILING_STOP (pending): quantity and the trail value; the stop is recomputed from the
    current watermark and validated by FR-OT41.
  - ICEBERG: quantity, `displayQuantity` and `limitPrice`. The new quantity must exceed
    `cumulativeFilled`, and `remaining' = quantity' − cumulativeFilled`.
    - **Priority kept** (price unchanged, total quantity down, `displayQuantity' ≤
      displayQuantity`): `displayed' = min(displayed, displayQuantity', remaining')`.
    - **Otherwise** a new tranche is placed at the tail: `displayed' = min(displayQuantity',
      remaining')`.
    - In both cases `hidden' = remaining' − displayed'`, and FR-OT20's conservation holds.
  - PEGGED: quantity, `pegOffset` and the cap (risk per FR-OT39).

  Priority is kept only on a strict total-quantity size-down with every price unchanged. A
  replaced pending stop that is now through the trade reference is refused (FR-OT13), and the
  original stays bit-identical. **This includes TRAILING_STOP (review I2):** the watermark is
  the best print since admission, not the current one, so a tighter trail can put the recomputed
  stop at or behind the last trade. That replace is refused STOP_ALREADY_TRIGGERED, with equality
  counting as through (the trigger rule), before the reservation moves.
- FR-OT30: **After trigger or conversion,** fills, partial fills, STP, restriction cancels,
  idempotent retries and expiry behave exactly as for the converted MARKET or LIMIT. A triggered
  order is always the aggressor, so STP cancels the resting side, never it.
- FR-OT31: **FOK preflight simulates execution.** After the admission decision (FR-OT17) and
  before any mutation, the engine runs a read-only walk of **the same matching order the
  execution uses**: opposite levels best first, FIFO within a level, at acceptable prices.
  - **Own-account orders** add nothing, because STP cancels them rather than filling. An own
    iceberg adds nothing, displayed or hidden.
  - **Other accounts' orders** add their full `remaining`, hidden included. Replenishment only
    moves a tranche to the tail of the **same** level, and the walk continues through that level,
    so the level total is what the aggressor can take.
  - **Pegs** add at their current price. Repricing waits until the aggressor finishes
    (FR-OT26), so no peg moves during the sweep.
  - **Risk and triggers:** the aggressor's reservation is already taken. Fills only
    `consume`, and replenishment never decides (FR-OT21), so **no rule can refuse a fill after
    the first one**. Triggered orders are drained only after the aggressor finishes.
  - **Feasible** (the total is at least the quantity): the FOK executes normally and is fully
    filled; own orders it meets are STP-cancelled as today.
  - **Invariant:** execution never fills less than the preflight promised. A shortfall would be
    an engine defect, not a valid partial FOK: it is counted (`fokInvariantBreaches`, on the
    member's surface), the remainder is cancelled so nothing rests, and every FOK test asserts
    the counter stays 0.
  - **Infeasible:** no prints, no STP cancels, no replenishment. The reservation is released and
    the order ends CANCELED with FOK_UNFILLABLE.

### Validation of derived prices

- FR-OT41: **Ranges, rounding and overflow.**
  - Every price, stop, cap and trail is a positive integer in Px units, at most
    `MAX_PRICE_TICKS = Long.MAX_VALUE / 10_000`. Because every print comes from an admitted
    price, every watermark and reference is also within that bound.
  - Every product and sum on the order path (`watermark × bps`, `offset × tick`,
    `bestBid + bestAsk`, `quantity × price × multiplier`) uses exact arithmetic. With the bound
    above, overflow is impossible for watermark, offset and midpoint. The existing risk path
    already refuses a notional overflow with ORDER_NOTIONAL.
  - `trailPercentBps` is an integer from 1 to 5000, and `|pegOffset|` is at most 10 000 grid
    ticks.
  - **Derived prices outside (0, MAX_PRICE_TICKS] are refused** (review r3 item 3: the maximum
    applies to derived prices too, which can exceed it without overflowing):
    - a TRAILING_STOP whose stop is outside the range is REJECTED TRAIL_INVALID at admission or
      replace. For a sell, `floorToGrid(watermark − t) ≤ 0` (for example `trailAmount ≥` the
      watermark); for a buy, `ceilToGrid(watermark + t) > MAX_PRICE_TICKS`. Later: a sell stop
      only rises and a buy stop only falls, so a later update cannot leave the range; if a
      recomputation ever did, the watermark and stop are left unchanged (deterministically);
    - a peg whose derived price is outside the range is REJECTED PEG_PRICE_INVALID at admission,
      and is SUSPENDED with reason REFERENCE if that happens later.
  - **UI parity:** REST 422 reasons, FIX Text and engine REJECTED reasons come from one reason
    table with one wording. The console runs every stateless check before submitting (positive,
    on grid, ranges, eligibility). For the checks that need sequenced state it shows the last
    trade as a hint (for example "trail must be below the last trade 100.37") and then displays
    the engine's reason verbatim. The engine is authoritative.

### Surfaces

- FR-OT32: **Console** (`web-front-end-console/src/app/ticket-panel.ts` and its order views).
  - An explicit order-type selector, showing per type: limit, stop, display quantity, peg
    reference, offset and cap, and trail type and value.
  - A TIF selector filtered by FR-OT06.
  - The existing untyped Direct submission is kept byte-for-byte as the legacy default.
  - STOP is submitted with **no** `limitPrice`.
  - Client-side validation per FR-OT41.
- FR-OT33: **Read model.** `OrderUpdate`/`OrderRow` and the `orderbook` table gain `ordertype`,
  `timeinforce`, `stopprice` (a TRAILING_STOP's CURRENT level), `displayquantity`,
  `pegreference`, `pegoffset`, `pegcap`, `trailamount`, `trailpercentbps`, `triggered`,
  `sessiondate`, `suspendreason` and `reason`. The status CHECK gains PENDING_TRIGGER and
  SUSPENDED, and the open-order query returns both.
  - The DDL is updated with **an explicit migration for existing databases**; first-boot init SQL
    alone is not enough on a persistent volume.
  - `SchemaMatchesShippedDdlIT` must pass.
  - Legacy rows read as `LIMIT`/`MARKET` and `GTC`/`IOC`, derived from their price.
- FR-OT34: **Blotter and REST GET** show the type, TIF, PENDING_TRIGGER, SUSPENDED and its reason,
  the triggered event and the DAY_EXPIRED, FOK_UNFILLABLE, NO_TRADING_DAY and CASCADE_LIMIT
  reasons. The business-date POSTs answer `{businessDate, command, outcome, sequence}`.

### Recovery

- FR-OT35: **Snapshot format 11** adds:
  - per-order extension records: type, TIF, stop, trail and watermark, `cumulativeFilled`,
    display and hidden, peg parameters, `riskPx`, the suspend reason and `sessionDate`;
  - the pending-trigger store, in admission-sequence order;
  - the per-security trade reference (FR-OT09);
  - `businessDate` and `dayOpen` (FR-OT37);
  - the PRE_OPEN queue tuple, widened for typed orders and their original admission sequence.

  As built:
  - **T_ORDER_EXT** immediately precedes each typed order's T_ORDER row.
  - **T_OT_SECURITY** carries `{securityId, lastTradePx, hasTraded, pegRefBid, pegRefAsk}`.
  - **T_ORDER_TYPES** carries `{businessDate, dayOpen, pendingCapacity, pegCapacity, roundLimit}`,
    is always written, and is required in format 11.
  - **T_QUEUED_ORDER** has 17 columns in format 11; formats 9 and 10 read their 8.
  - **Open rows are written in book-append order**, so every level's FIFO is restored exactly,
    including orders that replenished, repriced or were replaced to a tail. This also fixes the
    FIFO of an untyped order replaced to the tail of its level, which ascending-ref order lost.
    Pending and suspended orders follow, and their store order is re-derived from the admission
    sequence.

  Rules:
  - The trigger queue is always empty at a snapshot boundary, and the writer refuses otherwise.
  - A restore asserts I-S (FR-OT08) and FR-OT20's conservation, and fails closed on a violation.
  - A format-10 restore turns every order into LIMIT/MARKET with GTC, holds no pending orders,
    sets `hasTraded = false` and `businessDate = 0`.
  - Unknown formats fail closed.
  - A restored member produces **byte-identical egress** to a never-restarted one for the same
    log suffix.
- FR-OT36: **Wire.**
  - A new SBE template, `OrderInstructionMessage`, carries typed new-order and typed replace.
  - `InputEventMessage` and legacy ORDER_NEW/REPLACE are unchanged, so old journals replay
    unchanged.
  - SESSION_START and DAY_END are new control types on the existing template.

## Non-Functional Requirements

- NFR-OT01: **Determinism.** No wall clock, no hash-iteration order and no floating point in the
  apply path. The same log produces byte-identical egress on every member and on replay.
- NFR-OT02: **Allocation.** The steady-state hot path stays allocation-free (the NFR-LOB02 gate).
  Stores are pooled and preallocated; latching, draining, repricing, replenishment and the FOK
  preflight allocate nothing.
- NFR-OT03: **Bounded consumers.** The pending store, the peg store and the trigger queue have
  configured capacities.
  - Admission beyond capacity is refused with CAPACITY.
  - The trigger queue is sized `pendingCapacity + 1`, and cascades are bounded by FR-OT26's
    proven `cascadeRoundBound`, never by truncation. Exhaustion of a lowered limit follows
    FR-OT40.
- NFR-OT04: **Rollout.** This is a deterministic-core, wire and snapshot-format change. It is never
  rolled gradually across members. A live rollout needs a snapshot barrier or a fresh epoch, and
  separate authorization.
- NFR-OT05: **Backward compatibility.** Legacy REST, console and FIX limit requests, old journals
  and format-10 snapshots behave as before. The two exceptions are FR-OT02's negative-price
  rejection and the F1 fix. Legacy orders never consult the business date.
- NFR-OT06: **Latency.** The existing match-latency histograms must show no regression for legacy
  flow when no new-type orders are present. A measured before/after is required, not assumed.

## State Transitions

```text
admission ─ refused (FR-OT03/13/17/18/24/41, NO_TRADING_DAY, capacity, CLOSED) ─────► REJECTED
    │ PRE_OPEN → QUEUED ─(OPEN: admitted at its turn)─┐
    │   QUEUED ─ PRE_OPEN→CLOSED ─► CANCELED(SESSION_CANCELED); DAY expiry ─► CANCELED(DAY_EXPIRED)
    ▼                                                 ▼
 STOP/STOP_LIMIT/TRAILING ─► PENDING_TRIGGER ─ latched (print) ─► queue ─ drained ─► TRIGGERED(event)
     │ cancel / DAY expiry / restriction / reset ─► CANCELED    │ revalidation fails ─► REJECTED
     │                                   queue at FR-OT40 ─► CANCELED(CASCADE_LIMIT)   ▼
 PEGGED ─► NEW (priced) ⇄ SUSPENDED(REFERENCE | RISK)            MARKET / LIMIT lifecycle
 ICEBERG ─► NEW (displayed tranche; replenish to tail)
 LIMIT(IOC/FOK), MARKET ─► executes now ─► FILLED | CANCELED (remainder / FOK_UNFILLABLE)
 all resting ─► PARTIALLY_FILLED ─► FILLED | CANCELED (cancel, DAY expiry, STP, restriction, CASCADE_LIMIT)

 business date:  (0, closed) ─SESSION_START D─► (D, open) ─DAY_END D─► (D, ended) ─SESSION_START D'>D─► (D', open)
                 (D, open) ─SESSION_START D'>D─► expire DAY orders ─► (D', open)
                 stale dates ─► acknowledged, no change;  DAY_END D'>D ─► OUT_OF_SEQUENCE
```

## Success Criteria

Each case becomes a source test, and the same tests run on the generated tree. Unless stated:
one security on a 0.01 grid, accounts A, B, C and D distinct, and the price in parentheses is
the last *trade*. Notional is written in price units for readability; tests assert exact ticks.

**Legacy and boundary**

- SC-OT01: Console-shaped `{limitPrice: 0}` with no type. Expected: MARKET, exactly as today; the
  baseline tests stay green.
- SC-OT02: `{limitPrice: -1}` with no type. Expected: 422 INVALID, nothing sequenced.
- SC-OT03: Typed MARKET with `limitPrice`; LIMIT with `stopPrice`; STOP_LIMIT without
  `limitPrice`; ICEBERG with `displayQuantity ≥ quantity`; a PEGGED MARKET reference; MARKET with
  GTC. Expected: 422 for each, nothing sequenced.
- SC-OT04: FIX OrdType=1 with no Price (F1). Expected: an ExecutionReport.
- SC-OT05: An old journal and a format-10 snapshot replay into this build. Expected:
  byte-identical legacy egress, `hasTraded = false` and `businessDate = 0`.

**Trade reference and triggers**

- SC-OT06: Tick 105 and a bootstrap mark with no trades, then a buy STOP at 101. Expected:
  accepted and PENDING (the mark is not the reference). A further tick of 110 fires nothing.
- SC-OT07: (100) A buy STOP at 101; B asks 5@101 and 10@102; C buys 1@101. Expected: A
  triggers and fills 4@101, then 6@102.
- SC-OT08: (100) A buy STOP at 99. Expected: REJECTED STOP_ALREADY_TRIGGERED.
- SC-OT09 **(latch and reversal):** (100) A sell STOP at 99; one aggressor prints 99, then 101,
  as it sweeps. Expected: the stop latches on the 99 print and still triggers after the 101
  print.
- SC-OT10 **(mixed sides, same print, no prior trade):** With `hasTraded = false`, a buy STOP at
  100 (admitted first) and a sell STOP at 100 (second) are both accepted as pending; with a last
  trade of 100, both would be REJECTED STOP_ALREADY_TRIGGERED instead. The first print is at
  100. Expected: it latches both, queued buy then sell, with identical egress on two engines.
- SC-OT11 **(cascade):** Sell stops at 99 and 98, qty 1 each; bids 1@99 and 5@98; D sells 1@99.
  Expected: both fire in one apply.
- SC-OT12: A stop-limit gaps past its limit. Expected: it rests as LIMIT. A STOP gaps into thin
  bids. Expected: it fills what exists and cancels the remainder.
- SC-OT13: The account limit is lowered while a stop is pending, then the stop triggers.
  Expected: REJECTED CREDIT_LIMIT, and the account's reserved notional returns to its exact value
  before the stop's admission.
- SC-OT14 **(PRE_OPEN):** In PRE_OPEN, queue a sell LIMIT and a buy MARKET that trade at 102,
  then a buy STOP at 101. Expected: at OPEN the stop is REJECTED STOP_ALREADY_TRIGGERED at its
  turn. With the stop queued first instead, it is admitted and then latched by the 102 print.
- SC-OT36 **(PRE_OPEN tie-breaker):** No prior trade. A pending sell STOP at 149 (A). In
  PRE_OPEN, queue a buy STOP at 149 (B), a sell STOP at 149 (C), a sell LIMIT 1@149 and an untyped
  buy MARKET 1. OPEN releases them all in one apply. Expected: both queued stops are admitted as
  pending, and the 149 print latches A, then B, then C, regardless of side. B and C carry their own
  queue-time sequences (unique, ascending, below the OPEN's sequence), not the shared release
  sequence.
- SC-OT37 **(stop gap limitation, pinned):** (100) A buy STOP 15 at 101; asks 5@101 and 10@120;
  C buys 1@101. Expected: the trigger decision is at 101 (the mark after the latching print), so
  15 × 101 = 1515.00 is reserved. The stop fills 4@101 and 10@120 (C took 1@101), and the last 1
  is cancelled. Executed notional is 1604.00 against that 1515.00 reservation. A legacy MARKET
  order of 15 submitted at the same point produces the same normalized fills (price and quantity
  per fill) and the same executed notional; whole-lifecycle egress differs by design, because a
  stop adds its pending and trigger events. This test pins the documented limitation of FR-OT16;
  it is not a guarantee.

**Risk revalidation and idempotency**

- SC-OT38 **(state change between accept and trigger):** A buy STOP pending with a known
  reservation R.
  - (a) The kill switch goes on, then a print latches the stop. Expected: REJECTED KILL_SWITCH,
    no fill, R released once, and the account's reserved notional back to its value before the
    stop was admitted.
  - (b) The same with the account disabled, then with the credit limit lowered below the market
    notional. Expected: ACCOUNT_DISABLED, then CREDIT_LIMIT, with the same exact release.
  - (c) The same stop with limits unchanged. Expected: ACCEPTED at trigger and the idempotency
    store's size is unchanged by the trigger decision.
- SC-OT39 **(duplicate retries):** The original `clientOrderKey` of the stop is retried (1) while
  pending, (2) after it triggered and filled, (3) after it was REJECTED at trigger. Expected:
  each retry re-emits the order as it now stands, creates no order and changes no reservation.
  A retried replace key re-emits its one original replace outcome.

**Trailing**

- SC-OT15: (100) A sell TRAILING_STOP with trail 2.00; prints 103, 101.50, then 100.90. Expected:
  the watermark reaches 103, the stop reaches 101.00, and the order latches on 100.90.
- SC-OT16: A sell trail of 150 bps with the watermark at 100.37. Expected:
  `t = floor(100 370 000 × 150 / 10 000) = 1 505 550` Px (1.50555), the stop is
  `floorToGrid(98.86445) = 98.86`, and a print exactly at 98.86 latches it. A 1 bps trail at 0.50
  gives `t = 0.00005`, which is raised to one grid tick (0.01).
- SC-OT17: One print is both a new high and a crossing, for opposite-side trailing orders.
  Expected: FR-OT19's check-then-update order holds for each.
- SC-OT40 **(derived-price validation):** (5.00) A sell TRAILING_STOP with `trailAmount` 5.00, and
  one with 6.00. Expected: both REJECTED TRAIL_INVALID. `trailPercentBps` 0 and 5001. Expected:
  422. A PRIMARY sell peg whose reference plus offset is not positive. Expected: REJECTED
  PEG_PRICE_INVALID. The console shows the same reason wording for each.

**Iceberg**

- SC-OT18: An ICEBERG of 100 with display 10 at 100.00, another 10@100.00 resting behind it, and
  an aggressor buying 25. Expected: 10 from the iceberg, 10 from the other order, then 5 from
  the replenished tranche (replenishment goes to the tail).
- SC-OT19: Depth, BBO and the peg reference show only the 10 displayed. A size-down replace from
  100 to 60 keeps priority; a larger display loses it.
- SC-OT20: An own-account aggressor meets an iceberg tranche. Expected: the whole iceberg is
  CANCELED and its full reservation released.
- SC-OT21 **(FOK × iceberg):** A FOK buy of 50 against an iceberg with 10 displayed and 40
  hidden, at an acceptable price. Expected: feasible, fully filled. A FOK of 51 is CANCELED
  FOK_UNFILLABLE, with no prints and no STP side effects.
- SC-OT41 **(tight-credit FOK across tranches):** B's sell ICEBERG 30, display 10, at 100.00; C's
  sell 10@100.00 behind it; A's own sell 5@100.00 behind that. A's credit is exactly 3500.00: the
  own sell reserves 500.00, leaving 3000.00 for the FOK. No price ticks, so staleness is not in
  play.
  - A FOK buy of 30. Expected: fills 10 (B), 10 (C), then STP-cancels A's own 5 (it is ahead of
    the replenished tranche, which went to the tail), then fills 10 (B's new tranche). There is
    no risk refusal after the first fill, and A's reservation is fully consumed.
  - With credit one Px unit short (3499.999999). Expected: REJECTED CREDIT_LIMIT, no prints, book
    unchanged.
  - A FOK buy of 41 with ample credit. Expected: CANCELED FOK_UNFILLABLE (40 available from other
    accounts). No prints, no STP cancel of A's order, no replenishment, and the reservation
    released exactly.
- SC-OT42 **(conservation):** An ICEBERG of 100, display 10. Partial fill 4, then fill 6
  (replenish), then fill 3, then a replace to 70, then a replace to 90 with display 20. Expected:
  `quantity = cumulativeFilled + displayed + hidden` after every step; `displayed` is 6 and not
  recomputed after the partial fill; the second replace places a new tranche of
  `min(20, 90 − 13) = 20` at the tail.

**Pegged**

- SC-OT22: A PRIMARY buy peg with offset 0 and cap 101, while the bid moves 100 → 100.20 → 102.
  Expected: prices 100, then 100.20, then **capped at 101**. Priority is kept when the price is
  unchanged and lost when repriced. The reservation stays at `quantity × 101` throughout, with no
  decision.
- SC-OT23: A MIDPOINT peg over 100.00/100.03. Expected: a buy at 100.01 (rounded down) and a
  sell at 100.02.
- SC-OT24: The bid side empties. Expected: the PRIMARY buy becomes SUSPENDED(REFERENCE), and
  re-enters at the tail when a bid returns.
- SC-OT25: Two pegs and a limit at one level. Expected: the reference ignores the pegs, and the
  reprice walk runs in admission order.
- SC-OT26: A MIDPOINT buy and sell peg meet on an on-grid midpoint; their print latches a stop, and
  the stop's fill moves the reference. Expected: the loop terminates within `cascadeRoundBound`,
  with identical egress on two engines.
- SC-OT43 **(sell peg rising through credit):** A's credit is 1009.00. C asks 1@100.50, 1@100.80
  and 1@101.00. A's PRIMARY sell peg of 10, floor 100.00, offset 0.
  - Admission. Expected: priced 100.50, `riskPx` 100.50, reserved 1005.00.
  - C cancels 100.50. Expected: re-reserved before the move, priced 100.80, reserved 1008.00.
  - C cancels 100.80 (reference 101.00; 10 × 101 = 1010 > 1009). Expected: SUSPENDED(RISK) with
    reason CREDIT_LIMIT, out of the book (best ask 101.00 is C's), reserved exactly 1008.00.
  - C asks 1@100.70. Expected: re-enters at 100.70 with no decision; reserved 1008.00.
  - B buys 3@100.70: C's 1, then 2 from the peg. Expected: the peg keeps 8; reserved 806.40 (1008
    × 8/10). The non-peg best is 101.00 again, and 8 × 101 plus 201.40 executed exceeds 1009, so
    the peg is SUSPENDED again with 806.40 restored exactly.
  - Replace to quantity 8 (6 remaining) with floor 100.60. Expected: re-decided at
    `max(100.60, 101.00)`, reserved 606.00, priced 101.00.
  - Control: a buy peg while the bid moves is never re-decided: its reservation stays quantity ×
    cap, and nothing is added to the idempotency store.
  - Snapshot and restore of RISK-suspended and active pegs is covered by SC-OT31.

**TIF and the trading day**

- SC-OT27: An IOC LIMIT partially fills. Expected: the remainder is cancelled; nothing rests.
- SC-OT28: A FOK with own-account orders in its path. Expected: feasibility excludes them, and an
  infeasible FOK leaves them untouched.
- SC-OT29 **(halts are not expiry):** SESSION_START D1; DAY and GTC LIMITs, a DAY pending STOP and
  a DAY peg. CLOSED, then OPEN. Expected: all four survive unchanged, and so do their
  reservations.
- SC-OT44 **(PRE_OPEN queue, existing behaviour preserved):** SESSION_START D1. In PRE_OPEN, queue a
  DAY LIMIT and a GTC LIMIT.
  - PRE_OPEN→CLOSED. Expected: both CANCELED SESSION_CANCELED (decision b, unchanged).
  - Repeat, then PRE_OPEN→OPEN. Expected: both are admitted at their turn.
  - Repeat, then DAY_END D1 while still PRE_OPEN. Expected: only the DAY entry is removed,
    DAY_EXPIRED.
- SC-OT45 **(business-date ownership):** SESSION_START D1; A's DAY LIMIT and DAY STOP; B's GTC LIMIT.
  - SESSION_START D2 with no DAY_END D1. Expected: A's D1 orders DAY_EXPIRED in orderRef order,
    each reservation released once; B's order untouched.
  - A DAY order submitted in D2. Expected: `sessionDate = D2`.
  - A late DAY_END D1. Expected: acknowledged STALE; the D2 order untouched.
  - DAY_END D2. Expected: the D2 order DAY_EXPIRED. A repeated DAY_END D2 is STALE.
  - DAY_END D3. Expected: OUT_OF_SEQUENCE, no change.
  - A DAY order after DAY_END D2. Expected: REJECTED NO_TRADING_DAY. A GTC order is accepted.
  - SESSION_START D2 again. Expected: STALE.
- SC-OT30: The SC-OT45 sequence with a snapshot and restore between every pair of commands.
  Expected: identical egress to a never-restarted engine, including the STALE and
  OUT_OF_SEQUENCE answers.

**Cascades**

- SC-OT46 **(bound, non-vacuous):** Consensus limits lowered to pendingCapacity 3 and pegCapacity 2.
  After a print at 100.50: C bids 1@100.00, F bids 1@99.00 and 1@98.00, A's PRIMARY buy peg (cap
  101) prices at 100.00, D has sell STOPs at 100.00 and 90.00. E sells 1@100.00.
  - Expected: C fills (print 100) and latches D's 100 stop. Round 1 reprices the peg to 99.00 and
    drains the stop into F@99.00. Round 2 reprices the peg to 98.00. Exactly two rounds run,
    within 3P + N + 6.
  - Expected: the 90 stop stays pending, the queue ends empty, and no exhaustion is counted.
  - A fourth pending stop is refused CAPACITY at admission, never by truncating a cascade.
- SC-OT47 **(exhaustion, reachable):** The same public commands with the round-limit override
  lowered to 1. Expected, exactly per FR-OT40:
  - round 1's fills stand;
  - the queue is already empty at the boundary;
  - the peg is CANCELED CASCADE_LIMIT with its reservation released;
  - the 90 stop is still pending, and D's reservation is exactly its 90.00;
  - one exhaustion is counted, and the same commands replay to identical egress.

  The consensus-limit refusal of review r3 item 1 is its own test: a snapshot taken under the
  default limits is refused by a member holding other limits, and the same stream restores and
  replays identically under the same limits.

**FIX**

- SC-OT48: FIX NewOrderSingle mappings (plain OrdType 1/2 with no TimeInForce stays untyped):
  - OrdType `P` + ExecInst `R`, and + `M`, with Price. Expected: PEGGED PRIMARY and MIDPOINT.
  - OrdType `P` + ExecInst `a` with PegOffsetType `1`. Expected: TRAILING_STOP in basis points;
    without PegOffsetType, a price amount.
  - OrdType `3` with TimeInForce `0`, `4`, and `2` with MaxFloor. Expected: STOP DAY, STOP_LIMIT,
    ICEBERG.
  - Refused with a named Text: ExecInst `P`, PegScope `2`, PegMoveType `1`, PegRoundDirection `1`,
    PegLimitType, no ExecInst, two ExecInst values, a non-zero price-type peg offset, a Price on
    OrdType `1`, TimeInForce `6`, OrdType `5`.
  - Over a real session: a Price-less market order is answered NEW (F1), a market peg is answered
    Rejected with nothing sequenced, and an unfillable FOK is answered CANCELED with Text
    FOK_UNFILLABLE. A typed OrderCancelReplaceRequest is routed as a typed replace; the engine
    refuses a change of type (SC-OT33).

**Recovery, duplicates and replace**

- SC-OT31: A full mixed book → snapshot → restore, through the service. The snapshot completeness
  audit's rich fixture carries:
  - an open business date and a pending DAY stop;
  - a trailing stop with a watermark;
  - an iceberg with hidden quantity;
  - an active peg and a REFERENCE-suspended peg;
  - a typed stop-limit waiting in the PRE_OPEN queue;
  - the trade reference.

  Expected:
  - the restored member re-writes a byte-identical stream;
  - the same follow-on commands reach the same state on both. Those commands are: the open, a
    print that latches the stop and the released stop-limit into the iceberg's tranches, a bid
    that moves the peg, and DAY_END;
  - the halted queue releases identically.

  A separate case restores a RISK-suspended peg (a refused re-reservation, via a disabled
  account). Its typed state, `riskPx` and reservation must come back exactly, and the follow-on
  must produce byte-identical egress: re-enable, re-entry below `riskPx`, a fill, a reprice.
- SC-OT32: A duplicate clientOrderKey for each of the seven types. Expected: the retry answers the
  original order, creates no second order and changes no reservation.
- SC-OT33: Replace each type's mutable fields; a change of type or TIF is refused; a refused
  replace leaves the order bit-identical.
- SC-OT34: The allocation gate over admit, latch, drain, reprice, replenish and FOK preflight.
  Expected: 0 bytes in steady state.
- SC-OT35: Legacy-only flow, match latency before and after (NFR-OT06). Expected: measured, with
  no regression beyond run-to-run variance.

**Review corrections (2026-09-24)**

- SC-OT49: Egress-kind audit and ack routing. Every `KIND_*` in OutputEvent and the service is
  unique, and the sandbox reset keeps 104. Business-day, sandbox-reset and session-phase acks,
  interleaved through the gateway's real `onEgress`, each set only their own waiter, with their
  own request id.
- SC-OT50: Trailing replace against a moved market (sell: trade 110, trail 5 → stop 105, trade
  108; buy mirrored at 100/102). Trails that put the stop through the last trade, or exactly at
  it, are refused STOP_ALREADY_TRIGGERED, and so is a same-key retry. The order and the account
  aggregates are bit-identical after each refusal. A trail that stays behind the market is
  accepted and re-reserves at the new stop.
- SC-OT51: Exactness and presence at every boundary. REST, through the real `/orders` and
  `/replace` handlers:
  - fractional bps, display, quantity or peg offset;
  - a string or boolean number;
  - more than 6 price decimals;
  - out-of-range values;
  - an unused field sent as `0` or `null`;
  - a price of `0`.

  Expected: each is refused with its text, and nothing is queued for offer. A valid order through
  the same route is queued, which is the positive control. The FIX mapper refuses the same
  properties by tag, a real session refuses a fractional MaxFloor with nothing sequenced, and the
  console specs give the same text.

A live or cluster proof is not part of source acceptance and needs separate authorization.

## Decisions

### OPEN-D5: approved by the user (2026-09-23, board 20260923T231432Z)

| ID | Decision | Approved policy |
|---|---|---|
| **OPEN-D5 (economic)** | STOP / TRAILING_STOP reservation | Reserve at the stop price (trailing: the stop level at admission), then release and re-decide at trigger at `marketValidationPx` through the internal key-0 path. **No multiplier, no depth bound.** |

**Accepted limitation:** trigger re-decision validates at the mark or opposite best, not at the
depth the order walks. It does **not** bound the fill price, and executed notional can exceed the
reservation (FR-OT16, SC-OT37). This is today's MARKET behaviour.

### Adopted by the lane: the coordinator may overturn these in review

| ID | Decision | Adopted | Alternative not taken |
|---|---|---|---|
| D1 | Stop already through at admission | Reject STOP_ALREADY_TRIGGERED | Trigger immediately |
| D2 | Legacy negative `limitPrice` | Reject INVALID (the only legacy change) | Keep as silent MARKET |
| D3 | Operator force-fill as a trigger print | Does not qualify | Qualifies |
| D4 | Latch order within one print | Original submission sequence, both sides together | Price priority per side |
| D6 | IOC/FOK on triggered types | Refused | Applies at trigger |
| D7 | Trailing stop with no trade yet | Reject TRAIL_REFERENCE_MISSING | Start the watermark at the first trade |
| D8 | Iceberg display randomisation | None | Randomised tranche |
| D9 | PEGGED / TRAILING_STOP over FIX | **Mapped in this delivery** (FR-OT04). No technical blocker: QuickFIX/J 2.3.1 FIX44.xml carries every field on both messages | — |
| D10 | Peg cap and risk | Cap required; buy reserves at the cap; sell ratchets `riskPx` with atomic re-reservation (FR-OT39) | Optional cap with a decision on every reprice |
| D11 | Who issues SESSION_START and DAY_END | Admin commands; an opt-in scheduler, off by default | Scheduler on by default |
| D12 | DAY order after its day ended | Reject NO_TRADING_DAY | Park it until the next SESSION_START |
| D13 | Cascade exhaustion | Cancel the security's pegs with CASCADE_LIMIT (the queue is already empty); the member keeps running (FR-OT40) | Stop the member; roll back the step |
| D14 | Consensus limits | Compile-time constants, recorded in the snapshot; mismatched restore refused | Environment configuration per member |

## Implementation Notes (as built, 2026-09-24)

Details the design left to implementation, and small consequences a reviewer should know:

- **STOP_LIMIT collar:** checked at trigger, where the order would first rest; admission checks
  only that the stop and the limit are on the grid. A stop never re-anchors a band while it is
  pending.
- **Venue-initiated egress** (triggers, peg reprices, DAY expiry, CASCADE_LIMIT) always carries the
  resting-update class (FR-OT12).
- **An unfillable FOK** answers with its CANCELED update as the direct ack (reason
  FOK_UNFILLABLE), without a preceding ACCEPTED. REST answers 200 with `kind` 5 and the reason.
  FIX answers OrdStatus=Canceled with Text FOK_UNFILLABLE.
- **The order NATS bridge** adds `reason` to a non-ACCEPTED order update, and a typed block to a
  typed one. An untyped accepted order's JSON is unchanged. A rejected untyped order's JSON gains
  the additive `reason` field (the read model ignores unknown fields, and now stores it).
- **SBE `SCHEMA_CHECKSUM`** is unchanged. It identifies peers of the retired single-BLP Aeron
  replication tier, and template 1, the only template that tier carries, is unchanged.
- **Snapshot open rows** are written in book-append order. This also restores the exact FIFO of an
  untyped order that a replace sent to the tail of its level, which ascending-ref order did not.
- **The console** offers the typed selector on the Equity (Direct) and Option tickets. The bond
  and OTC tickets are unchanged.
- **Not added:** a business date on `GET /session` or the member health page. The POSTs report it.
- **Legacy fast paths (NFR-OT06, review I4):**
  - A pool take clears an order's typed block only if the order was typed or still linked into
    a store.
  - An order update copies the typed shape only for a typed order, or into a ring slot that last
    carried one. Such a slot is zeroed by that copy.
  - The typed output fields live in a per-slot side object (`OutputEvent.typed`), so a legacy
    slot is its pre-YU18 size.
  - `lastTradePx` and `hasTraded` still update on every print, so a later stop sees the true
    reference.
  - `OrderTypesEngineTest.i4_…` proves that neither fast path can leak a typed shape into a
    legacy update.

## Verification map

| SC | Test (YU18 layer unless noted) |
|---|---|
| SC-OT01–03 | `cluster.OrderTypesBoundaryTest.sc01_sc02…`, `sc03…` |
| SC-OT04, SC-OT48 | `cluster.OrderTypesBoundaryTest.sc04_f1…` (real QuickFIX/J session), `sc48…` (two cases) |
| SC-OT05 | `cluster.OrderTypesServiceTest.sc05…` |
| SC-OT06–17, 18–28, 33, 37–43, 45–47 | `lmax.OrderTypesEngineTest.sc06…` to `sc47…` (method names carry the SC ids) |
| SC-OT29, 30, 36, 44 | `cluster.OrderTypesServiceTest` |
| SC-OT31 | `cluster.SnapshotCompletenessAuditTest` (rich typed fixture), `cluster.OrderTypesServiceTest.sc31…` |
| SC-OT32 | `lmax.OrderTypesEngineTest.sc32…`, `sc39…` |
| SC-OT34 | `lmax.OrderTypesAllocationGateTest`, isolated `orderTypesAllocationGateTest` task |
| SC-OT35 | `lmax.MatchLatencyBenchmarkTest`, before (0ae2c53a sources) vs after (c41e508b), 6 alternating pairs, same machine. Percentiles match the baseline on the cross and insert paths; a small mean residual is disclosed (README) |
| SC-OT49 | `cluster.OrderTypesBoundaryTest.i1_…` (two cases) |
| SC-OT50 | `lmax.OrderTypesEngineTest.i2_…` |
| SC-OT51 | `cluster.OrderTypesBoundaryTest.i3_…` (three cases), `sc04_f1…` (fractional MaxFloor), console `order-types.spec.ts` |
| Consensus limits | `cluster.OrderTypesServiceTest.consensusLimitsAreRecorded…` |
| Read model | trade-processor `OrderTypesReadModelTest`, `SchemaMatchesShippedDdlIT` (upgrade arm) |
| Console | `web-front-end-console/src/app/order-types.spec.ts` |

## Out of scope for this delivery

- Exchange-specific variants: MOO/MOC, GTD, minimum quantity, discretionary, reserve
  randomisation, and hidden (non-displayed) orders.
- NBBO or any external consolidated reference. None is available, and none is invented. FIX
  PegScope other than local is refused.
- The legacy Angular `web-front-end` ticket.

[YU13 reference](../../../YU13-limit-order-book/spec.md) · [State components](../../../../docs/spec-kit/state-components.md)

#### SC-OT51 decimal decoding follow-up

Typed REST validation must see the original decimal value, before any binary floating-point
conversion. Fractions that round to integral quantities or valid six-decimal prices as doubles
are still refused on /orders and /replace, with nothing sequenced. Exact integral exponent
notation and prices with insignificant trailing zeros remain valid. Untyped Jackson node types
and conversion behavior stay unchanged. FIX validates original numeric tag text.
