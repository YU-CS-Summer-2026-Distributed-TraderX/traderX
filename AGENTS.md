# AGENTS.md

This repository follows a SpecKit-first, multi-state architecture. Agents should use this file as the primary operating contract.

## Core Model

- Learning guides live in `docs/learning/**/*.md` and must include normalized front-matter.
- State definitions and contracts live in root `specs/NNN-*` feature packs.
- Contracts and architecture docs are generated from state-local spec artifacts.
- Learning graph index lives at `docs/learning-paths/index.md`.

## Generation Concurrency Rule

- Run state generation sequentially; do not run `pipeline/generate-state.sh` for multiple states in parallel by default.
- Default generation writes to shared targets (`generated/code/target-generated` and `generated/code/components`), so parallel runs can race and corrupt outputs.
- Use separate `TRADERX_GENERATED_ROOT` values only when intentional isolated workspaces are required.

## Active State IDs

- `001-baseline-uncontainerized-parity`
- `002-edge-proxy-uncontainerized`
- `004-containerized-compose-runtime`

## Team YU State Lineage

- `YU02-lmax-kubernetes`
- `YU03-in-memory-risk-gateway`
- `YU04-durable-control-feeds`
- `YU05-post-trade-compliance`
- `YU06-eod-price-production`
- `YU07-historical-tick-store`
- `YU08-execution-algo-engine`
- `YU09-ops-hardening`
- `YU10-fix-ingress`
- `YU11-aeron-replication`
- `YU12-aeron-cluster`
- `YU13-limit-order-book`
- `YU14-listed-equity-options`
- `YU15-eod-risk-extract`
- `YU16-cdm-instruments`
- `YU17-otc-rates`
- `YU18-risk-integration`

## Learning Doc Front-Matter Contract

Every file under `docs/learning/**/*.md` must include:

```yaml
---
title: "<Human friendly title>"
---
```

Schema: `docs/.schema/frontmatter.json`  
Validation script: `tools/validate-frontmatter.sh`

## Required Contents Per State

Each state feature pack should include:

- `spec.md` with FR/NFR and scenarios
- `plan.md` and `tasks.md` for execution
- `system/**` with requirements, flows, architecture model, and generated architecture docs
- `README.md` with state intent and scope

## Prompt Pack Status

Legacy `prompts/**` scaffolding has been retired from the active repository surface.
Use the canonical SpecKit artifacts and docs under `docs/spec-kit/**` instead.

## Custom Overlay Requests (Agent Guidance)

When a user asks how to customize TraderX for a private environment, create an overlay repository, or publish internal generated states:

1. Start from `docs/spec-kit/customizing-traderx.md`.
2. Use `docs/spec-kit/corporate-environments-guide.md` for strategy and governance framing.
3. Use `docs/spec-kit/custom-overlay-architecture.md` for implementation contracts:
   - overlay repository layout
   - overlay state catalog fields
   - transform idempotency and ordering
   - `--dry-run` start-script behavior
   - `TRADERX_GENERATED_ROOT` output redirection
   - working-directory anchor rules
4. Use `docs/spec-kit/custom-environments-guide.md` for environment integration norms:
   - package management policy
   - runtime version/tool loading (`env-loader`)
   - pub/sub replacement decision points
   - smoke-test `health_hint` usage

Agent operating rules for overlay workflows:

- Keep upstream TraderX canonical; do not place environment-specific deltas in upstream state packs.
- Prefer additive docs/spec updates and reusable templates over one-off generated output edits.
- Prefer `examples/custom-overlay-template/` as the default starter.
- For docs-portal changes, run AFDocs checks against local preview and published URL; prioritize build/pipeline/plugin fixes before broad manual rewrites.
- Do not hand-edit generated artifacts as a persistent solution.
- Preserve generated-state branch invariant: one snapshot commit per branch (reset to base + force-push).

## Quality Gates

```bash
tools/validate-frontmatter.sh
bash pipeline/speckit/validate-root-spec-kit-gates.sh
bash pipeline/speckit/validate-speckit-readiness.sh
bash pipeline/verify-spec-coverage.sh
```

If docs dependencies are installed:

```bash
cd website
npm run build
```

## Non-Breaking Policy

- Preserve earlier levels while evolving Level 4/5.
- Favor additive changes and clear migration notes.
- Never commit secrets or sensitive data.

## Active Technologies
- Java 21 (Spring Boot services), TypeScript/Node.js (Nest + Socket.IO + Angular), C# (.NET 9), SQL (H2) + Spring Boot, Gradle, NestJS, Socket.IO, ASP.NET Core, Angular, H2 (001-baseline-uncontainerized-parity)
- H2 over TCP/PG/Web ports (001-baseline-uncontainerized-parity)

## Recent Changes
- 001-baseline-uncontainerized-parity: Added Java 21 (Spring Boot services), TypeScript/Node.js (Nest + Socket.IO + Angular), C# (.NET 9), SQL (H2) + Spring Boot, Gradle, NestJS, Socket.IO, ASP.NET Core, Angular, H2

## Local EOD bundle state

`YU18-risk-integration` inherits `YU17-otc-rates`. Its state pack is `specs/YU18-risk-integration/`. The added Python component builds and validates local EOD bundles and produces explicitly non-pricing mock results. It uses no cloud resources. Run `bash scripts/test-state-YU18-risk-integration.sh`; see the state quickstart for its local demo.

## Maintained integration and trading backlog

For risk integration, engine containerization, risk pipelines, order-type extensions and related
demo/recovery work, read [issues/risk-integration/README.md](issues/risk-integration/README.md).
At task claim, material discovery, handoff and completion, update the matching RI task and index
with date, status, owner, revision, blockers, next action and evidence. Reverify historical findings
on new revisions; do not mark queued work done from a plan alone. The queue tracks work and does
not itself authorize implementation, deployments, Git pushes or cross-worktree changes.

## Component-based state development

Follow [state component rules](docs/spec-kit/state-components.md). YU18-risk-integration is the
canonical YU18 pack; add order types, service packaging and pipeline feature specs under its
components/ directory. Keep shared quickstart, architecture, contracts and generation in the parent.
A feature does not require a new state branch; isolated task worktrees remain available for concurrency.
