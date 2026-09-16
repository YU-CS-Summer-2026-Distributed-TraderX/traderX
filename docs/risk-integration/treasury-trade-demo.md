# Controlled Treasury trade-to-analysis demo

This is a single operator-triggered demo, not a production trading or risk service. Risk's Run
button books one matched Treasury bill trade on the existing test venue: buy USD 1,000 face of
UST-BILL-20261112 in the Treasury test account against the designated test seller. Prices are
fractions of par, not dollars per bond. Existing venue risk controls remain active. Neither account
may already hold or have traded that bill. No broad reset, seeding or limit override is performed.

The run is deliberately single-use. POST `/risk/treasury-demo/start` requires the existing operator
cookie, JSON, the exact configured Origin and a UUID idempotency key. Any later start returns the
same run, even with another key. GET `/risk/treasury-demo` has no side effects. A persisted run is
never deleted by this workflow. Console runs one replica with Recreate strategy and a dedicated
persistent volume; changing that concurrency model requires a different storage/locking design.
No automatic repeat-run reset exists.

`treasury-demo.mjs` persists and fsyncs the run before dispatch. The local worker polls an authenticated
worker endpoint using a private scoped secret. The public API accepts no trade parameters, commands
or paths. Worker payloads are capped, errors are fixed plain text, histories are bounded to one run,
and results appear only after independent acceptance. Worker health expires after 30 seconds.
A run already in flight can wait while the worker is disconnected; it is not shown as complete.

## Execution and evidence

Run `scripts/treasury-demo-worker.py` in the reviewed external Python environment with `--state`
(a private persistent directory), `--secret-file` (the private worker secret) and `--engine`
(the read-only pricing repository). The script archives and byte-verifies the exact accepted
producer pin; it never edits that checkout. `--check` checks the dependency import without trading.
Keep this local process and computer awake for the demo. A restart uses the SAME state directory.
A file lock permits only one local worker process.

Before every order submission or EOD-close mutation, the worker fsyncs an intent. Successful replies
are fsynced too. A lost reply or interrupted intent is reconciled against trades/positions and
stops for operator review; it is never blindly resent. Stable distinct client order IDs also use
existing engine duplicate suppression. The two accepted orders must become actual booked trades
and the buyer/seller positions must be exactly +1,000/-1,000 USD face from zero. Engine acceptance
alone is not booking. Rejection, unfilled orders or a partial outcome is a failure requiring review;
this script does not automatically cancel or repair an uncertain trade.

The next EOD version must pass the normal quality gate with no overrides. The resulting completion
receipt and original position/contracts CSVs are retained privately and hash-verified. A scoped
bundle selects the exact buyer bill row from that original export, retaining its cut identity.
The full original export remains intact; unrelated account positions are not priced or silently
replaced. The separate `traderx-booked-bill-demo-v1` acceptance profile verifies export/receipt hashes,
cut/epoch/date/account/currency, order-to-trade identities, booked position deltas, cost basis, mark
quality/freshness and exact supplied terms. The original W0 and fixed-fixture profiles stay intact.

Issue date 2026-08-13 and maturity 2026-11-12 come from the venue reference response. The explicit
operator demo supplement assumes redemption at par, signed USD face, no coupon, calendar NONE,
unadjusted maturity, no payment lag and trade-date valuation without a settlement adjustment.
These are model assumptions, not asserted market settlement conventions. Valuation is dated
2026-09-16 16:00 America/New_York. The immutable flat-3pct-v1 profile is a continuously compounded
constant 3% curve constructed relative to that valuation date, not observed market data.

The real library runs locally on the exact scoped export bundle. Original producer bytes are saved;
a separate Decimal discounted-cash-flow calculation and strict full-result shape/identity/outcome
checks must agree within USD 0.00000001. The public view contains business values only. Bill
sensitivity remains unsupported; portfolio risk is unavailable and `usableForRisk` is false.
The UI separately labels the older fixed examples as reference examples.

A successful result is retained with its actual business date. This worker refuses new execution
outside 2026-09-16. Hosting this result on GKE does not turn the local worker into a deployed pricing
service. Terms v2, producer versioned result schemas and durable producer HTTP remain outside this
implementation.

## Deployment verification (2026-09-16)

The UI/backend is deployed with console digest
`sha256:d7e7faa99bc2a023ab0263136d1facb622c9aec90124c8e761c8f71d573fbda2`.
Source and regenerated backend suites passed 137 tests each; combined console UI passed 90,
and Node route suites passed 13. Required repository gates passed. A real pinned-library run
against explicit dated test inputs agreed with the independent check; that is test evidence,
not evidence of an actual venue trade.

The public endpoint rejects unauthenticated start (401), GET start (405), and an unauthenticated
worker update (401). The configured public status correctly reports worker unavailable and no run.
Automatic approval review blocked launching the local worker pending direct user approval in the
GKE task. No new Treasury orders, fills, EOD version or live priced result have been created yet.
The full live trade-to-analysis acceptance remains pending that approval; do not present it as done.

## Local runtime and recovery evidence

The external Python environment requires `SSL_CERT_FILE=/opt/homebrew/etc/openssl@3/cert.pem`
on this operator machine to use its installed trusted CA bundle. TLS verification remains enabled.
After direct user approval, the single run booked the expected two trade sides and created EOD
version 2 with no flags or overrides. The worker then stopped on an EodReport field-name mismatch
(`instruments`, not `prices`). That mapping is corrected and regression-tested. Original failed
run evidence remains preserved. Offline analysis of those exact exported bytes passed the real
library and independent validator. Recovery of the displayed run is a separate operator action;
it must never create another order or EOD close.
