---
title: EOD integration and recovery boundaries
---

# EOD integration and recovery boundaries

This describes source at `4c2c4eb2`. Implemented support, an enabled profile and a validated production installation are different claims.

## From a portfolio cut to a result

1. Cut the sequenced portfolio and write CSV plus a completion receipt.
2. Bind the immutable bundle, terms and private coordinator snapshot.
3. Submit to the external EOD worker and retain the original response.
4. Validate identity and semantics before exposing read-only job and Risk views.

The receipt binds exact exporter bytes and the common cut. Bundle validation checks schemas, hashes, item identities and terms. The market-input envelope records source, observation/publication/retrieval times and suitability decisions. A structurally valid envelope is not a calibrated curve; a recent arrival timestamp is not evidence of recent market observation.

Mock and W0 paths validate orchestration and structure without producing usable risk. Provisional pricing and container intake add their own profile checks. The accepted container adapter uses synchronous `/eod/price`, not the unrelated `/portfolio/price` simulation API. It stages a private copy, preserves the original HTTP response and validates identities, calculation status, units and coverage before accepting derived views.

The current container profile admits a fixed synthetic Treasury-bill bundle and the assumed `flat-3pct-v1` curve. Other bundles, missing market inputs and unsupported conventions refuse or report unavailable calculations. It does not supply general live-trade portfolio pricing, Greeks, VaR or ES. `usableForRisk=false` and `portfolioRiskAvailable=false` remain visible.

### Submission and custody

A submission whose delivery cannot be determined becomes UNCERTAIN. Read-only reconciliation can resolve evidence; the adapter does not blindly resubmit a potentially accepted job. Consumer restart reuses the retained attempt/result, and completed data is revalidated on reads. A stored RUNNING value is not a worker liveness check. Missing configuration or state returns unavailable, not fabricated success or an empty portfolio.

## Run identity and projections

The authoritative managed-run descriptor binds an epoch to the SQL projection scope. Explicit migration/transition validates the boundary and retained rows. Legacy rows do not acquire managed identity merely because a reader selected a run. Historical runs are read-only.

Explicit archive catch-up verifies sequence, output order, identity and immutable economics before adding missing rows. It refuses missing archive history, ahead/orphan rows, unexplained balances and conflicting retained state. A database insert or successful HTTP response alone does not prove that engine state and projection agree.

## Optional automatic catch-up

The automatic worker is **default off**. With the additive migrations and configured managed profile, startup, subscriber reconnect and periodic checks acquire a lease/fence and read complete command pages from the authoritative archive. A transaction applies validated rows, re-derives affected positions, advances the cursor and records the verification boundary. Notifications follow commit and remain best-effort.

`verifiedThroughSeq` and `lastSuccessfulVerificationAt` describe point-in-time completeness. Worker liveness, connection status and a checkpoint counter do not establish completeness.

Limits remain explicit:

- Non-idle pages replay from genesis on the serving member; history size affects cost.
- The verified cursor does not continuously detect later corruption below it. Explicit catch-up or transition verification is needed for that case.
- Page application shares the projection write lock with live booking.
- Transition VERIFY can exceed a database packet limit because its witness is stored in one row.
- Local single-member catch-up evidence does not establish multi-member HA, deployment throughput or cloud activation.

See the [state/component map](feature-map.md) for contracts and the [test guide](testing-strategy.md) for executable checks. Retained deployments require migration, archive and compatibility review before enabling this profile.
