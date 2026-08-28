# ADR-066: The price band follows the market

## Status

**Accepted and implemented** (2026-08-23). Decision: the full re-anchoring collar, in
the engine — not the seeder-only band-aid (cross demo equities at live prices so a fresh epoch
starts sane), which was offered and rejected because it leaves the mechanism intact: a stray
order still poisons a book mid-epoch, and it forces a suite-wide churn of every proof written
against IBM at 200.

Supersedes the anchoring rule in [ADR-050](../../YU13-limit-order-book/system/adr-050-banded-price-level-arrays.md)
(*"anchored mid-band on the security's first limit"*). Closes
`issues/resolved/a-books-price-band-is-anchored-by-its-first-order.md`.

## Context

`LimitBook` is a banded array of `BOOK_LEVELS` (1<<17) consecutive price ticks of `BOOK_TICK_PX`
(0.001) — a $131.07 window, ±$65.5 around its centre. Slot = absolute tick − `baseLevel`, and
`baseLevel` was set once, by the first limit order the book ever saw, then never moved. A security's
tradeable range for the whole epoch was therefore decided by whichever order arrived first.
Measured 2026-08-19: MSFT accepted at 180.00 only and refused twelve orders at 384–387.

Two reference prices already existed and the collar read neither:

| reference | where | moves when |
|---|---|---|
| feed price | `BlpRiskState.lastPrice[]` via `onPriceTick → risk.onPrice` | every sequenced tick |
| mark | `MatchingEngine.lastPxBySecurity[]` | a tick, only until the first print; then every trade (ADR-051) |

So `/seed` looked like it should move the band and did not — twice over for MSFT, whose mark was
frozen at 180 by its own stray print. Both references are replicated (they arrive through the
consensus log) and both are snapshotted (`T_SECURITY`, `T_PRICE`), which is what makes an anchor
derived from them deterministic on every member and on replay.

## Decision

1. **The reference is the feed price, else the mark, else nothing.** The feed is exogenous and keeps
   ticking after the book prints; the mark is the fallback when no tick has ever arrived (tests, a
   ticker outside the publisher's universe); with neither, the first limit still anchors — the old
   rule, kept as the floor so an un-priced security remains tradeable at all.
2. **A new book anchors on the reference**, not on its first limit. With the feed at 388, a stray
   MSFT order at 180 is the one refused.
3. **Re-anchor lazily, only when it changes the answer.** A limit the current band refuses is
   re-judged against a band centred on the reference. If that band admits the limit, the book is
   re-centred there and the limit accepted; if not, the refusal stands as a genuine `PRICE_COLLAR`.
   A book whose band still contains the order never pays anything, a tick never touches a book, and
   thrash is impossible by construction: the band moves only to admit an order the market says is
   admissible, and lands centred on the market.
4. **Re-anchoring is a re-index, and stranded orders are cancelled first.** Every slot-indexed
   structure (`bidHead/Tail`, `askHead/Tail`, level quantities, occupancy bitmaps, `bestBid/Ask`)
   shifts by `oldBase − newBase`; a level whose new index falls outside `[0, levels)` has nowhere to
   live. Its orders are cancelled through the same unsolicited-cancel path as cancel-oldest STP
   (ADR-057) — `FLAG_CANCEL | FLAG_RESTING_UPDATE`, reservation released exactly once, reason
   **`PRICE_COLLAR`** on the ack byte — so the client learns, and there is still exactly one way the
   venue removes a resting order. Cancels are walked top-down per side, so their order is fixed.
   An order more than ±$65.5 from the market is, by the collar's own definition, one the venue
   would not accept now; cancelling it is the honest outcome, not a loss.
5. **A replace may not strand itself.** If re-centring would drop the order being replaced, the
   replace is refused `PRICE_COLLAR` and the order stands — atomicity (ADR-058) means a replace
   never answers with the order gone.
6. **Operator visibility:** `MatchingEngine.bandReanchors()` and `bandStrandedCancels()` counters
   beside `selfTradesPrevented()`; the cancels themselves reach clients with their reason.

## Snapshot

`SNAPSHOT_FORMAT` stays 7. `T_BOOK` still carries `{securityId, baseLevel}` with the same value
domain; only the rule that chooses `baseLevel` changed, and the inputs to that rule were already
captured. Either build restores the other's snapshot exactly; a first-limit anchor restored into
this build is re-centred lazily the first time the market disagrees with it. Recorded at the
constant in `MatchingEngineClusteredService`.

## Consequences

- `/seed` now moves a security's band (it was always a sequenced tick into `risk.onPrice`).
- Proofs that refuse at prices far outside any market-centred band (`yu03-risk-proof` IBM@400,
  `yu13-stp-and-replace` PRICE+500, `yu13-gke-replace-proof` px 5000) keep their refusals. A proof
  that relied on a refusal *inside* ±$65.5 of the feed would now be accepted — none was found.
- `GatewayReplicaStoreTest`'s `PRICE_COLLAR` is the gateway's percentage pre-screen, a different
  check; untouched.
- `scripts/sim/session.mjs`'s `probeAnchor()` is no longer load-bearing but is kept: "never assume"
  is still right.
- The collar is not self-referential in the sense `HANDOFF-collar-price-sourcing.md` warned about:
  it follows the *feed*, not the book. The handoff's premise that the collar already read the feed
  was wrong (it read nothing); that is now the case it described.
- Deterministic core: no gradual roll. Fresh epoch only.
