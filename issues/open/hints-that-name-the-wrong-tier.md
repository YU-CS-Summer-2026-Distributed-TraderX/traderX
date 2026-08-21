# Failure hints that name the wrong tier's plumbing

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Found 2026-08-20.** Predicted by the console lane after it fixed the same class in the UI, then
confirmed by grep. Filed unassigned — the console instances are fixed, this is the wider surface.

## The class

A message written on one rig and read on another, telling the reader to check something that does
not exist where they are. It has no failing test, because prose is not executed.

The console had five: an empty-sink banner asserting a PVC that only the kind rig has, and four
bridge errors naming "the dev proxy + kubectl" — neither of which exists on GKE, where those bridges
are the console's own in-cluster server using a ServiceAccount. **An error that names the wrong
subsystem is worse than a generic one: it spends the reader's attention before they have evidence.**

## Where else it lives

`port-forward` appears in 39 scripts, `kind-traderx` in 33, "kind rig" in 6. Most are comments or
correct-on-kind usage. The ones that matter are in **user-visible output**:

```
proofs/yu05-recon.sh:37        "$OM unreachable (curl 000) — port-forward svc/order-matcher 18110:18110?"
proofs/yu16-bond-position.sh:47  "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher …?)"
proofs/yu17-swap-netting.sh:131  same
proofs/yu13-otel-trace-join.sh:45-46  offers only the two KIND contexts as the values to set
```

On GKE the gateway is a `LoadBalancer` with a public IP and **there is no port-forward in the path at
all**. A proof run against GKE that cannot reach the gateway sends the operator to build a forward
that is not the fix, while the real cause — a wrong `MATCHER_URL`, the LB not yet assigned, a pool at
zero — goes unexamined.

## The fix that worked in the console, and why it generalises

Not environment detection in the message. **Let the side that knows do the talking.** The console's
bridges already returned their own specific reason (`kubectl exec failed — is the risk-extract pod
up?`); the panels were *discarding* it and substituting a guess. Preferring the real error and
falling back to a role-named generic only when nothing intelligible came back removed the whole class
without the client ever learning which tier it is on.

For the scripts the equivalent is: report what was actually observed (`curl rc=7 to ${MATCHER_URL}`,
which already distinguishes "nothing listening" from "timed out"), and name the *role* — "the gateway
is not reachable at ${MATCHER_URL}" — leaving the remedy to whoever knows which rig they are on.
Several proofs already do exactly this; `yu16-ready-tracks-commit.sh` is the model.

## Proven, not assumed

The console's fixed failure path was exercised live rather than reasoned about: scaling
`deploy/risk-extract` to 0 made `/extracts` return `{"error":"kubectl exec failed — is the
risk-extract pod up?"}` — the bridge's own diagnosis reaching the client, where it previously would
have been replaced by a claim about a dev proxy that does not run there. Restored immediately.

## Grep

`dev proxy` · `kubectl` · `kind` · `PVC` · `emptyDir` · `gs://` · `port-forward` · a cluster name
