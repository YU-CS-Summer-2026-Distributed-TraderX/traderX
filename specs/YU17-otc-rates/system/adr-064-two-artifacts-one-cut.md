# ADR-064: Two artifacts from one cut — netting is lossy for a swap

**Status**: Accepted
**State**: YU17-otc-rates
**Date**: 2026-08-13

## Context

The EOD extract's row grain is `(accountId, security)` and the position behind it is
`(signed quantity, averageCostBasis, multiplier)`. For anything fungible that is exact, and it is
exact for the four classes this line already carries: equities, ETFs, Treasuries and listed options.

It is lossy for a swap, in a way that produces no error:

> Receive fixed 4.2% on 10mm, then pay fixed 4.3% on 10mm — same dates, same conventions, same
> account. Net quantity **zero**, average rate meaningless, position gone. The account is in fact
> locked into paying 10bp on 10mm for five years: ~10k a year, ~50k undiscounted. **Netting deleted
> a real position.**

It breaks in the same-direction case too: receive-fixed 4.2% 5Y and receive-fixed 4.2% 3Y share a
rate and a direction and still cannot be averaged into one contract.

The general statement is that for a fungible instrument the price is what you paid, whereas for a
swap **the rate is what the contract is**. Cost basis averages a history; a fixed rate is a term.

## Decision

**Two artifacts, rendered from one cut, at one consensus sequence, under one `cutSha256`.**

- The netted position extract stays exactly as it is, CSV **schema 3**, every column unchanged. It
  stops reading at the cut's section marker and never carries a swap row.
- Swaps get a **second, per-trade artifact** at schema 1: one row per contract, carrying
  `contractId, accountId, payReceive, notional, fixedRate, floatIndex, effectiveDate, maturityDate,
  paymentFrequency, dayCount, currency, counterpartyId, nettingSetId`.
- The contracts ride the cut as a second **section** (`#contracts`), not a second message.
- The artifact carries **terms and no valuation**: no NPV, no mark, no discount factor, no curve, no
  par rate, no sensitivity.

## Consequences

**Netting is kept where it is correct.** Four classes against one. Giving up the netted extract's
shape to accommodate the class it cannot serve would make the file worse for every existing
consumer, to fix a problem none of them have.

**A single polymorphic file was rejected on two grounds.** It forces every consumer to branch on
`instrumentType` before reading any column, because a swap row's `quantity`, `costBasis`,
`closingMark`, `marketValue` and `unrealizedPnl` are each either empty or wrong. And the existing
"non-bond rows carry empty bond columns" convention does not stretch this far: a bond row shares
nearly every column with an equity row, while a swap row shares `accountId` and nothing else.

**One cut, not two messages.** The two artifacts are one observation of one portfolio at one
instant. Rendering both from the same bytes under the same stamp makes the shared
`consensusSequence`, `sessionDate` and `cutSha256` structural rather than something the producer has
to remember to keep aligned. Two messages could be delivered apart, hashed apart and stored apart —
precisely the "consistent at two instants" failure a consensus-sequenced cut exists to rule out.

**The section is emitted even when empty.** An absent section and an empty one are opposite facts:
one says the portfolio holds no swaps, the other says the producer is an older build. A consumer
must not have to guess, so a cut with no `#contracts` marker aborts the render with a message saying
so, rather than rendering an empty file.

**Terms, not values, keeps the boundary clean.** We are authoritative for what was booked, because
we sequenced it; the consumer's engine is authoritative for what it is worth, because that is its
purpose. An NPV computed here would be a second, differently-derived number for the same quantity,
arriving in the same file as the terms it came from — a reconciliation break someone has to explain
every time the two differ, and they will differ, because discounting depends on a curve neither side
has agreed on. The preamble states the absence explicitly, since a missing valuation column that
reads as an omission invites the one assumption that must not be made: that no valuation means no
exposure.

**Currency comes from the contract, not the account.** The convention's currency is the contract's;
`counterparties.csv` carries the account's base currency for the netted extract's own purposes. A
USD-based account trading a GBP swap is ordinary, so those two disagreeing is not an error — and
taking the account's would misstate the trade.

**Both files are written write-once, in one call, beside the single stored cut.** Not two independent
deliveries: they are meaningless apart, so one write path is what stops a later edit from delivering
one and not the other. `RiskExtractMain --rebuild <cut> <positions.csv> <contracts.csv>` reproduces
both from that stored cut alone, which is how the claim is checked rather than asserted.
