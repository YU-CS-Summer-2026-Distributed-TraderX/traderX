# Compatibility follow-up to Alex v3

Implemented locally; new accrual-basis shape awaits Alex's agreement.

## Immutable existing examples and checkout behavior

Root `.gitattributes` applies `-text` only to the YU18 component's `tests/fixtures/**` tree.
That includes JSON, CSV and cut/preimage bytes. It is not a repository-wide JSON/CSV policy.
Existing vectors and shared cases are unchanged. The golden verifier reports CRLF with a filename
and remediation; it never normalizes data or updates expected hashes.

Run `python3 scripts/test-state-YU18-checkout.py`. Real local Git clones with core.autocrlf=true
prove an unprotected control fails and the protected fixture bytes survive. An unrelated CSV
still translates. This checks Git filters, not Python/Java execution on a Windows host. A checkout
that was already translated must be restored from committed bytes after attributes are installed,
with local edits preserved first. Exported real inputs are always hashed exactly as supplied.

## Terms v2 inside bundle v2

The bundle schema remains traderx.eod-bundle.v2. Its instrumentTerms artifact schema now accepts
traderx.instrument-terms.v1 or traderx.instrument-terms.v2, and records the actual supplied version.
The latter requires `accrualBasis` on Treasury entries; swap entries retain their previous shape.
Terms v1 rejects the unversioned extra field. Existing fixture hashes are not revised.

Accrual basis fields are exactly schema=traderx.accrual-basis.v1, dateBasis=SESSION_DATE,
valuationDate matching the extract's sessionDate, settlementAdjustment=NONE, fractionDecimals=6
(integer), rounding=HALF_EVEN. The first implemented basis requires reference terms with calendar
NONE, businessDayAdjustment UNADJUSTED and settlementDays zero. Future settlement/calendar
semantics require an explicit contract extension. On zero-coupon instruments the structural
absence of a coupon schedule still governs blank accrual; the basis does not fabricate a value.

`tests/fixtures/compatibility/note-structured-basis/` is a complete synthetic bundle. Its position
and contract CSVs retain the original note bytes; the terms and resulting bundle identity differ.
Reference provenance remains synthetic/supplied. The proposed observed/assumed/synthetic/mixed
curve-origin vocabulary is a separate agreement; no curve package is implemented here.

## Missing accrual negative example

`tests/fixtures/compatibility/note-missing-accrual/` deliberately blanks both coupon-bearing note
accrual cells, keeping all other CSV bytes. It is not a production export, valid bundle, or receipt.
The source cut metadata is retained solely to isolate missing accrual as the rejection reason.
The builder rejects it before publication. expected.json separately records the proposed Alex
mapper outcome unavailable/ACCRUED_NOT_SUPPLIED; no observed worker response is claimed.

A valid zero-coupon bill still has empty accrual fields. Coupon-bearing notes cannot silently
normalize missing accrual to zero. V1 contains coupon fields, despite lacking the terms supplement.
Signed monetary accrual uses fraction × signed face once. Exported six-decimal fraction and an
unrounded recomputation differ by about four cents on the shared 100,000-face note; comparison
must name its rounding basis and tolerance.
