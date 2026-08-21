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

---

## Resolved 2026-08-21

Fifteen messages across twelve `scripts/proofs/` files. The shape is the issue's own prescription and
`yu16-ready-tracks-commit.sh`'s: **report the observed rc and name the role, prescribe nothing.**

    - || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
    + || fail "the gateway is not reachable at ${MATCHER_URL} (curl rc=$?; 7=nothing listening,
    +   28=timed out, 22=it answered but /ready was not 2xx)"

`curl -f` makes 22 mean "it answered, with an error", so the one line now separates three causes the
old one collapsed — and it does not send a GKE reader to build a forward.

### What changed

| Site | Was | Now |
|---|---|---|
| 7 identical preflights — `yu13-clordid-suppression`, `yu15-option-persistence`, `yu16-bond-position`, `yu16-book-grid`, `yu17-fx-credit`, `yu17-swap-netting`, `yu17-swaption-terms` | `(port-forward svc/order-matcher 18110:18110?)` | observed `curl rc`, role named, no remedy |
| `yu05-recon.sh`, `yu05-regulatory-reproducible.sh` | `✘ $OM unreachable (curl 000) — port-forward …?` | rc captured off the curl that failed, then reported; "the transport, not a recon verdict" |
| `yu03-risk-proof.sh` | `UNREACHABLE — no response from %s (is the port-forward up?)` | `(curl rc=%s; 7=nothing listening, 28=timed out)` |
| `yu13-otel-trace-join.sh`, `yu13-otel-reject-trace-log-join.sh` — `need_obs` | `[hint] bash scripts/yu15/start-observability-kind.sh` | both bring-up scripts named symmetrically; the forward hint conditioned on the rig using one |
| `yu13-otel-reject…` cleanup note | `your own gateway port-forward died with the rolled pod` | conditioned — *if* you reach it through a forward of your own |
| `yu13-otel-reject…` seed diagnostic | `000 = no answer at all (a dead port-forward or …)` | `000 = nothing answered at ${MATCHER_URL} at all` |
| `yu16-book-grid.sh` rebuild line | `(emptyDir — it returns with no disk)` | reads `volumeClaimTemplates` off the rig and says what is actually there |

### Two corrections to this issue as filed

**`yu13-otel-trace-join.sh:45-46` was not a defect.** The block is three lines, not two: line 47 has
offered `CTX=<gke context>  (to assert against GKE)` since `6d47febb` (2026-08-16), four days before
this was filed. Reading 45-46 alone made a symmetric enumeration look like a kind-only one. Left as
it is.

**`svc/order-matcher` on GKE is `ClusterIP`, not the LoadBalancer.** The LB is a *different service*:
`order-matcher-gw`, `104.196.202.136:18110`. So the old hint was worse than the issue says — it named
a service that exists on GKE and is not the one in the path, which is the most believable kind of
wrong.

### Exercised, not reasoned about

Every changed string was printed before commit.

- **GKE-shaped, no forward anywhere in the path.** `http://104.196.202.136:18110/ready` (the real LB)
  passes the preflight; the same IP on a closed port prints `curl rc=28` at that URL — precisely where
  the old text said "port-forward svc/order-matcher 18110:18110?".
- **Three transport causes separated**, on the real scripts: `rc=7` (127.0.0.1:59999), `rc=28`
  (192.0.2.1, TEST-NET-1), `rc=22` (a local stub answering 503).
- **`yu16-book-grid`'s new branch against the live kind rig**: `volumeClaimTemplates` is `data`, so it
  prints the PVC form. The old unconditional `emptyDir` line was false on the rig it runs on — which
  `yu17-swap-netting.sh` had already recorded in a comment.
- `bash -n` clean on all twelve.

### The sweep

25 emitting lines in `scripts/proofs/` named a tier word (`port-forward`, `kind-traderx`, `kind rig`,
`minikube`, `dev proxy`, `PVC`, `emptyDir`), out of 421 raw hits including comments. 15 changed. The
other 10 pass the test and were left:

- `yu13-cancel-ingress.sh:164-165` and `yu10-fix-session.sh:101-102` name a forward **the script
  itself creates** (`start_pf`, and line 89). That is the legitimate case.
- `yu13-gke-replace-proof.sh:211`, `yu12-gke-restore-from-gcs.sh:141` — GKE-only proofs describing the
  one rig they run on.
- `yu17-swap-netting.sh:403/406` — reads the backing off the rig rather than asserting it.
- `yu13-otel-trace-join.sh:45-47` — see the correction above.

### Still open

`yu16-book-grid.sh` deletes the victim pod but **not its PVC**, so on today's PVC-backed StatefulSet
the "rebuild" comes back with its disk and replays only the tail. The identity assertion still holds,
but it no longer exercises a from-nothing rebuild. `yu17-swap-netting.sh` deletes the claim for
exactly this reason. That is a vacuous-pass question, not a prose one, and it needs a rig run to
change — filed here rather than fixed silently.

Playbook: `.claude/skills/prose-has-no-test`.
