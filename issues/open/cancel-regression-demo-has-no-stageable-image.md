# `yu13-cancel-ingress` has no image that can stage its regression demonstration

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Filed** 2026-08-18 by the coordinator, after trying and failing to restore it. **Open.**
Low severity: the proof's **forward claim runs in full** and passes. What is missing is the
before/after half that shows the pre-fix build could not cancel.

## How it broke

The default `IMAGE_PRE` was `traderx/cluster-node:yu15`. On 2026-08-17 that tag was retagged away by
me — it turned out to hold a YU16-intermediate build rather than a YU15 one, so the name was a lie
(`issues/mislabeled-cluster-node-images`). After that the regression half skipped on every run while
the suite summary line still read **PASS**, which is the coverage-loss-behind-a-green-verdict shape.

## Why it could not simply be repointed

Three requirements, and no local image meets all three. Measured 2026-08-18, each with a control:

| tag | `/cancel` in `ClusterGatewayMain` | probe server (18111) | committed ack |
|---|---|---|---|
| `yu12` | absent | present | **NO** |
| `yu13` | absent | **absent** (crash-loops the kubelet) | - |
| `yu14` | absent | **absent** (crash-loops the kubelet) | - |
| `yu15-pre` and later | **present** (not pre-cancel) | absent / present | - |

**`:yu12` was tried as the default and made things worse.** It rolls and comes up, then step 2 fails
with `{"error":"no committed ack"}` — it is far enough back that its gateway cannot get a committed
ack from today's members. A missing `/cancel` route and a gateway that cannot reach the engine are
indistinguishable from the probe, so the demonstration would be ambiguous even if it did not fail.
It turned a proof that passed its forward half into a red one, and was reverted.

**Marker note, because this cost time twice.** Grep the CLASS, not the image: `OrderController.class`
carries the string `/cancel` in every build back to `:yu12`, so an image-wide grep reads positive
everywhere and discriminates nothing. Scoping to `ClusterGatewayMain.class` makes it a real test —
`:yu17-fx` positive, `:yu12` negative.

## Current state

The default is deliberately a name that **cannot resolve** (`traderx/cluster-node:precancel-BUILD-ME`),
which routes to the existing skip branch rather than to a failure. That is better than a real-but-
mutable tag: a mutable tag can be rebuilt into something other than what its name says and then stage
a demonstration against the wrong build while looking correct, which is exactly what happened. A name
that cannot resolve can only skip, loudly, carrying its own remedy.

## The fix

Build a pre-cancel image from the commit before the cancel route landed in `ClusterGatewayMain`, tag
it immutably (a date-stamped name, not a state name), and set it as the default here. Verify all
three properties against controls before trusting it — the route absent from the gateway class, the
probe server present, and a committed ack obtainable against current members.

## The wider gap this sits on

The suite summary has only PASS / SKIP / FAIL. A proof whose forward half passes while a documented
half cannot stage reports **PASS**, and only its log says otherwise. That is legible to someone
reading the log and invisible to someone reading the roll-up — the same who-reads-it problem booked
in `vacuous-pass-audit`. Worth considering a partial verdict, though not urgent while this is the
only known instance.
