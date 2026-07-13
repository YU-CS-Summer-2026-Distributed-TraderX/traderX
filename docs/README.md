# FINOS | TraderX Sample Trading App

![DEV Only Warning](https://badgen.net/badge/warning/not-for-production/red) ![Local Dev Machine Supported](http://badgen.net/badge/local-dev/supported/green)

TraderX is a simple, distributed reference application for exploring trading workflows in financial services. It is intentionally approachable and non-production, and is meant for education, experimentation, and demos.

## Start here
- [Overview](overview.md) for architecture and the C4-inspired diagram.
- [Flows](/specs/baseline-uncontainerized-parity/system/end-to-end-flows) for sequence diagrams of core user journeys.
- [State Docs](spec-kit/state-docs.md) for spec packs, architecture, generated code, and branch comparisons.
- The [Running TraderX](running.md) guide for run options (manual, Docker, Kubernetes/Tilt).

## What is in this docs folder
- `overview.md` contains the architectural diagram and links to system context.
- State-local flow diagrams live under `specs/*/system/`.
- `spec-kit/state-docs.md` maps every state to its specs, architecture, and generated code branch.
- `home.mdx`, `roadmap.mdx`, `running.md`, and `project-history.md` are Docusaurus site pages.
- `c4/` contains the original C4 DSL and rendered image.

You can view more details here:
* [Generated State Branches](spec-kit/generated-state-branches.md)
* [Learning Guides](learning/index.md)
* [Overview](overview.md)
* [State 001 Architecture](/specs/baseline-uncontainerized-parity/system/architecture)
* [State 001 Flows](/specs/baseline-uncontainerized-parity/system/end-to-end-flows)
