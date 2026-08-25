# `kubectl rollout status` on the members returns before the cluster can sequence a write

**Measured 2026-08-25** during the format-8 mint, on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`. Fixed at the one call site that was hitting it
(`yu13-otel-reject-trace-log-join`); filed because the shape is general and there are other
roll-then-write sites that will meet it. **Fixed generally 2026-08-25** in `rebuild_fresh_epoch` —
see *Resolution*, including what it deliberately does not cover.

## The measurement

Members rolled and nothing else (`set env` on the StatefulSet, gateway pod untouched so the
port-forward in the path survives), then `POST /seed` — which registers a symbol, so it must reach
consensus — at fixed offsets after `rollout status statefulset/order-matcher-cluster` returned:

```
baseline (no roll):  {"seeded":true}
rollout status returned 12:20:43
  after +0s:   {"error":"TimeoutException"}
  after +10s:  {"seeded":true}
  after +20s:  {"seeded":true}
  after +30s:  {"seeded":true}
  after +60s:  {"seeded":true}
```

The window is under ten seconds. `yu13-otel-reject-trace-log-join` seeds inside it every time: it
failed on **three consecutive runs**, always at the same line.

## Why nothing in the readiness path is lying

- The members' `readinessProbe` is `/ready` on 8080. That answers as soon as the member's HTTP
  server is listening, which happens well before the member has rejoined consensus — the same
  property that makes a restarted member serve `phase=OPEN` on a CLOSED venue for ~24s
  (`lib-consensus-readings.sh`, `await_member_restored`).
- The gateway's `/ready` reports `{"connected":true}` throughout, and it is telling the truth about
  *its own* session.
- So `rollout status` returning is a correct statement about pods, and a proof that reads it as
  "the cluster will accept my write now" has substituted one for the other.

## The diagnosis cost, which is the part worth fixing generally

The gateway's REST handlers end in a catch-all:

```java
respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
```

so the client gets `{"error":"ExecutionException"}` or `{"error":"TimeoutException"}` — the class
name of the wrapper, never the cause — and **nothing is logged**: a gateway-log capture running
across three reproductions caught no exception at all. The 503 is honest that something failed and
carries no information about what. Surfacing the cause (or logging it once at WARN) would have made
this a one-minute diagnosis instead of an afternoon's.

## What changed

`scripts/proofs/yu13-otel-reject-trace-log-join.sh` now retries its fixture seed for up to 60s
before failing, with the measurement above recorded at the call site. The retry is scoped to
**fixture setup only** — the proof makes no claim about how quickly a seed lands, and the hard
failure still stands if the cluster never accepts it, so nothing asserted was weakened. A retry
around an *assertion* would be the vacuous-pass shape this suite refuses everywhere else.

## Not fixed, and worth a decision

The general remedy is a readiness reading that means "the cluster will accept a write", not "the
pods are up" — the same distinction `await_member_restored` draws for the phase. Candidates: a
gateway endpoint that reports its session's ability to sequence, or a member reading that
distinguishes rejoined-consensus from HTTP-listening. Until one exists, every roll-then-write site
carries this race, and it will present as whatever that site's first write happens to be.


## Resolution

`rebuild_fresh_epoch` in `scripts/yu15/run-proofs.sh` now ends its roll with `await_cluster_writable`
before it claims a fresh epoch. Every epoch mint in the suite goes through that one function — five
call sites — so this is the "readiness reading that means the cluster will accept a write" the
section above asks for, placed where the roll-then-write pairs actually are.

The reading IS the write: `POST /seed` with the throwaway ticker `ZZPROBE9`, retried every 3s for
up to 120s, and the mint fails hard if it never lands. Three details are deliberate:

* **It goes through the gateway POD**, `kubectl exec ... wget localhost:18110`, not a port-forward.
  `rebuild_fresh_epoch` is called before any forward exists (the baseline block) and from the stp
  wrap, where re-establishing one costs a gateway roll.
* **`ZZPROBE9` is the ticker the symbol-table probe further down already registers**, so the two
  share one symbol slot, and nothing reads its price.
* **It is skipped, loudly, when the gateway and the members are on different builds.** A `/seed`
  through a mismatched pair is refused for reasons that have nothing to do with writability — that
  refusal is the entire reason `stp_borrow_gateway` exists — so the probe only speaks when the pair
  matches. The stp prep, which mints onto a historical image with a tip gateway, takes that branch
  and prints two lines saying so.

This is a **setup gate, not an assertion**, exactly as the `yu13-otel-reject-trace-log-join` retry
is: the suite makes no claim about how fast a seed lands, and a retry around an assertion would be
the vacuous-pass shape this suite refuses. Nothing asserted was weakened.

### The arms

* **Negative, run 2026-08-25:** members scaled to 0 (PVCs kept), `await_cluster_writable 15`
  returned 1 — `cluster never sequenced a write within 15s (last answer: nothing)`.
* **Positive, run 2026-08-25:** on a healthy freshly-minted epoch it returned 0.
* **In situ, same day:** inside the suite's baseline mint it printed
  `[epoch] cluster sequenced a write 0s after the roll`. **Zero seconds is worth reading
  carefully** — it means the window this issue measured had already closed by the time
  `rollout status` returned, which is a plausible side effect of the `startupProbe` added to the
  members the same day (`rollout status` now also waits for startup, so it returns later). The gate
  added no delay on this run. Its value is the refusal, not the wait; a gate that costs nothing
  when the property holds is the correct shape.

### Not covered, deliberately

* **Rolls that do not go through `rebuild_fresh_epoch`** — `roll_to()` inside
  `yu13-stp-and-replace`. The single-member restarts already carry the right gate for their case,
  `await_member_restored` (a changed pod uid AND a target applied sequence), which is the
  member-side sibling of this one: `yu17-closed-survives-restart` and `yu17-halt-survives-failover`
  always did, and **`yu17-retick-determinism` now does too** — it had a hand-rolled wait that broke
  on `get pod ... ready == true`, which is still true for ~6s for the pod it had just deleted, so
  step 6 read a member whose JVM had no HTTP server yet. It failed a full suite run on 2026-08-25
  that way. Same defect class as this issue, one layer down: a reading taken before the thing it
  measures has finished.
* **The diagnosis cost** the section above calls "the part worth fixing generally" — the gateway's
  catch-all still answers `{"error":"TimeoutException"}` with the wrapper's class name and logs
  nothing. That is a gateway code change, was not touched here, and remains the reason a future
  instance of this shape will cost an afternoon rather than a minute.

### The full suite, 2026-08-25

`DESTRUCTIVE=1 bash scripts/yu15/run-proofs.sh` on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`: **38 passed, 0 skipped, 1 failed**. The one failure is
`yu17-session-opens-from-close`, a proof landed by a concurrent chip an hour earlier whose
`/eod/session/previous` route is committed in source but not yet on the running trade-processor
image — it fails at step 1 with a 404, has nothing to do with the epoch procedure, and is not this
chip's to make green. Everything the procedure change touches ran and passed, including the whole
rolling tail (`yu13-stp-and-replace`, `yu13-cancel-ingress`, `yu16-book-grid`,
`yu16-liveness-restarts-wedge`, `yu17-halt-survives-failover`, `yu17-closed-survives-restart`).
