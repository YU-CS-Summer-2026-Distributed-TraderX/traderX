# The feed adapter does not come back after the cluster rolls

**Filed 2026-08-23** by the coordinator, after finding the adapter in CrashLoopBackOff (8 restarts)
while the cluster it feeds was healthy 3/3 and had been for some time.

## What happens

`FeedAdapterMain` exits non-zero when it cannot connect to the cluster. On a cold start that is
right, and deliberate — it is the fix that stopped the adapter from reporting `1/1 Running` while
closing nothing, which is the vacuous shape this tier keeps paying for.

But the exit is unconditional, so any *transient* loss of the members' pod DNS names kills it, and
Kubernetes' CrashLoopBackOff then holds it down long after the cause has cleared:

```
Caused by: RegistrationException: UnknownHostException: unresolved
  - endpoint=order-matcher-cluster-2.order-matcher-cluster.traderx.svc.cluster.local:22002
```

The reliable trigger is **running the proof suite**. Several proofs roll the StatefulSet or mint a
fresh epoch; during the roll the per-pod DNS names do not resolve, the adapter fail-fasts, and each
subsequent restart doubles the backoff. By the time the suite finishes, the adapter is sleeping
through a perfectly healthy cluster.

## Why it is not merely cosmetic

The adapter is the only market-data path into the deterministic core. While it is down the books
stop receiving prices, so anything reading the book — `/bbo`, the collar's reference under ADR-066,
any future consumer of ADR-067's export — is quietly served a stale market. Nothing reports this:
the members are 3/3 Running, `applied` still advances on unrelated traffic, and the adapter's own
`FEED received=… dropped=…` line is absent rather than alarming. **An absent counter reads as a
quiet feed, not as a dead process.**

## Measured 2026-08-23

- adapter `0/1 CrashLoopBackOff`, `restarts=8`
- CoreDNS `1/1` ×2, and `getent hosts` resolved all three member names **from inside the cluster**
- `kubectl get endpoints order-matcher-cluster` listed all three addresses
- `kubectl delete pod -l app=feed-adapter` → `1/1 Running`, `restarts=0`, sequencing immediately

So the failure outlived its cause by a large margin, and the repair is a pod delete.

## The margin is BOUNDED at five minutes — measured 2026-08-25

"Unbounded" was the reasonable reading on 2026-08-23 and it is wrong; `CrashLoopBackOff` caps its
backoff at 5 minutes, so the adapter does retry, just rarely enough that nobody waits for it.
Reproduced by minting a fresh epoch under a live, sequencing adapter and then touching nothing:

```
17:00:46  session lost -> exit 1
17:01:12  members 3/3 on the fresh epoch, adapter NOT touched
17:01:12 .. 17:05:44   ready=false  restarts=6      <- no retry attempted; pod status says why:
                       state.waiting.reason  CrashLoopBackOff
                       state.waiting.message back-off 5m0s restarting failed container=feed-adapter
17:06:14  SAME POD     ready=true   restarts=7      <- retried, connected
17:06:44  SAME POD     ready=true   restarts=7  SYMBOL=69   <- sequencing again
```

Two things follow. **The pod delete is not load-bearing** — a new pod recovers instantly only
because its backoff starts at zero, not because pod identity matters; the same pod recovers on its
own at the cap. And **this issue and
[`a-fresh-epoch-strands-the-feed-adapter-and-only-a-new-pod-recovers-it`](../resolved/a-fresh-epoch-strands-the-feed-adapter-and-only-a-new-pod-recovers-it.md)
are one defect** — that issue read the same shape as "only a new pod recovers it" and is corrected
in place.

## Partially addressed 2026-08-25, and why this stays open

`rebuild_fresh_epoch` in `scripts/yu15/run-proofs.sh` now rolls the adapter and **asserts it is
sequencing** (>= 20 `SYMBOL <ticker>=<id>` round trips on a pod whose uid changed) before it claims
a fresh epoch. That covers every epoch mint in the suite, and `yu13-stp-and-replace` is last, so a
full run now ends with an asserted live feed instead of a hopeful one. It also answers direction 3
above — "do nothing and make it visible" — for the runner's own path, without adding the probe the
trap below warns against.

What it does NOT cover, and what keeps this open:

* a bare `kubectl rollout restart statefulset/order-matcher-cluster` outside the runner;
* the window DURING a suite between a member-rolling proof (`yu16-book-grid`,
  `yu16-ready-tracks-commit`, `yu16-liveness-restarts-wedge`) and the next mint — the adapter can
  be asleep for up to five minutes there and the proofs in between read a stale book;
* direction 1, the bounded retry inside `FeedAdapterMain`, which is the only fix that removes the
  five-minute hole rather than papering over the places it is noticed. The tuning question is
  unchanged: N must be longer than a proof suite's roll and shorter than a human's patience.

## Directions, none chosen

1. **Retry the connect with a bounded budget instead of exiting on the first failure.** Keeps the
   fail-fast property for a genuinely absent cluster (exit after N minutes of trying) while riding
   out a 30-second roll. The tuning question is what N is, and it must be *longer than a proof
   suite's roll* and *shorter than a human's patience*.
2. **Let Kubernetes do it** — leave the exit alone and cap the restart backoff. Simpler, but the cap
   is a cluster-level setting on kubelet, not a per-Deployment field, so this may not be available.
3. **Do nothing and make it visible.** If a pod delete is an acceptable repair, the defect is that
   nobody is told. A liveness signal derived from "time since last successful flush" would surface
   it, but see the trap below before reaching for that.

**Trap for direction 3, and for any probe added here:** a readiness/liveness probe that fails while
the adapter is legitimately idle would evict a healthy pod, and a probe keyed on the *cluster's*
health rather than the adapter's would be silenced by the very repair it is meant to guard. This is
`guard-interaction-audit` territory — the probe must not be satisfiable by something other than the
adapter actually flushing.

## Related

- `issues/resolved/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md` — the fix that
  introduced the fail-fast, correctly. This issue is its unbounded edge, not a regression of it.
- [ADR-045](../../specs/YU12-aeron-cluster/system/adr-045-feed-adapter.md) — the adapter as the only
  market-data path into the core.
