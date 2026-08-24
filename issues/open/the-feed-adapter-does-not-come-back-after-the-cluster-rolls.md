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

So the failure outlived its cause by an unbounded margin, and the repair is a pod delete.

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
