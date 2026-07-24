# 04 — Milestone unit tests across the YU states + integration tests

> The second half of the testing ask. Dov's framing: **you need not fully test every single state** —
> pick reasonable points where 2–3 states add up to a new logical/functional milestone, and test *there*.
> Plus the integration tests that prove the services actually work together.
> Lane: implementation. Can start on our own layers **without** waiting for brief 01. See [[00-INDEX]].

## Part A — milestone unit tests

Don't march state-by-state. Identify the **functional milestones** the lineage actually delivers and
test at those boundaries. Candidate milestones (confirm against brief 02's map before committing):

- **Risk gateway** (YU03–YU04) — two-tier in-memory risk, the control plane, and durable control feeds
  (outbox + watermarked-snapshot bootstrap). High narrative value; also the surface brief 07 builds on.
- **Post-trade / compliance** (YU05) — settlement lifecycle, reconciliation, regulatory export, TCA, auth.
- **EOD pricing + batch chain** (YU06) — the quality gate and the fail-safe consumer halt.
- **Replication → consensus** (YU11–YU12) — failover, snapshot/replay, cold-follower rejoin.
- **Order book + lifecycle** (YU13) — largely covered already; verify rather than rewrite.
- **Options + risk extract** (YU14–YU15) — covered; verify.

The engine/consensus layers are already strong (269/283/300 green). **Spend the budget where 02 says
it's thin**, and prefer *characterisation* tests at milestone boundaries over exhaustive per-state suites.

## Part B — integration tests (the bigger gap)

The professor asked for integration tests explicitly, and this is where we're weakest in CI. Several
capabilities are proven today only by **runnable falsifiable shell/node scripts** in `scripts/bench/`
(cancel, ClOrdID suppression, atomic replace, FIX status, the risk extract, the read-model effect-end).
Those are good proofs — several were genuinely falsified before passing — but **they don't run in CI**,
so they can rot.

**The highest-value work here is turning those proofs into automated integration tests**, plus covering
the cross-service seams that nothing exercises today: order → match → egress → read model → REST/FIX
enumeration, and the control-plane path (limit change → in-memory limits → order rejected).

Decide deliberately what runs where: fast in-process/testcontainer integration tests in CI, versus
full-cluster proofs that stay manual but are documented and runnable. Both are legitimate; **saying
which is which is the credibility win.**

## Traps

- **kind is at its limit for three-member cluster proofs on the dev host** (4 nodes idling at 145–205%
  CPU on an 11-CPU Docker VM from Aeron busy-spin; 2.3× run-to-run spread; timing-sensitive flakes).
  For correctness-only work set the opt-in `CLUSTER_IDLE_SLEEP_MS=1`; it makes the rig usable for
  correctness but **disqualifies it for any timing/throughput claim**.
- Run gradle suites **one at a time** (concurrent builds break `ThreeMemberClusterTest`).
- Assert at the **effect end**, not the ingress: a 200 from the gateway has repeatedly meant nothing
  booked. Order-level effects now have a SQL effect end (the `orderbook` read model) — use it.
- Ground truth for anything order-count-related is the member `traderx_cluster_next_order_ref` delta,
  never a gateway "accepted"/booked counter.

## Deliverable

Milestone suites green in CI, the highest-value proof scripts promoted to automated integration tests,
and a short written statement of the test strategy (what's unit, what's integration, what stays manual
and why). That statement is a slide.

## Conventions

Commit per milestone; propagate to descendant branches verifying two ways; `git push` goes to yaakov.
