# The risk-control snapshot carries securities but no accounts

**Found 2026-08-20** by the console lane, confirmed here. Filed unassigned and NOT urgent — the
practical case is already covered by bring-up admitting every directory account.

## The gap

`GET /risk/control/snapshot` returns the control replica's securities and nothing about accounts:

```
{"sourceEpoch":1787233941869,"watermark":0,"count":0,"securities":[]}
```

So **nothing can read which accounts the engine has admitted.** The directory (account-service) and
the engine's admitted set are separate, and only the second is reset by an epoch roll — but the
separation is invisible to any client, because only one side of it is readable.

## What that costs

Every account picker in every UI lists the directory. On a rig that has not been through bring-up,
some of those accounts reject every order with `UNKNOWN_ACCOUNT` — a reason code that reads as *"no
such account"* when the account exists and is merely unadmitted. A picker cannot mark, filter or warn
ahead of time, because there is no read path to mark from.

The console lane declined to guess, correctly: marking options from anything other than a reading
would be "a guess dressed as a reading". It went reactive instead — read the engine's own rejection,
explain the two-sets distinction, and offer a one-click admit through the same consensus control. Two
limits it stated rather than papered over: it cannot fire in a **batch** session (a batch answers
with a count, not per-order reasons), and it needs one rejected order first.

## The fix

`controlReplicaJson()` in `ClusterGatewayMain` carries the admitted account set alongside
`securities`. Read-only, additive, no new endpoint. A picker could then mark admission before a
single order is sent, and the failure becomes unreachable rather than merely explained.

## Why it was not done at the time

`ClusterGatewayMain` is the file that carries option B's ack correlation on five branches, and the
change costs a gateway rebuild and roll. With bring-up now admitting every directory account
(`faa47710`), the live failure is closed; this is defence against a rig that skipped bring-up. Worth
doing deliberately, in a window where the gateway is being rolled anyway — not on its own.

## Adjacent, checked while here

Both halves of the control endpoint's guard return the same 401 body — a wrong `X-Risk-Control-Token`
and a missing `X-Risk-Operator` are indistinguishable to the caller (`{"error":"invalid risk-control
credentials"}`). That is ordinary practice for auth and is NOT filed as a defect; noted only so the
next person debugging a 401 knows the message does not narrow it down.
