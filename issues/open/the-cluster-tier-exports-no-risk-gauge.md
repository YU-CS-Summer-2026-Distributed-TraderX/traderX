# The cluster tier exports no risk gauge, so reserved capacity is unobservable on the only rig

> Read from source 2026-08-24, not scraped from a running member. Re-derive against the tree in
> front of you.

Split out of `issues/resolved/orphaned-children-hold-risk-capacity-nobody-releases.md`, which needed
this to turn its argument into a measurement and could not get it. Deferred there for **collision,
not merit**: the change lands in `ClusterNodeMain.java`, which another lane had open in this worktree
at the time (task `a327712f`, YU15 layer, live fix). Two lanes in one file is how this project loses
work.

## The gap

The reserved-notional aggregates in `BlpRiskState` are the account's tradeable-capacity accounting:
`reservedNotional`, `reservedBuyNotional`, `reservedSellNotional`. The class already exposes them —
`reservedNotional(int accountId)`, `executedNotional(int accountId)`, `totalReservedNotional()` —
they are simply never rendered anywhere a supporter can read them.

Precisely what exists today, because the parent issue overstated this and the correction matters:

- **Spring tier** (`OrderMatcherService`): exports `traderx_risk_reserved_notional_total`, an
  **all-accounts total**. So "there is no read endpoint for reserved notional" was never quite true.
- **Per account**: nothing, on any tier.
- **Cluster tier** (`ClusterNodeMain`, `/metrics`): **no risk gauge at all.** Role, applied,
  trades, snapshots, book digests, STP cancels, band re-anchors, span-sink health — and nothing
  from `BlpRiskState`.

The cluster tier is the only rig. So the number is unreadable exactly where it is authoritative.

## Why it is worth a line

An invariant nobody can observe is one nobody can prove is holding. The failure mode the parent
issue describes — a **legitimate new order rejected on CREDIT_LIMIT**, long after the incident that
consumed the capacity, with nothing connecting the two — is diagnosable in one scrape with this
gauge and close to undiagnosable without it. The per-account split is the whole point: a total tells
you capacity is committed, not whose.

## Sketch — roughly 15 lines, read-only

Nothing here releases, cancels, or fires anything; it is a render of state that already exists.

`BlpRiskState`, mirroring the existing `accountTuples()` idiom (cold path, allocating, never on the
decision path):

```java
/** Occupied account slots holding a live reservation, as {accountId, reservedNotional}. */
public List<long[]> reservedTuples() {
    List<long[]> out = new ArrayList<>();
    for (int i = 0; i < accountIds.length; i++) {
        if (accountIds[i] != -1 && reservedNotional[i] > 0) {
            out.add(new long[] { accountIds[i], reservedNotional[i] });
        }
    }
    return out;
}
```

`ClusterNodeMain`'s `/metrics` context, reaching the state via `service.engine().riskState()`:

```java
traderx_risk_reserved_notional{member="1",account="22214"} 10000000000
```

**Non-zero rows only** — that is not a nicety, it is what bounds label cardinality to the number of
accounts with open orders rather than to `maxAccounts`. Note that context currently builds its body
by string concatenation into `final String body`, not a `StringBuilder`, so the loop needs the
surrounding shape adapted rather than pasted into.

## Lineage — two different layers, and one is a known trap

- `BlpRiskState.java` has **three** carriers (YU03, YU14, YU17) and **YU17 is operative** here,
  verified by diff against `generated/`. A fix to the YU03 or YU14 copy is inert and looks exactly
  like a fix that did not work. YU14's copy adds the multiplier gate, so a blind copy of a YU03-layer
  change reverts multiplier logic. This file has already cost this project one such mistake.
- `ClusterNodeMain.java` lives in the **YU15** layer — a different carrier from the class it would
  read. Check both before carrying.

## Also worth having, same surface

`executedNotional` per account, from the same enumerator. The credit gate reads
`executedNotional + reservedNotional`, so exporting one half of the sum still leaves a rejection
half-explained.
