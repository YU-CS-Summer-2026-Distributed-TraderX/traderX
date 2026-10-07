# Risk-capacity metrics plan

Owner: Codex RI22. Accepted base: `a0d6da0b`; isolated task branch `codex/ri22-risk-gauges`. Review and integration are coordinator-owned.

1. Reproduce missing gauges through the actual unmodified member HTTP handler while authoritative reservation is nonzero.
2. Trace operative YU18 source owners and existing account reservation/execution/total accessors, order units and sequenced swap FX conversion.
3. Append a cold formatter to member metrics; enumerate occupied account tuples without changing the risk class. Emit zero rows, explicit availability and raw tick units. Keep the gateway lane and shared indexes untouched.
4. Exercise actual formatter/handler in an inherited source composition, regenerate YU18 and check exact source/generated parity and related accounting regressions. Check nonzero allocation reports and unchanged deterministic sources/wire/snapshots; qualify the JVM profile.
5. Preserve evidence and scoped commit, post READY_FOR_REVIEW locally, and release the lane. Broader RI22 tasks and shared index additions are proposed for coordinator review.

No atomic read snapshot is introduced; racing reads remain samples. The existing saturating total is reported directly. Existing accountTuples avoids introducing a new accessor or altering snapshot tuple layout.
