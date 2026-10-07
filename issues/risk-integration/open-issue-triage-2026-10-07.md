# Open-issue reconciliation, 2026-10-07

Reviewed branch `traderX-risk-integration` at `a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e`. This maps every legacy open document to the maintained queue. No legacy file was moved or deleted. Source findings and local tests do not establish the current state of a deployed rig or stored cloud data.

[Detailed source review and evidence](/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/open-issues-20261007/report.md). Counts:20 current source gaps,9 partially addressed,10 stale/intentional/admin/incident,3 deferred features,3 live/data revalidation. These are file classifications, not distinct bug counts.

| Legacy file | Current assessment | Maintained work |
|---|---|---|
| [HANDOFF-agent-flow-generator.md](../open/HANDOFF-agent-flow-generator.md) | Stale / implemented | Implemented; retain history |
| [HANDOFF-collar-price-sourcing.md](../open/HANDOFF-collar-price-sourcing.md) | Partial / re-scope | RI-25 |
| [HANDOFF-continuous-portfolio-risk.md](../open/HANDOFF-continuous-portfolio-risk.md) | Feature / deferred | RI-15 future intraday feature |
| [HANDOFF-egress-consensus-sequence.md](../open/HANDOFF-egress-consensus-sequence.md) | Partial / re-scope | Retain history |
| [HANDOFF-fx-instrument-class.md](../open/HANDOFF-fx-instrument-class.md) | Feature / deferred | Deferred FX product; separate from RI-05 rate inputs |
| [HANDOFF-issue-tca-tickstore-retrofit.md](../open/HANDOFF-issue-tca-tickstore-retrofit.md) | Feature / deferred | RI-09 conventions / future TCA feature |
| [HANDOFF-issue-yu12-bridge-at-least-once.md](../open/HANDOFF-issue-yu12-bridge-at-least-once.md) | Partial / re-scope | RI-27 |
| [HANDOFF-issue-yu12-failover-measurement.md](../open/HANDOFF-issue-yu12-failover-measurement.md) | Live / revalidate | RI-23 |
| [HANDOFF-issue-yu12-gateway-sessionaffinity-split.md](../open/HANDOFF-issue-yu12-gateway-sessionaffinity-split.md) | Stale / implemented | Implemented; RI-13 scale-out proof |
| [HANDOFF-issue-yu12-services-ui-rewire.md](../open/HANDOFF-issue-yu12-services-ui-rewire.md) | Stale / implemented | Implemented; RI-11 admission workflow |
| [HANDOFF-issue-yu12-sustained-throughput.md](../open/HANDOFF-issue-yu12-sustained-throughput.md) | Live / revalidate | RI-13 |
| [YU15-s5-gke-fifo-correlation-offset.md](../open/YU15-s5-gke-fifo-correlation-offset.md) | Stale / implemented | Implemented; RI-23 availability measurement |
| [a-book-costs-4mb-so-member-heap-caps-venue-breadth.md](../open/a-book-costs-4mb-so-member-heap-caps-venue-breadth.md) | Current / source-confirmed | RI-24 |
| [a-nats-restart-silently-kills-every-eod-durable.md](../open/a-nats-restart-silently-kills-every-eod-durable.md) | Partial / re-scope | RI-27 |
| [a-per-member-liveness-probe-fires-on-a-global-condition.md](../open/a-per-member-liveness-probe-fires-on-a-global-condition.md) | Partial / re-scope | RI-23 |
| [a-proof-killed-mid-run-leaves-its-prep-stranded-on-the-rig.md](../open/a-proof-killed-mid-run-leaves-its-prep-stranded-on-the-rig.md) | Current / source-confirmed | RI-17 |
| [a-refused-otc-booking-leaves-no-audit-trace.md](../open/a-refused-otc-booking-leaves-no-audit-trace.md) | Current / source-confirmed | RI-22 |
| [a-reused-clordid-merges-two-orders-into-one-trace.md](../open/a-reused-clordid-merges-two-orders-into-one-trace.md) | Stale / intentional | Intentional trace behavior |
| [a-successful-gcs-restore-leaves-the-read-model-holding-orders-the-engine-dropped.md](../open/a-successful-gcs-restore-leaves-the-read-model-holding-orders-the-engine-dropped.md) | Current / source-confirmed | RI-21 |
| [a-suite-that-does-not-rebuild-never-checks-the-members-image-is-resolvable.md](../open/a-suite-that-does-not-rebuild-never-checks-the-members-image-is-resolvable.md) | Current / source-confirmed | RI-20 |
| [ack-unmatched-counts-continuation-fills.md](../open/ack-unmatched-counts-continuation-fills.md) | Current / source-confirmed | RI-28 |
| [an-export-data-overwrite-leaves-the-previous-runs-extra-shards.md](../open/an-export-data-overwrite-leaves-the-previous-runs-extra-shards.md) | Stale / implemented | Implemented; retain history |
| [console-extract-source-is-ephemeral-and-unroutable-on-kind.md](../open/console-extract-source-is-ephemeral-and-unroutable-on-kind.md) | Stale / intentional | Accepted storage/read-path decision |
| [control-snapshot-carries-no-accounts.md](../open/control-snapshot-carries-no-accounts.md) | Current / source-confirmed | RI-22 |
| [five-gke-proofs-read-a-global-counter-that-replayed-flow-now-moves.md](../open/five-gke-proofs-read-a-global-counter-that-replayed-flow-now-moves.md) | Stale / implemented | Implemented; RI-07 live acceptance |
| [gateway-http-executor-never-drains.md](../open/gateway-http-executor-never-drains.md) | Current / source-confirmed | RI-23 |
| [gke-deploy-path-has-no-image-guard.md](../open/gke-deploy-path-has-no-image-guard.md) | Current / source-confirmed | RI-20 |
| [member-readiness-tolerates-5000-entries-of-lag.md](../open/member-readiness-tolerates-5000-entries-of-lag.md) | Current / source-confirmed | RI-23 |
| [nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together.md](../open/nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together.md) | Partial / re-scope | RI-21 |
| [nothing-proves-recovery-across-a-real-format-and-capacity-gap.md](../open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md) | Current / source-confirmed | RI-26 |
| [order-matcher-issues-ddl-against-the-shared-database.md](../open/order-matcher-issues-ddl-against-the-shared-database.md) | Partial / re-scope | RI-21 |
| [schema-migration-fires-only-on-pod-creation.md](../open/schema-migration-fires-only-on-pod-creation.md) | Partial / re-scope | RI-21 |
| [six-lifted-issues-carry-an-unverified-status.md](../open/six-lifted-issues-carry-an-unverified-status.md) | Stale / administrative | Reconciled by this mapping |
| [stamp-replay-epoch-cannot-work-on-a-tier-without-member-pvcs.md](../open/stamp-replay-epoch-cannot-work-on-a-tier-without-member-pvcs.md) | Current / source-confirmed | RI-21 |
| [the-cluster-tier-exports-no-risk-gauge.md](../open/the-cluster-tier-exports-no-risk-gauge.md) | Current / source-confirmed | RI-22 |
| [the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md](../open/the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md) | Current / source-confirmed | RI-23 |
| [the-manifests-pin-a-build-the-rig-no-longer-runs.md](../open/the-manifests-pin-a-build-the-rig-no-longer-runs.md) | Current / source-confirmed | RI-20 |
| [the-publisher-signals-absent-and-corrupt-tape-identically.md](../open/the-publisher-signals-absent-and-corrupt-tape-identically.md) | Current / source-confirmed | RI-25 |
| [the-replayed-universe-stops-at-the-publishers-price-tickers.md](../open/the-replayed-universe-stops-at-the-publishers-price-tickers.md) | Current / source-confirmed | RI-25 |
| [the-rig-was-left-mixed-version-on-the-stp-revert-build.md](../open/the-rig-was-left-mixed-version-on-the-stp-revert-build.md) | Stale / incident | RI-17 |
| [the-stp-freshness-guard-is-unsatisfiable-after-a-no-op-rebuild.md](../open/the-stp-freshness-guard-is-unsatisfiable-after-a-no-op-rebuild.md) | Current / source-confirmed | RI-18 |
| [tick-store-drops-taq-sym-suffix-and-merges-share-classes.md](../open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md) | Current / source-confirmed | RI-25 |
| [trade-processor-limps-through-heap-oom-and-no-probe-fires.md](../open/trade-processor-limps-through-heap-oom-and-no-probe-fires.md) | Partial / re-scope | RI-24 |
| [truncated-uploads-make-one-day-of-the-tick-store-unreadable.md](../open/truncated-uploads-make-one-day-of-the-tick-store-unreadable.md) | Live / revalidate | RI-25 |
| [yu05-recon-forward-sweep-cannot-pass-on-a-rolled-rig.md](../open/yu05-recon-forward-sweep-cannot-pass-on-a-rolled-rig.md) | Current / source-confirmed | RI-19 |

Validation in the source review:15 provisioning/transition unit tests, consensus predicate selftest and11 GKE gate controls passed. Mocked missing-PVC epoch stamping returned success without a stamp; temporary missing/corrupt tape fixtures confirmed identical status shapes. Broad Java, SQL, Docker, HA and cloud suites were not rerun. No TAQ conversion occurred.

## After the October 7 integration

The inventory above is the original `a0d6da0b` source snapshot, not a current-open verdict. The 13 reviewed deliveries are now integrated in `b0b4c332`. RI17/18/19/22/28 have completed their scoped local source work; RI20/21/23/24/25 have integrated milestones with broader work open. RI26/27 require decisions. The [current queue](README.md) and [resolved index](resolved/README.md) own current status. Original report files remain historical references; no live deployment or GitHub closure is inferred.
