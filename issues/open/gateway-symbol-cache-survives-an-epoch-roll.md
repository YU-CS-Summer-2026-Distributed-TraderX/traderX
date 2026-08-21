# A gateway's ticker→securityId cache survives an epoch roll and silently mis-maps orders

**Found 2026-08-21** from a user report of intermittent rejections. The rejection was the lucky
outcome; the same defect can book an order against the wrong instrument.

## What happens

`ClusterGatewayMain.resolveSecurityId` caches `ticker -> securityId` in `idByTicker` on first sight
and **never invalidates**. A fresh epoch renumbers the engine's symbol table. Any gateway process
that outlives the roll therefore keeps resolving tickers to the previous epoch's ids.

Measured, three gateways, identical image and ReplicaSet:

```
UST-BILL-20270812   dnrg5 -> 415    v59hp -> 415    m2gh6 -> 6
```

`m2gh6` had resolved that ticker before the roll; the other two after. Orders routed to `m2gh6`
were submitted against **security 6**, not 415.

## Why it presented as flakiness

The three gateways sit behind one Service, so round-robin sent roughly a third of orders to the
stale one. The user saw bond and corporate orders rejected `INVALID` about one time in three, with
the same payload succeeding on retry. Nothing in the response names a gateway, so it reads as an
intermittent engine fault.

Direct measurement settled it in one run: `m2gh6` returned INVALID **3/3** on the exact payload the
other two accepted **3/3**.

## Why the rejection was the lucky case

Security 6 in this epoch was not a tradable instrument, so the risk gate refused. **Had the stale id
pointed at an enabled security, the order would have been accepted and booked against the wrong
instrument** — no rejection, no log line, no signal anywhere. The class is silent mis-mapping; the
observed symptom is only the tail of it that happens to fail loudly.

## Fix

`scripts/yu15/bring-up-gke.sh` step 3e restarts the gateways unconditionally at bring-up, so no
symbol cache can predate the current epoch. Cheap: bring-up is a cold-start path with no session
worth preserving.

**That is a mitigation at the operational boundary, not a fix in the gateway.** The durable fix is
for the gateway to invalidate `idByTicker` when the epoch changes — it already observes cluster
session and leadership events, so the signal is in hand.

## Related, found in the same investigation

**A fresh epoch drops the entire OCC option class.** Options are not reference-data instruments — an
OCC contract is enabled directly in the engine's risk state and never listed in the catalog — so
admitting all 533 catalog instruments cannot reach them. The Option preset answered
UNKNOWN_SECURITY while every equity and bond worked, which reads as a broken preset rather than a
dropped class. Fixed as step 3d, sourcing the chain from the price snapshot (the chain is generated
from its underlyings, so there is no static list to copy). Same family as
[[an-epoch-roll-silently-drops-instrument-classes]].

## Diagnostic note worth keeping

Two of my own checks reported false passes while chasing this:

- A per-gateway probe through the console's `/gw/N` bridge printed `ok` for all three gateways. The
  bridge does not forward POST, so every response was `{"error":"POST only"}` and the harness read
  "no `reason` field" as success. The conclusion "all three gateways are clean" was drawn from three
  identical errors.
- A quantity sweep looked non-monotonic (100 ok, 1000 INVALID, 100000 ok) and irreproducible, which
  is what finally pointed away from the input and at the routing. **An input-shaped hypothesis that
  produces unstable results is evidence about the variable, not noise to average away.**

Related: [[securities-need-admission-like-accounts]]
