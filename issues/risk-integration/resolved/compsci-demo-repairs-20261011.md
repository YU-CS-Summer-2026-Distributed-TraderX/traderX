# COMPSCI-DEMO-REPAIRS — Hosted console and Desk corrections

Updated: 2026-10-11. Status: done for the approved demo repair scope. Coordinator/author: Codex chat `01a12b5c-4c93-7e62-b90a-3e56223dee78`, host local.

## Authorization, ownership and integration

The human requested demo account/catalog initialization, authenticated account controls and credential rotation, network-restricted external access and HTTPS, listed-option/EOD/FIX/reconciliation fixes, and the hosted Desk. The human separately approved the bounded live proofs described below and then explicitly said: “Yes, integrate the1132510f repairs into canonical, preserving my existing edits.”

Owned candidate: `traderX-compsci-gke`, branch `codex/compsci-gke`, source `1132510f974bea4c119a3e6165649095d088651c`. Eight commits after accepted `fa074662`,35 scoped paths. The canonical `traderX-risk-integration` branch merged the exact candidate at `85afc9998bd5e5b3f233fff6d2794104c01e61d1`.34 files changed because RI30's identical planning record was already present. All35 approved paths matched the candidate byte for byte. All20 original dirty/private/untracked files and empty staging were preserved; no stash, reset, broad staging or push.

Scope covers the console and Desk, explicit CompSci configuration/tooling and associated documentation. Generated engine dependency `6156ad22` remains unchanged. No deterministic-core or financial changes, historical-data conversion, retained-data deletion, broad propagation or additional node provisioning.

## Changes and live acceptance

The full demo catalog is registered:557 instruments,544 enabled,13 expired/matured deliberately disabled,55 sequenced quote references including AMZN. All14 current directory accounts are enabled on all three members, including Test Account20/22214; this includes11 original accounts, two benchmark fixtures and a later user-created account.

Both `https://demo.yaakovseif.dev` and `https://desk.yaakovseif.dev` have trusted managed TLS and network-only Cloud Armor access. Account management requires sign-in. Desk requires the operator password and uses Secure/HttpOnly/SameSiteStrict sessions plus persistent membership storage. The empty yaakov workspace was created and its login/logout verified with explicit human approval. Passwords and signing material remain outside the repository and proof records.

Hosted Origin is validated before credentials are forwarded, then omitted on the internal hop, resolving the Spring CORS refusals. One explicitly approved orphan sweep and one Oct11 close succeeded; v2 published. Two approved five-contract unexpired AAPL option-story orders produced attributable SQL trade rows. The final preset selects `AAPL261218C00240000` and preserves the same admitted engine reference for both tickets; field-only verification submitted no additional order. FIX market-price subscription and fill were verified live.

Actual HTTPS EOD Refresh shows all four stages green: publishedv2,67 prices,30 PnL rows, attributable extract READY and six retained date objects. Async CLI reads preserve HTTP responsiveness. The shared CLI queue defaults to two active processes and64 queued reads; overflow refuses explicitly. Actual21-request cold workload at limits1/2/3 measured146/219/277MiB peak and51/29/25s total, with no restarts. Limit2 was restored. The earlier unbounded async OOM remains in the evidence.

Live image acceptance:25 Ready pods/26 container instances; independently measured console23 files and Desk33 files match their amd64 pins.62 resources passed server dry-run. Exact original single gateway configuration was restored after the separate C4D campaign, and the temporary load pod was removed. User-approved offsets flattened only benchmark positions65000/65001 at100; direct SQL shows zero positions and47,231 retained C4DBENCH trade rows per account. No uncertain high-rate order was retried.

## Canonical integration verification

An isolated copy included current canonical source and all existing user source edits. Full30-state ancestry regeneration and independent derivation passed. All11 generated service build contexts exactly match the reviewed candidate. Frontend input files match; preexisting Docker-excluded caches and empty build directories were preserved rather than deleted. Canonical generated files were untouched.

Global frontmatter, root Spec Kit, readiness and spec-coverage checks passed. Focused actual HTTP/CLI/CORS/SQL/extract tests:13 passed; Desk session/hosted-password tests:7 passed. Previously verified focused Angular/Desk compilation and live payload proof remain attributable to the identical repaired source. No new rollout or cluster mutation was performed during this source integration.

Evidence: parent coordination `review-evidence/compsci-gke-20261011/integration-1132510f/` (before, merge, source snapshot, derivation, context/frontend comparisons, six check logs and preservation receipt); related live review `demo-bounded-20261011/review.md`. [LR05](../live-rig/lr-05-deployment-artifacts.md) retains broader artifact/retained-state obligations; this task closes only its measured CompSci demo milestone.

## Remaining separate work

[RI13](../13-gke-performance-rebaseline.md)/[LR07](../live-rig/lr-07-gke-performance.md): the initial C4D REST/gateway baseline is complete, but exclusive CPU placement, workload/recovery/soak and production qualification remain open. The3000/s trial has74 unknown socket outcomes without per-item IDs and is not a fully successful ceiling. [RI30](../30-lossless-taq-reingestion.md) full-column historical ingestion remains deferred, with no conversion/cleanup. [RI31](../31-test-suite-guard-and-fixture-profiles.md) records unresolved wrapper guards, incompatible matcher fixture profiles and an HA timeout; no blanket full-suite success claim. Claude, further Alex upgrades and skill authoring remain held; no monitoring automation or public data publication.
