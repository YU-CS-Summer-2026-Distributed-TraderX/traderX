# Console runtime packaging

Updated: 2026-10-08. Status: bounded packaging correction reviewed and integrated with human approval. Owner: Codex console packaging lane.

The console image omitted `desk-orders.mjs`, so its default command exited before serving requests. The root `web-front-end-console/Dockerfile` now includes that module in its explicit runtime copy list. Server, Desk, auth and engine behavior are unchanged.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), and the [bounded task](../../../../issues/risk-integration/resolved/lr05-console-packaging.md). The root Dockerfile remains the implementation owner; this pack adds no generation overrides.

Platform follow-on: the old Docker Hub runtime base only supplied amd64. The candidate now uses the official Cloud CLI stable multi-platform index pinned by digest, with native Debian Node and official Google apt kubectl packages. The installed-image checker reads ELF headers before startup and verifies the requested platform. Native arm64 passed 19 checks; the retained genuine amd64 image passed the same checks as amd64, while the mislabeled arm64 image was refused. The revised Dockerfile has only been built for arm64 in this follow-on. The native connected startup/read-surface milestone passed coordinator review. Broader release, authenticated Desk, real cloud bridge and GKE qualification remain open.
