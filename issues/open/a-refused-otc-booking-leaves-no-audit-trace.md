# A refused OTC booking leaves no audit trace, where a refused order leaves one

**Found 2026-08-23** while implementing the OTC half of
[[otc-bookings-absent-from-the-regulatory-report]]. Not a regression — the gap is as old as the
booking path; it only became visible once booked contracts reached the audit surface and the
refused ones did not follow them.

## The asymmetry

`/regulatory/report` now carries `SWAP_BOOKED` / `SWAPTION_BOOKED` for every **booked** contract, and
has always carried `ORDER_REJECTED` for a refused order. It carries **nothing at all** for a refused
booking.

A booking is refused for real, decided reasons — `UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED`, `CAPACITY`,
`PRICE_MISSING` (no sequenced FX rate for the convention's currency), `ORDER_NOTIONAL`. Measured on
the rig: `POST /swaps` on account 999123 → `422 {"booked":false,"reason":"UNKNOWN_ACCOUNT"}`, and
`scripts/proofs/yu17-swap-netting.sh` step 1 asserts exactly that. The refusal was **sequenced and
decided through consensus** — it is a committed log entry, not a dropped request. It simply leaves
nothing behind that a reader can find.

## Why the OTC fix could not close it in the same pass

The shipped projection works by noticing that the shadow replay's **contract store grew**. That is
what made it a read-side change with no emit-side cost. A refused booking, by construction, does not
grow the store — `onSwapBook` accrues nothing and adds nothing — so there is no replicated state for
a replay to observe. `MatchingEngineClusteredService.onSwapBook` writes the reason into the egress
ack (`ackBuffer` byte 22) and returns; nothing reaches the output ring.

So closing this needs one of:

- **An emit-side change** — offer a rejection to the output ring, which is a change to what the
  deterministic core produces and therefore cannot be rolled gradually (scale to 0, wipe PVCs, fresh
  epoch), or
- **A second read-side hook** on `MatchingEngineClusteredService`, in the style of the existing
  `outputSink`: a decision tap the shadow can subscribe to, off-consensus and outside the snapshot.
  Cheaper, and the shape the rest of `ClusterRecon` already uses — but it is still a new seam in the
  apply path, and `ClusterReconTapTest.unsetTapLeavesTheApplyPathUntouched` is the property any such
  seam has to keep.

Neither was decided, so neither was built. The shipped tests pin the current behaviour honestly:
`ClusterReconOtcTest.aRefusedBookingProducesNoRow`.

## Why it matters more than the row count suggests

A booking refused for `PRICE_MISSING` means the credit gate could not value a non-USD notional
because no FX rate had been sequenced for that currency. That is an operational condition with a
window, and after the fact the only evidence it happened is a 422 the client received and nobody
retained. The EOD contracts artifact cannot help — it lists contracts, and this one does not exist.

## Related

- [[otc-bookings-absent-from-the-regulatory-report]] — the booked half, decided and shipped.
- [[the-audit-surface-records-that-an-order-was-refused-not-why]] — the same question for orders, and
  strictly easier: there the reason IS already on the replayed event and is merely not rendered.
