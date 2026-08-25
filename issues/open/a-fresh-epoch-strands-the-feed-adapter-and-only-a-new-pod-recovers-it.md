# A fresh epoch strands the feed adapter, and restarting its container does not recover it

**Measured 2026-08-25** at the end of the format-8 mint, on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`. Filed, not fixed: the one-line remedy is obvious but it belongs
to whoever owns `rebuild_fresh_epoch`, and the mint chip had no mandate to change the runner's
epoch procedure.

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
