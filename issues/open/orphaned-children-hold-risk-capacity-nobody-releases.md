# Orphaned children hold risk capacity that nobody will ever release

> A record, not a rig you can query. Account ids, order refs and share counts come from the epoch
> this was measured on and will be rolled. Read them as the shape.

Follow-up to `issues/resolved/a-torn-algo-log-replays-clean-and-orphans-live-children.md`, which made
a torn log *say so* but deliberately did not repair it.

## The decision this sits under

**Yaakov's call, 2026-08-21: do NOT auto-cancel orphaned children.** That is the right call — cancelling
live orders automatically, on the strength of an engine having lost its own memory, is a destructive
action taken on incomplete information. This issue is not a request to revisit it. It is the
consequence that decision leaves behind, written down so it is chosen rather than inherited.

## The consequence

An open order is not free. The risk gateway holds a **reservation** against the account for as long as
the order rests — `reservedNotional`, `reservedBuyNotional`, `reservedSellNotional` and
`reservedBuyQtyByExposure` / `reservedSellQtyByExposure` in `BlpRiskState`, which the class comment
describes as rebuilt from open-order reservations at snapshot restore.

An orphaned child rests forever:

- it will never fill (unless the market comes to it),
- it will never be cancelled, because the parent that would cancel it no longer exists and, by the
  decision above, nothing else will,
- so its reservation is never released.

Every torn log therefore takes a permanent bite out of that account's tradeable capacity. It does not
recover on its own, and it **compounds** — each subsequent tear removes more. In the worked example a
single evening's two tears left twelve children resting, several hundred shares' worth of reservation,
against one account.

The failure mode when capacity finally runs out is a *rejection of a legitimate new order*, arriving
long after the incident that caused it, with nothing connecting the two.

## Status of the evidence — READ, NOT MEASURED

**Measured:** the orphaned children exist and rest with `status=NEW` in the read model, and the engine
holds no parent for them.

**Read from source, not observed:** that they consume reservations and that nothing releases them. The
reservation fields were read off the operative `BlpRiskState`; the leak is inferred from the mechanism,
not watched.

**Not established:** the size of the bite, whether a snapshot restore rebuilds reservations for orders
whose parent is gone (the comment implies it rebuilds from the open orders themselves, which would mean
the leak *survives* a restore, but that was not tested), and whether an epoch roll clears it.

**There is no read endpoint for reserved notional per account.** The gateway exposes `/risk/control` but
no risk *read*. That absence is why this is inferred rather than measured, and it is arguably the first
thing to fix — an invariant nobody can observe is one nobody can prove is holding.

## Directions, not a decision

1. **Make it observable first.** A per-account reserved-notional read would turn this whole issue from
   an argument into a measurement, and is useful well beyond this case.
2. **Release on orphan detection, without cancelling.** The engine now names the orphaned parents. If
   the reservation can be released while the order continues to rest, capacity is recovered without any
   destructive action — this may not be coherent, since the order is still live and could still fill,
   but it is the option that respects the no-cancel decision.
3. **Surface, don't fix.** Report orphaned resting exposure on an operator surface and let a human
   cancel deliberately. Consistent with the recovery-not-repair line already drawn.
4. **Accept it.** Legitimate if tears are rare and capacity is generous — but then say so, with the
   compounding written down, so the eventual mystery rejection has a documented cause.

## Lineage warning

`BlpRiskState.java` is carried by **three** layers — `YU03-in-memory-risk-gateway`,
`YU14-listed-equity-options` and `YU17-otc-rates` — and **YU17 is operative** (verified by diffing each
against the generated tree). A fix to the YU03 or YU14 copy is inert and looks exactly like a fix that
did not work. This file has already cost this project one such mistake; see
`.claude/skills/propagate-spec-fix`.
