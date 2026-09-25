# Combined managed recovery and console acceptance

2026-09-25. Local synthetic acceptance in `codex/managed-acceptance-20260925`, based on
`29ad72cb1157ed5796da35c8e835a81733768045`. The unchanged integrated runtime **failed** catch-up;
the narrow timestamp comparison correction described below is required for the passing result.

## Reproduce on disposable local resources

Reserve ports 27800–27999 and container names `traderx-ma-20260925-sql` and
`traderx-ma-20260925-nats` with the coordinator. Select Java 21, Docker, Node/npm and Chrome.
Generate the owned worktree sequentially, then run:

```bash
bash pipeline/generate-state.sh YU18-risk-integration
# JAVA_HOME must name the installed Java 21 home. OUT must be a new evidence directory.
export JAVA_HOME=/absolute/path/to/java21
export OUT=/absolute/path/to/new-proof-directory
bash specs/YU18-risk-integration/components/managed-run-ui/proofs/test-managed-acceptance.sh
```

The launcher builds actual generated matcher classes and trade/position/account service jars,
builds the production console and serves its shipped `server.mjs`. The small fixture edge routes
requests and NATS WebSockets; it fabricates no service responses. Optional unrelated services
(including algo, reference-price and observability services) are absent. This is not full-rig readiness.
The launcher refuses existing named containers, records owned IDs and removes only those IDs on
exit after dumping SQL. It waits for each child JVM to exit, escalating only its owned child if needed. It preserves logs, archive/storage, screenshots and executable identities.
Do not run it against any retained rig. Tests reserve `traderx-o1-controls-sql` separately.

The NEW empty database is initialized using the generated ConfigMap's **900-migrations.sql**
desired-state schema, which creates tables/reference accounts but no demo trades/positions.
The canonical `ri06.sql` and additive `ri06-event-recovery.sql` are then applied explicitly.
The driver asserts zero trades, orders and positions before starting services. It never runs the
demo initializer and never deletes projection rows to obtain acceptance.

## Scenario and observed result

Three separate **single-member** Aeron runs are used sequentially as trading sources, with their
own gateways, immutable descriptors and empty storage. There is no multi-member or HA claim.
A synthetic nonempty legacy bootstrap supplies the real archive witness required by the controller;
an empty history is intentionally not accepted. Both managed descriptors are installed using the
actual offline provisioning tool. Every transition phase goes through the actual HTTP controller.

1. Cross a bootstrap trade, adopt its explicit legacy descriptor, then prepare/freeze/verify/select/
   activate the first managed run. No SQL phase or pointer updates substitute for controller actions.
2. Cross quantity20 in `fresh-live`: two trade legs, two FILLED orders, signed positions +20/-20.
   Keep Trading and a separate Admin page open throughout the following outage and transition.
3. Stop the actual consumer JVM, cross quantity30, restart it. SQL still has only the first two
   legs; restart alone does not replay core-NATS loss. Both open pages lack the missed trade.
4. Invoke direct operator archive catch-up. The complete boundary is sequence18 with12 events.
   SQL has exactly `e1-freshlive-{1-B,2-S,3-B,4-S}`, four exact FILLED order IDs, and positions
   +50/-50. Previously retained trade columns remain unchanged. Both open pages converge; raw
   real-NATS scoped trade notifications and UI subscriptions are recorded.
5. Retry: identical response/witness; trades, orders, positions, recovery checkpoint and run
   registry all remain unchanged.
6. Use the real controller to transition to `next-live`, cross quantity7, and observe both open
   pages select the new scope and its trade. UI subscriptions and actual notifications use the new
   scope. All prior managed trades/orders/positions remain unchanged. Named old history is SEALED,
   read-only, isolated from the new run, and account selection isolates buy/sell rows.
7. Console proxy rejects six operator route forms with403 `operator_control_refused`; the real
   controller's completed transition remains unchanged. Component tests also exercise action-handler
   refusals, rather than relying only on hidden buttons.

The final small Admin R4 behavior is exercised with the actual AdminPanel and explicitly scripted
abort-ignoring transport: hung algo, hung trade reads, refused active reader while algo hangs, late
responses, newer successful polls, scope/account changes and destruction. This is component proof,
not a live hung-algo service experiment. The live browser proof uses actual SQL readers/NATS.

## Failure, correction and limits

The unchanged base refused `RECOVERY_RETAINED_ORDER_ROW_CONFLICT: freshlive-2`. A regression
creates orderbook from the actual generated ConfigMap DDL and compares all fields. The independent
wire event digest matches; updatedAt1005ms is retained as1000ms by DATETIME seconds. Hibernate-created
fractional-precision fixtures had hidden the mismatch. The correction compares expected timestamps
using SQL CAST to each actual column's declared precision. Full event digest and all other fields
remain strict. It neither drops timestamps nor rewrites retained rows. **No migration is needed.**
Tests cover precision0/3/6, a subsecond source change caught by the event digest, representable stored
timestamp changes, economics/provenance corruption, and unchanged whole-state/refused notifications.
Unexplained balances, missing retained positions and conflicting history still refuse.

One corrected attempt failed a harness subscription check because an independent NATS observer
used an account-pattern subscription. The observer now uses `>` and cannot be counted as a UI
account subscription. This failure and the initial system failure remain separate in the evidence.

Evidence: owned worktree `review-evidence/managed-acceptance-20260925/`, including initial failure,
ConfigMap/precision regression XML, corrected live attempts, final executable launcher, SQL dumps,
archive files, screenshots, served-asset hashes, executable/classpath identities, parity and manifest.
Final runtime58/58 checks and13 screenshots; generated unit102/102 and recovery SQL17/17; Angular127/127, node15/15; source/generated parity189/189. Independent raw NATS parsing verifies14 managed messages and8 expected subject/payload outcomes, with empty/foreign-scope controls. Command exits and manifest hash are in `review.md` and the coordinator delivery.

No cloud, retained installation, external risk engine, financial valuation, latency, production
readiness, automatic recovery or exactly-once notification delivery is claimed. Notifications remain
best-effort; readers and the authoritative archive establish state. Integration and peer worktrees
are untouched; this owned branch is delivered for review without push or broad propagation.
