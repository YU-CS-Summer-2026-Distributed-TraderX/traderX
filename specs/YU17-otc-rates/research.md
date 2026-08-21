# Research: YU17-otc-rates

Every claim here is grounded in this repository's own constraints — the record the log carries, the
grain the position model uses, the capacity of the symbol table, the shape of the cut.

## 1. Why the position model cannot hold a swap

The position is `(accountId, security) → (signed quantity, averageCostBasis, multiplier)`. For
anything fungible that is exact: buy 100 AAPL at 150 and 100 at 160, hold 200 at 155, and marking
to market is right because the two lots are the same instrument and the average is the position.

Run the same arithmetic on swaps. Receive fixed 4.2% on 10mm, then pay fixed 4.3% on 10mm, same
dates and conventions: net quantity zero, average rate meaningless, position gone. Economically
the account is locked into paying 10bp on 10mm for five years — roughly 10k a year, ~50k
undiscounted before any discounting the consumer would apply. Netting deleted a real position, with
no error and no log line.

It breaks in the same-direction case too. Receive-fixed 4.2% for 5Y and receive-fixed 4.2% for 3Y
share a rate and a direction, and averaging them is still wrong: they are two obligations with
different remaining terms, and one number cannot carry both.

The general statement is short. **For a fungible instrument the price is what you paid; for a swap
the rate is what the contract is.** Cost basis is an average of a history. A fixed rate is a term of
a contract. Averaging a term is a category error, not a rounding error.

Listed options are the useful contrast and the reason they fit. An OCC symbol encodes underlying,
expiry, strike and type, so `AAPL260918C00240000` is a fixed identity that pre-exists any trade,
and two lots of it genuinely are the same instrument. That is why YU14 needed a multiplier and
nothing else.

## 2. Why the trade creates the instrument

A 5Y swap traded today matures 2031-08-13; traded next month, 2031-09-13. Identity includes the
trade date and the agreed rate, so there is no ticker that pre-exists the trade the way `UST-20310630`
does. The system's `security` is exactly such a pre-existing ticker, registered through a sequenced
`SymbolRegisterMessage` and assigned an id from a table capped at `MAX_SECURITIES = 1024`, never
evicted (the id must survive in the snapshot for the book to restore).

So giving each swap a symbol-table entry exhausts the table in an afternoon of trading and fills
every subsequent snapshot with books that hold no resting orders, because a swap never rests. The
contract store is the right home: bounded on its own terms, holding what a swap actually is.

## 3. Why there is no book

Swaps trade OTC, bilaterally, by RFQ. There is no central limit order book that corresponds to
anything real, so building one would be building a fiction and then proving properties about it.
`MatchingEngine` is a crossing price-time-priority book over integer ticks; there is nothing for it
to do with a booking that has no counterparty order to cross and no price grid to rest on. Routing
the command past it is not an optimisation — it is the accurate model.

## 4. Why the booking is sequenced anyway

The obvious shortcut is to book swaps into the read model directly, since nothing matches. It is
the wrong shortcut, and the reason is written in the extract's own preamble:

> `# cutConsistency=every row is the replicated state machine's state at consensusSequence on the
> totally-ordered consensus log, not a read-model query`

Booking swaps outside the log makes that sentence false for the file that carries it. What would be
given up is concrete, not stylistic: deterministic replay of a booking, byte-identical rendering
across all three members, the quiescence witness that proves nothing was sequenced during the
build, and reproducibility of the whole artifact from the stored cut alone.

The version that keeps all of that is also the better story: the architecture absorbs an instrument
class that cannot match without giving up determinism anywhere.

## 5. Why the existing record is enough

`InputEvent` carries `accountId, side, qty, limitPx, priceTicks, securityId, orderRef,
eventTimeMillis` in a fixed 64-byte record, and the journal plus the snapshot's byte-offset recovery
are the reason it stays fixed. A swap needs notional, fixed rate, direction, two dates, and three
convention fields — more values than there are unused slots, until the conventions are observed to
be a small enum rather than per-trade economics.

With float index, payment frequency and day count in a table addressed by index, the mapping falls
out with five values landing in a slot whose existing meaning already fits:

| Swap field | Slot | Why it fits rather than being repurposed |
|---|---|---|
| booking account | `accountId` | unchanged meaning |
| pay / receive fixed | `side` | a swap has exactly one binary direction |
| notional | `qty` | caps at 2,147,483,647; the gateway refuses more |
| fixed rate | `limitPx` | already a 1e6 fixed-point long, and a rate is a 1e6 fixed-point number |
| idempotency key | `priceTicks` | already the `clientOrderKey` slot for ORDER_NEW |
| conventions | `securityId` | free, because a swap gets no symbol-table entry |
| effective + maturity | `orderRef` | the one packed field: two 16-bit epoch days |

`orderRef` is genuinely free for a swap: the sequenced generator overwrites it only for
`TYPE_ORDER_NEW`. Two 16-bit unsigned epoch days reach 2149-06-06, and the gateway refuses anything
outside that range rather than letting it wrap into a plausible date.

Keeping `priceTicks` as the idempotency key rather than spending it on the dates is deliberate. A
swap booking is a bilateral confirmation; a retried one that creates a second 10mm contract is
exactly the failure the idempotency table exists for, and the table's key is 64 bits because a
collision answers a distinct request with another's outcome.

## 6. Why the conventions live in a table

This line already derives rather than stores, twice: YU14 derives the option contract multiplier
from the committed ticker (ADR-052), and YU16 derives the Treasury book grid from the ticker prefix
(ADR-060). Both are pure functions of committed state, so they are identical on every member, on
replay and on restore, and neither costs a snapshot field.

A compile-time convention table has the same property for the same reason. Its one obligation is
that an index, once journaled, keeps its meaning forever: appending is safe, reordering silently
rewrites the terms of contracts already booked. A build that meets an index it does not know aborts
the render rather than resolving to another convention — publishing a contract under the wrong day
count is worse than publishing nothing, and the fix (roll forward) is the same one the snapshot
header already prescribes.

## 7. Why the risk gate needed real work

The gate computes `notional = quantity × validationPrice × multiplier`. For a swap that is not
approximately right, it is wrong in a specific and quiet way: 10,000,000 × 0.042 × 1 = 420,000. A
10mm exposure measured as 420k understates by about 24x, and nothing about the result looks
anomalous.

The alternative to a swap path is a documented bypass, and it is worse: a swap admitted with no
notional measured at all consumes no credit, so the account's exposure is understated by the entire
notional rather than by 24x. The swap path is a dozen lines and measures the notional directly.

What it drops, it drops for cause:

- **security enabled / restricted / priced / fresh** — a swap has no symbol-table entry and no last
  trade. Run against the convention index sitting in the `securityId` slot, these checks would test
  an unrelated number and refuse every booking.
- **position limit and concentration limit** — both project `(account, security)` quantity forward.
  That is the grain a swap does not have, and netting a receive-fixed against a pay-fixed to zero
  exposure inside the gate would reintroduce, in the admission decision, the exact error this state
  exists to demonstrate.

What remains is everything that is about the account rather than the instrument, which is the part
that transfers.

## 8. Why two artifacts rather than one

Netting is correct for equities, ETFs, Treasuries and listed options — four classes against one —
and the netted extract is the file downstream risk already consumes. Changing its shape to
accommodate the class it cannot serve is the expensive option.

A single polymorphic file was considered and rejected on two grounds. It forces every consumer to
branch on `instrumentType` before reading any column, since a swap row's `quantity`, `costBasis`,
`closingMark`, `marketValue` and `unrealizedPnl` are all either empty or wrong. And the existing
"non-bond rows carry empty bond columns" convention does not stretch this far: a bond row shares
almost every column with an equity row, whereas a swap row shares `accountId` and nothing else.

The two artifacts are nonetheless one observation. They are rendered from one cut under one stamp,
so a shared `consensusSequence`, `sessionDate` and `cutSha256` are structural rather than something
the producer has to remember to keep aligned. The contracts ride the cut as a second SECTION rather
than a second message for the same reason: two messages can be delivered apart, hashed apart and
stored apart, which is precisely the "consistent at two instants" failure a consensus-sequenced cut
exists to rule out.

The section is emitted even when empty. An absent section and an empty one are opposite facts — one
says the portfolio holds no swaps, the other says the producer is an older build — and a consumer
must not have to guess which it is looking at.

## 9. Why we publish terms and not values

The boundary with the consumer's pricing and risk engine is clean when each side is authoritative
for one thing. We are authoritative for what was booked, because we sequenced it. Their engine is
authoritative for what it is worth, because that is the engine's purpose.

An NPV computed here would be a second, differently-derived number for the same quantity, arriving
in the same file as the terms it was derived from. That is not extra information; it is a
reconciliation break that someone has to explain every time the two differ, and they will differ,
because discounting depends on a curve neither side has agreed on. The preamble says the absence is
deliberate, because a missing valuation column that reads as an omission invites exactly the
assumption that must not be made — that no valuation means no exposure.

## 10. Why the snapshot format moves and the epoch does not

`T_CONTRACT` is a new record type, not a changed one. Every format-4 record keeps its shape and
meaning here, so `MIN_READABLE_SNAPSHOT_FORMAT` stays at 3 and an existing epoch rolls forward
untouched — which matters, because a format bump that also demanded a PVC wipe would turn a routine
roll into an outage.

The bump exists for the other direction. A format-5 snapshot handed to a `YU16-cdm-instruments`
build reaches `T_CONTRACT` at the `default ->` arm and aborts with "unknown snapshot record type:
12" — deep in record parsing, with a message that reads like corruption. The version number makes
that unconditional and legible at the header instead, and the existing message already names the
direction of the mismatch and says to roll forward rather than wipe.

The precedent is the reason this rule exists at all: format 4 was minted only after a widened symbol
domain went unversioned, and the resulting incompatibility was data-dependent — a rollback rehearsed
on a quiet rig succeeded and the identical rollback failed the moment the 65th security existed.

## 11. Why the contract id is a consensus sequence

An id has to be unique, deterministic on every member and on replay, and reproducible from the log.
A generator would satisfy that, at the cost of a field in the snapshot header, a restore invariant
of its own, and a failure mode where the generator and the restored contracts disagree.

The booking's own consensus sequence satisfies it with none of that: one sequence applies one
command, so it is unique by construction, and a replay to N reproduces the identical ids while
carrying no extra state at all. What it does not give is uniqueness across a wiped epoch, since
sequences restart — the same property `orderRef` and the trade counter already have. The extract's
write-once sink refuses a colliding key loudly rather than mixing two epochs, which is the better
half of that trade.
