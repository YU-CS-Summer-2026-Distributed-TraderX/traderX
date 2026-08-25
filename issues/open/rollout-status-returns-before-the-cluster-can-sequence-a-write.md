# `kubectl rollout status` on the members returns before the cluster can sequence a write

**Measured 2026-08-25** during the format-8 mint, on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`. Fixed at the one call site that was hitting it
(`yu13-otel-reject-trace-log-join`); filed because the shape is general and there are other
roll-then-write sites that will meet it.

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
