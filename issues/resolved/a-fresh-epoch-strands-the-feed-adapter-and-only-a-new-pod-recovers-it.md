# A fresh epoch strands the feed adapter, and nothing notices for five minutes

**Measured 2026-08-25** at the end of the format-8 mint, on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`. **Fixed 2026-08-25** in `rebuild_fresh_epoch` — see
*Resolution*, and read *THE MECHANISM WAS MIS-ATTRIBUTED* first, because the sentence in this
file's original title turned out to be false.

> **CORRECTED 2026-08-25 by measurement.** The original title was "restarting its container does
> not recover it", and the body below claims a NEW POD is required. **It is not.** Reproduced on
> the rig: the adapter recovers **in the same pod, from an in-place container restart**, 5m28s
> after the mint. The 5m28s is `CrashLoopBackOff`'s five-minute cap, which is the whole defect —
> not pod identity. The observations recorded below are kept verbatim because they are true; the
> INFERENCE drawn from them is wrong and is corrected at the end.

## What was observed

After `yu13-stp-and-replace`'s tail minted a fresh epoch on the baseline image, the rig looked
healthy in every way except one:

```
order-matcher-cluster-0/1/2   1/1 Running   0 restarts   digest-identical, applied 2258/2258/2258
cluster-gateway               1/1 Running   0 restarts
feed-adapter                  0/1           31 restarts   <- crash-looping, NOT ready
```

The adapter's log, every restart:

```
io.aeron.exceptions.TimeoutException: ERROR - cluster connect timeout:
  state=AWAIT_PUBLICATION_CONNECTED messageTimeout=5s
  ingressEndpoints=0=order-matcher-cluster-0...:21802,1=...:21902,2=...:22002
  ingressPublication=null egress.isConnected=false
  responseChannel=aeron:udp?rejoin=false|endpoint=10.244.3.165:40490
    at finos.traderx.ordermatcher.cluster.FeedAdapterMain.run(FeedAdapterMain.java:80)
```

It never recovered on its own. **31 in-place container restarts, none of which worked.** A single
`kubectl rollout restart deploy/feed-adapter` — a NEW POD — recovered it on the first try:

```
feed-adapter-7dd9d8684d-kvmxq   1/1 Ready   0 restarts
SYMBOL FNMA=517
SYMBOL GLD=523
...
```

## Why the container restart is not enough

The container restarting in place keeps the pod, and therefore keeps whatever Aeron client state
lives in the pod's `/dev/shm` and its IP. A new pod clears both. The exact mechanism is not pinned
here — stale client/driver state in shared memory is the obvious candidate, and it is the same
family as the `active mark file detected` loop recorded elsewhere — but the OBSERVABLE is solid and
reproducible: same image, same config, same members; restart-in-place fails indefinitely, new pod
succeeds immediately.

## Why it goes unnoticed

`rebuild_fresh_epoch` in `scripts/yu15/run-proofs.sh` rolls the gateway after minting an epoch:

```bash
${K} rollout restart deployment/cluster-gateway
${K} rollout status deployment/cluster-gateway --timeout=600s || fail_hard "..."
```

and does **not** do the same for `feed-adapter`, which is the third Deployment running the
cluster-node image and is a cluster CLIENT exactly as the gateway is. So every fresh epoch leaves
the adapter to reconnect on its own, and it cannot. Nothing fails loudly: the suite still passes,
because proofs that need prices either seed their own or run before the adapter dies. The rig is
simply left without a live price feed, and the next lane inherits it.

This also partly re-explains the pre-mint `feed-adapter 97 restarts` recorded in
[`a-large-epochs-replay-outlasts-the-members-liveness-budget`](a-large-epochs-replay-outlasts-the-members-liveness-budget.md).
That issue attributes them to the members crash-looping underneath, which was true at the time —
but the adapter would not have recovered by itself even once the members settled.

## The remedy

Give the adapter the same two lines the gateway gets in `rebuild_fresh_epoch`, guarded for the case
where it is scaled to zero:

```bash
${K} rollout restart deployment/feed-adapter
${K} rollout status deployment/feed-adapter --timeout=600s
```

Not done here because `rebuild_fresh_epoch` is the runner's epoch procedure, shared by every lane
and every proof that mints an epoch, and changing it wants a full suite run to confirm — which is a
chip of its own, not a footnote to the mint.


---

## THE MECHANISM WAS MIS-ATTRIBUTED — measured 2026-08-25, same rig, same build

The remedy this issue asks for is right. The reason it gives is not, and the wrong reason would
have sent the next person looking at `/dev/shm` and pod IPs. Three measurements settle it.

**1. `dirDeleteOnStart(true)` is already set.** `FeedAdapterMain.run()` launches its MediaDriver
with `.aeronDirectoryName(aeronDir).dirDeleteOnStart(true)`, so an in-place restart cannot inherit
the previous JVM's Aeron directory — the "stale client/driver state in shared memory" candidate
named above is closed by the code itself.

**2. A brand-new pod buys nothing when the members are absent.** With the StatefulSet at 0
replicas, `rollout restart deploy/feed-adapter` produced a fresh pod which crash-looped for the
full four minutes and registered zero symbols. (Its failure was a publication-registration error
rather than this issue's connect timeout — the members' per-pod DNS names do not resolve at 0
replicas — so this measures pod identity, not the original error path.) Reachable members are the
requirement; a new pod is not.

**3. The same pod recovers on its own — it is just asleep.** A live, sequencing adapter (69
symbols) was left untouched while a fresh epoch was minted underneath it, exactly as the pre-fix
`rebuild_fresh_epoch` left it:

```
17:00:29  pre-mint: adapter feed-adapter-d7bbfdcd4-69fdp SYMBOL=69 restarts=6
17:00:46  session lost -> IllegalStateException: cluster session lost (offer=-4) -> exit 1
17:01:12  members 3/3 on the fresh epoch, gateway rolled, adapter NOT touched
17:01:12 .. 17:05:44   same_pod=yes  ready=false  restarts=6     <- NO restart attempted at all
17:06:14  same_pod=yes  ready=true   restarts=7  SYMBOL=0        <- it finally retried, and connected
17:06:44  same_pod=yes  ready=true   restarts=7  SYMBOL=69       <- sequencing again
```

and the pod's own status says why the gap is five and a half minutes and not five seconds:

```
state: waiting
  reason:  CrashLoopBackOff
  message: back-off 5m0s restarting failed container=feed-adapter pod=feed-adapter-...
```

**So the defect is `CrashLoopBackOff`'s 5-minute cap, not pod identity.** The adapter fail-fasts
correctly when its session dies (that exit is a deliberate fix — see the related issue below), the
backoff has already climbed to its cap by the time a suite has rolled the members a few times, and
the kubelet then sleeps through a perfectly healthy cluster. `rollout restart` "fixed it first try"
because a new pod's backoff starts at zero, not because the old pod could not have done it.

**Why the 31 restarts read as "never recovered":** they were accumulated across the whole preceding
suite (which rolls the members repeatedly), not 31 attempts against a healthy cluster. The adapter
was observed down and repaired immediately; nobody waited out the cap. This makes this issue and
[`the-feed-adapter-does-not-come-back-after-the-cluster-rolls`](../open/the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md)
**one defect seen twice**, and that issue named the cause correctly on 2026-08-23.

## Resolution

`rebuild_fresh_epoch` in `scripts/yu15/run-proofs.sh` now ends with `roll_feed_adapter`, which:

1. skips silently when there is no `feed-adapter` Deployment, and says so loudly when it is scaled
   to 0 (the stp prep deliberately does that — see below);
2. repins it to the members' build if it has drifted, because it is a cluster client speaking the
   ingress codec and a stale one cannot round-trip anything;
3. `rollout restart` + `rollout status` — a new pod, whose backoff starts at zero. **This buys
   latency, not recovery**, and the distinction matters for whoever reads this next: left alone the
   adapter comes back by itself within five minutes, so the roll is not load-bearing for
   correctness. What it removes is a five-minute window in which the rig has no price feed on the
   state where that feed is the collar's reference and the price grid's input;
4. **asserts the round trip.** This is the part that matters. "The pod is Ready" is worth nothing
   here: the Deployment carries no readinessProbe, so a container that starts and then times out
   connecting is Ready for its whole doomed life. The gate waits for the pod UID to CHANGE and
   then for >= 20 `SYMBOL <ticker>=<id>` lines in **that pod's** log. A SYMBOL line is printed only
   when a registration the adapter offered came back on the egress — ingress publication
   connected, command sequenced and committed by the members, ack delivered. A stranded adapter
   prints none. Failing to reach it inside 240s is `fail_hard`.

The stp prep now scales the adapter to 0 alongside the control feed and the observability stack —
it is a tip-build cluster client and the prep rolls the members onto historical images, which is
the mismatch `stp_borrow_gateway` exists to work around, one Deployment further along; and that
epoch is meant to hold only the proof's own fixtures, not 69 instruments' ticks. The stp restore
block scales it back to 1 **before** its `rebuild_fresh_epoch`, so `roll_feed_adapter` brings it up
and asserts it. `yu13-stp-and-replace` is the last proof in the suite, so a full run now ends with
an asserted live feed.

### The arms, and what they can still catch

* **Negative, run 2026-08-25:** members scaled to 0, `roll_feed_adapter` invoked. The outgoing pod's
  log held the previous epoch's **69** SYMBOL lines. It rolled a new pod, counted **0**, and failed
  at 240s. A gate that had read `kubectl logs deploy/feed-adapter` without requiring a changed pod
  UID would have passed there — that is the trap the UID check exists for.
* **Positive, run 2026-08-25:** healthy cluster, `roll_feed_adapter` returned 0 in **20s** with 67
  symbols round-tripped.
* It still catches: an adapter that cannot reach the members, one on a mismatched codec generation
  after the repin, one whose NATS subscription is dead (no ticks -> no registrations -> no SYMBOL
  lines), and a cluster that accepts the connect but cannot commit.
* It does **not** catch a feed that is connected and sequencing STALE prices — nothing here reads
  price values, only that registrations round-trip.

## Related

- [`the-feed-adapter-does-not-come-back-after-the-cluster-rolls`](../open/the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md)
  — the same defect, named correctly two days earlier, and still open: this fix covers every
  `rebuild_fresh_epoch` site, not a bare `kubectl rollout restart sts` outside the runner.

### The full suite, 2026-08-25

`DESTRUCTIVE=1 bash scripts/yu15/run-proofs.sh` on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`: **38 passed, 0 skipped, 1 failed**. The one failure is
`yu17-session-opens-from-close`, a proof landed by a concurrent chip an hour earlier whose
`/eod/session/previous` route is committed in source but not yet on the running trade-processor
image — it fails at step 1 with a 404, has nothing to do with the epoch procedure, and is not this
chip's to make green. Everything the procedure change touches ran and passed, including the whole
rolling tail (`yu13-stp-and-replace`, `yu13-cancel-ingress`, `yu16-book-grid`,
`yu16-liveness-restarts-wedge`, `yu17-halt-survives-failover`, `yu17-closed-survives-restart`).
