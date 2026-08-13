# ADR-061: Accrued interest is derived into the extract, to the session date, as a fraction of par

Status: Accepted

## Context

ADR-059 put `coupon` and `maturityDate` on the Treasury row, which is enough for a consumer to
compute accrued interest for itself — and that is exactly the problem. Two systems each deriving
accrued interest from the same inputs, under conventions neither has written down, will agree
right up until a month-end break. The JAX risk engine asked for the number rather than the inputs
(`traderx-bond-integration-roadmap.md` §7.1) for that reason: the value is not the saved day-count
arithmetic, it is that there is one number to reconcile against instead of two derivations to
argue about.

Three sub-decisions had to be made, and each is a place where a silent disagreement could live:

1. **What the coupon schedule is anchored to.** The instrument static carries no issue date.
2. **What date accrual runs to.** Trade date, or a T+1 settlement date.
3. **What unit the number is in.** Cash, percent, or fraction of par.

## Decision

Two columns append after `maturityDate`, and the delivered CSV becomes
`# traderx-risk-extract schema=3` with `risk.extract.ready` announcing `schema: 3`:

- **`lastCouponDate`** — the coupon date accrual ran from.
- **`accruedInterestFraction`** — accrued interest as a **fraction of par**, six decimals.

Both are **derived** at render time from static the extract already joins, not joined from new
reference data. `instruments.csv` is unchanged.

1. **The schedule is generated backwards from `maturityDate` in six-month steps, each step
   measured from the maturity anchor** rather than from the step before it, so end-of-month
   clamping cannot walk the schedule off its day (`Aug 31 → Feb 29 → Aug 29` under repeated
   subtraction). This makes the schedule a function of the maturity alone, which is why no issue
   date is needed — and it is the standing assumption: a short or long **first** coupon is not
   modelled.
2. **Accrual runs to `sessionDate` itself, not to a T+1 settlement date.** Every other column in
   the extract is as-of `sessionDate`; a settlement-date accrual would make the row internally
   inconsistent with its own marks. It would also require a holiday calendar, which this system
   does not have and would have to invent. `lastCouponDate` is emitted precisely so a consumer
   that does have a calendar can roll accrual forward itself.
3. **Fraction of par, the same unit as `closingMark` (ADR-057)**, so `closingMark +
   accruedInterestFraction` is the dirty price with no scaling step between the two. That missing
   step is where a 100× error would otherwise live, which is the same hazard ADR-057 was written
   to close for the price itself.

Day count is ACT/ACT (ICMA), the US Treasury convention: elapsed fraction of the current coupon
period times half the annual coupon.

`marketValue` and `unrealizedPnl` stay **clean** — accrued is not folded into them. A clean-price
P&L and a dirty-price P&L are different numbers, and the consumer should choose deliberately
rather than inherit ours.

## Consequences

- **This is the first value in the extract that rounds.** `elapsed / period` does not terminate in
  decimal, so `RoundingMode.UNNECESSARY` — the extract's exact-or-abort discipline everywhere else
  — is not available here. It rounds `HALF_EVEN` at the tick scale. Rounding is deterministic, so
  byte-identical-across-members and rebuild-from-stored-cut both hold unchanged; but the guide can
  no longer say "every value in this file is exact", and it now says which one is not.
- The `.cut` sidecar is untouched (`#cut schema=1`). No engine change, no snapshot format bump, no
  fresh epoch: this is render-time derivation, exactly as ADR-059 was.
- Every schema-1 and schema-2 column keeps its name, position and meaning. A reader indexing by
  header name is unaffected; one that hardcoded a column count must widen.
- The accrual is only as good as its assumptions, and the assumptions are now written into the
  file's own header rather than into a document beside it — a consumer who never reads the guide
  still cannot miss the convention.
- **Settlement-date accrual remains open.** If the consumer wants it as the delivered number, it
  needs a holiday calendar owned by somebody; that is a real work item, not a flag.
