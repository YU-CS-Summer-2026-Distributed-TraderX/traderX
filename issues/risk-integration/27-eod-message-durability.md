# RI-27 — NATS rebind recovers subscriptions but loses EOD messages

Updated: 2026-10-07. Status: blocked (durable EOD storage/recovery semantics decision needed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

EOD consumer self-heal is implemented; broker emptyDir still loses messages. Managed archive repair applies to trade projections and is not EOD durable-message replay or delivery to every sink.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Decide durable broker storage versus regenerable EOD event recovery, with event identity and replay safety.
- [ ] Document which downstream consumers are repaired by archive recovery and which remain best-effort.
- [ ] Preserve synthetic/real data provenance and immutable artifact custody across recovery.

## Acceptance

- [ ] A nonempty EOD chain survives or explicitly recovers broker-state loss with attributable results.
- [ ] Rebinding to an empty stream cannot count as message recovery.

## Original reports

- [a-nats-restart-silently-kills-every-eod-durable.md](../open/a-nats-restart-silently-kills-every-eod-durable.md)
- [HANDOFF-issue-yu12-bridge-at-least-once.md](../open/HANDOFF-issue-yu12-bridge-at-least-once.md)

Next: Agree durable EOD storage/regeneration and replay semantics.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

## October 7 integration outcome

Subscription rebind is not event recovery. Choose durable broker storage versus regenerable EOD recovery, event identity and replay semantics before implementation; no silent recovery guarantee is added.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
