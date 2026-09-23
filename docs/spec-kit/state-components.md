# Components within a state spec pack

Adopted by user direction on 2026-09-23. Applies to new component work; existing states do not need a bulk migration.

## Layout

```text
specs/YU18-risk-integration/
  README.md
  spec.md                 # state scope, invariants and component index
  plan.md                 # shared sequencing and integration decisions
  tasks.md                # shared milestones and links to component task lists
  quickstart.md
  data-model.md
  research.md
  contracts/              # authoritative shared exchange contracts
  requirements/           # shared requirement deltas
  system/                 # shared architecture model, topology and ADRs
  generation/             # hooks, runtime overrides and deployment inputs
  tests/                  # shared acceptance/proof material
  components/
    README.md
    eod-integration/{README,spec,plan,tasks}.md
    order-types/{README,spec,plan,tasks}.md
    risk-service/{README,spec,plan,tasks}.md
    risk-pipeline/{README,spec,plan,tasks}.md
```

## Rules

1. A state may contain multiple components/features with independent specs. Adding a substantial feature does not by itself require a new state ID or permanent branch. Create a new state only for an intentional separately selectable lineage/generation milestone agreed with the user.
2. Each new component directory contains README, spec, plan and tasks. Record status, owner, source paths, dependencies, interfaces and executable acceptance expectations. Planning scaffolds must say they are incomplete.
3. Keep shared quickstart, architecture, contracts and generation files in the state parent. A component links these rather than maintaining divergent copies. Component-specific prose may live beside its spec; generation inputs stay state-level.
4. Use stable component-scoped requirement IDs. Existing EOD FR-EB/NFR-EB/SC-EB identifiers are preserved. Trace tests to requirements; distinguish source, generated and live evidence.
5. Generation is explicit. A new spec directory does not automatically create, enable or deploy a service. State renderers compose runtime overrides and include component documents in generated spec-source.
6. The integration branch remains traderX-risk-integration. Temporary task branches/worktrees may isolate concurrent writers; they are not new state branches. One writer per checkout, coordinated shared-file ownership.
7. Determine the operative runtime layer before editing. New YU18 features may override inherited components deliberately; do not silently modify earlier state behavior to implement a YU18 feature.
8. A component may specify an external service boundary. Risk-container implementation remains in the engine repository and its packaging pack remains authoritative there. Link revisions; do not duplicate financial implementations or conflicting packaging specs.
9. Update the component tasks, shared indexes and maintained backlog at task boundaries. Proposals, implemented behavior and tested behavior must remain distinct.

## YU18 rename and compatibility

Canonical state ID: YU18-risk-integration, parent YU17-otc-rates. The former YU18-eod-risk-bundles ID is accepted as a compatibility alias by the general generator and legacy shell wrappers. The runtime directory eod-risk-bundles, wire schema IDs and golden fixture bytes are unchanged.

Historical correspondence and other worktrees retain their historical names. This migration does not rename Git branches/worktree directories or propagate to other branches. The active integration branch is already traderX-risk-integration.

## Checks

Run `python3 scripts/check-state-components.py specs/YU18-risk-integration`, generate the canonical state, then run its source and generated component suites. The existing CRLF fixture check guards byte-preserving checkout behavior.
