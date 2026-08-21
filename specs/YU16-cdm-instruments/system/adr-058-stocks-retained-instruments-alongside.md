# ADR-058: `/stocks` is retained and `/instruments/control-snapshot` is added alongside it

Status: Accepted

## Context

The folded source pack (016) removed `/stocks` and `/stocks/{ticker}` outright — its FR-01602
required removal without alias or redirect, and its SC-01607 asserted `GET /stocks` returns 404.
On this line that removal breaks a shipped feature: `/stocks/control-snapshot` is the YU04
durable control feed's bootstrap source, load-bearing for `yu04-live-delta`,
`yu04-offline-catchup` and the proof-suite readiness gate. The general name is genuinely more
correct — the feed carries the whole security universe, the engine's command is already
`SECURITY_CONTROL`, and with Treasuries in the universe "stocks" is simply wrong — but a
flag-day rename moves every consumer at once, and the source pack is its own cautionary tale:
having removed the route, it needed SC-01610 to assert that its parent's lifecycle scripts,
which still probe `/stocks`, were untouched.

There is exactly one runtime consumer of the snapshot URL and it is already config-driven:

```java
@Value("${risk.bootstrap.securities-snapshot-url:http://reference-data:18085/stocks/control-snapshot}")
```

## Decision

Additive, never a rename. `/stocks`, `/stocks/{ticker}` and `/stocks/control-snapshot` keep
serving their exact inherited contracts. `/instruments/control-snapshot` is added, serving the
identical contract over the same store and the same outbox watermark. The bootstrap default
repoints to the general route at this state's layer — a property default, not a code change; the
YU04 layer's `@Value` fallback is untouched. The two YU04 proofs migrate to the general route;
the suite readiness gate keeps probing `/stocks/control-snapshot`, turning it into the standing
check that retention holds. YU04's architecture is amended by declaration in this pack, not by
editing YU04's.

The supersession is declared by source id — FR-01602 superseded, SC-01607 not adopted — so the
divergence reads as a decision, not an oversight.

The durable feed's stream and subject (`TRADERX_CONTROL_SECURITY`,
`traderx.control.security.deltas`) are not renamed: a JetStream consumer's position is keyed to
the stream, so that rename is a genuine flag day. Recorded as TD-CDM02.

## Consequences

- The YU04 feature and both its proofs keep working, on the general route, with zero code
  change to YU04's layer.
- Two routes serve one snapshot; the contract test asserts they agree (same watermark, same
  rows) so they cannot drift apart silently.
- A future retirement of `/stocks` is a deliberate flag day with a checklist (bootstrap env,
  readiness gate, ancestor lifecycle scripts, dashboards), not a tidy-up.
- The route family and the stream family disagree on naming until that flag day (TD-CDM02).
