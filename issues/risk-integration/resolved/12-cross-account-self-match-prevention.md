# RI-12 — Cross-account self-match prevention

Updated: 2026-09-25. Status: done for local demo. Owner: Codex coordinator, traderX-ui-combined; integrated into traderX-risk-integration at 2de9dd8b. User authorized implementation after initial backlog entry.

## Original problem and baseline

TraderX currently compares account IDs for self-match prevention. Orders from different accounts owned by the same person can therefore match. The September 25 source review found the account equality checks in the operative YU18 MatchingEngine override. Existing same-account prevention must remain intact.

## Original proposed scope

- Define a stable self-match prevention group for accounts sharing beneficial ownership. A demo username is not a verified ownership identity; define the demo mapping and production ownership contract separately.
- Enforce the group in the deterministic matching engine across all order entry paths, not only in the Desk UI.
- Specify group assignment, unassigned accounts, authorization to change membership, and the treatment of resting orders when membership changes.
- Review whether to extend the existing cancel-oldest policy across the group. Record the chosen cancellation policy and risk-release behavior in the order-types component spec before implementation.
- Persist and replicate group configuration so snapshot restore, replay and failover preserve the decision.
- Show a clear prevention reason in acknowledgements, order history and audit records. Do not label every prevented match as an illegal wash trade.

## Acceptance

- [x] Same-account protection still passes.
- [x] Two distinct accounts in one group cannot execute against each other, through either UI or direct API submission.
- [x] Different groups can trade normally; one trader managing unrelated client accounts is not automatically one beneficial owner.
- [x] Cover all seven order types, triggered/repriced/replenished orders, partial fills, cancel/replace and FOK preflight, with consistent risk reservation release.
- [x] Group assignment changes and missing/invalid groups follow the specified policy without a bypass.
- [x] Snapshot-plus-replay and failover preserve groups and matching outcomes; audit records identify the prevention action.
- [x] Controlled local proof verifies no prohibited intra-group trade rows and a successful inter-group control trade.

## Next action and dependencies

The policy and local implementation are delivered. Maintain engine group regressions; production identity verification belongs to RI-11 and retained deployment qualification to RI-06. No new permanent state or cloud deployment is required by this backlog entry.

Venue reference: [CME self-match prevention FAQ](https://www.cmegroup.com/solutions/market-access/globex/trade-on-globex/faq-self-match.html). CME supports prevention across accounts with common ownership; technical prevention and the legal assessment of wash trading are distinct.

## Delivered — 2026-09-25

RI-12 implemented and integrated at2de9dd8b (040394e0 implementation + committed-ack/recovery delivery). Local kind only; image traderx/cluster-node:ri12-groups-20260925, all three member imageIDs identical. Initial upgrade preserved PVCs and SQL26trade/39order rows. Grouped resting orders canceled with SELF_TRADE_PREVENTED; different-group two-leg fill succeeded; invalid/unauthenticated group changes refused. Real Desk API-created accounts65003/65004 shared protection automatically. All5 existing workspace accounts migrated without altering admission flags. Snapshot counters advanced2->3 on allmembers. Leader2 restarted retainingPVC; member1 promoted and member2 recovered. Subsequent cross-account match prevented without group reassignment. Final allmembers32trade legs and identical book hash5188467653886072962; full16check readiness passes.
Final107 focused generated tests, five allocation gates and7Node tests pass. Full592test run had two failures: historical-format fixture corrected and passing; snapshot50ms timing remains failing. Prior cf08a57d baseline reproduced127.211ms max, current initial88.521ms and isolated110.496ms. These are host test results, not controlled latency or financial validation; no full-suite-green claim. No threshold changed. Evidence includes raw failed and passing runs.
Runtime risk state owner now YU18 BlpRiskState override (previously YU17), snapshots format13 with legacy9-12 reader. New group command19/ack107 requires all-member upgrade; no downgrade supported after13 snapshots/new commands. Local demo workspace identity is not verified beneficial ownership/authentication. Unrelated seeded accounts remain separate unless operator configured. No pushes, GKE, Alex checkout writes or broad propagation. UI4320/4321 and full local rig left running; user may need to sign in again. All17 prior dirty paths byte-preserved before narrow backlog updates. Claims released.

Production identity verification remains part of RI-11; local usernames do not establish beneficial ownership. The proposed-scope text above records the original task, and the operative contract is now the RI-12 section of the YU18 order-types component spec.

October 7 archive decision: this delivered local scope is complete; its documented production/performance/financial limitations are preserved and do not become new unfinished implementation here.
