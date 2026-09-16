# Professor demo — 16 September 2026, 18:00 America/New_York

Open **https://yaakovseif.dev/risk**. The existing seven-node GKE rig is intentionally left running
through and after the demo. No shutdown is scheduled. Git changes are local; nothing was pushed.

## A four-minute walkthrough

1. **0:00–0:45 — Calculation status.** Open Risk. Show the separate Treasury bill and note
   statuses: Input prepared → Pricing completed → Independently checked. Keep the presentation
   focused on outcomes; internal provenance remains in private operational evidence.
2. **0:45–2:00 — Real library, synthetic assumptions.** Show the bill and note long/short
   comparisons. Alex's real pinned library ran **locally on the operator's Mac**, for synthetic
   2025-06-02 fixtures under the explicit assumed `flat-3pct-v1` curve. GKE serves static evidence
   and revalidates stored job custody; it is not running Alex's worker. Bill long NPV is about
   $98,507.15; note dirty NPV about $103,308.33, clean about $101,451.23 and exported accrued
   $1,857.10. Short values have opposite signs.
3. **2:00–2:45 — Independent checks.** Show the Decimal reference, differences and tolerance.
   The note's +1bp parallel bump changes long NPV by about **-$15.28** (short +$15.28). This is
   signed USD change, not derivative per unit and not a per-pillar DV01. Explain clean/dirty/accrual
   reconciliation and the six-decimal exported-fraction bound.
4. **2:45–3:30 — Live TraderX operational path.** Open End of day, date 2026-09-16. The clearly
   labeled operational smoke test used synthetic/historical replay prices: one new IBM cross,
   two trade sides, two P&L rows, 67 OK marks, no overrides, receipt sequence 1086 with adjacent
   witness 1087, two positions and zero OTC contracts. GCS position/contracts/cut bytes match
   the receipt hashes. This operational extract is **separate from** the dated bill/note examples;
   do not imply the IBM portfolio was priced by Alex.
5. **3:30–4:00 — Coverage.** Read the coverage panel. Bill sensitivity is unsupported, not zero.
   Note NPV and parallel bump are supported; gamma/theta, equity/SOFR and portfolio VaR/ES are not.
   Terms-v2, versioned result schema and durable EOD HTTP service remain pending in the reviewed
   capability snapshot. `usableForRisk=false` throughout. Do not equate the shell's “rig connected”
   pill with an Alex connection or financial readiness.

## What was actually reset and measured

The user explicitly approved fresh application trading state. Before reset, the retained SQL
projection held six trades (maximum ID 6), four positions and eight order rows against an empty
matcher. Producers, consumers and gateways were paused. Six tables were backed up, then cleared
transactionally: `trades`, `positions`, `orderbook`, `eod_position_pnl`, `eod_price_snapshot` and
`eod_price_session`. Both emptyDir-backed NATS instances were replaced to discard pending events.

Main matcher emptyDir state was restarted together. Sandbox uses a new directory
`/data/demo-20260916T1829` on its existing PVC; its previous directory remains preserved.
Operator-assigned `CLUSTER_EPOCH` labels are `2026091601` (main) and `2026091602` (sandbox).
These are **operator labels, not platform-issued run identity**. The price replay startup anchor
was derived from the new member-0 Pod's observed creation timestamp, with Pod UID preserved in
private evidence. Historical TAQ replay stays historical; arrival freshness does not make it a
current observation. Print-order replay is disabled, and sandbox replay remains at zero replicas.

Before any test trade, all three main members and the sandbox showed zero trades, open orders,
order/position hashes and queue depth; all six SQL tables were empty. Bootstrap then admitted
11 directory accounts and 532 catalog instruments. The only new main trading activity was the
explicit IBM smoke cross (one unit at 200.00, accounts 42422 and 22214), producing two trade rows.
Accounts/security configuration, credentials, PVCs, prior sandbox files and external buckets were
preserved. The risk-extract bucket was never deleted.

## Deployment and repeatable evidence

Target: project `traderx-505400`, cluster `traderx-bench`, zone `us-east1-b`, namespace `traderx`.
Seven Ready nodes: support 1×n2-standard-8, matcher 3×n2-standard-4, gateway 3×e2-standard-4:
32 allocated vCPUs. Hourly billing/remaining credits were not independently measured. Keep running.

Service source: accepted integration `e045a03e01e4161b244f3e0144a8d54316dfc858`, fully generated YU18.
Registry prefix: `us-east1-docker.pkg.dev/traderx-505400/traderx`. Tag: `yu18-demo-e045a03e-20260916`.

| Workload | Image | Verified image digest |
|---|---|---|
| trade-processor | trade-processor | `sha256:d699b7df85496b1e8cf48b10dd978e88fb56c0d78fdc86c8e1068d0a0e3dcde6` |
| price-publisher | price-publisher | `sha256:78e4c7cbd4815733f595cb40543c7c833bf12f14dff5daa30998d877d6a52ed3` |
| risk-extract | cluster-node | `sha256:a9e7eb960c1b851ca7bd1931da78612620d7818dfd775fd214f63cacc21472ee` |
| web-front-end-console | web-front-end-console | `sha256:5c4dd9eed8ad4e4a17ec998ef37dfe2dac610c179229ae087e2aa434398b9de2` |

The matcher/gateway core images were retained, avoiding an unrelated deterministic-core upgrade.
The new cluster-node image is used only for the read-side risk extractor and its completion hook.
Its receipt directory is `/data/risk-extracts/ready` on the existing emptyDir: receipts survive a
process restart but not Pod replacement; this proof's original receipt is preserved privately,
and the actual extract artifacts are in GCS.

Private evidence lives outside Git at
`/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/gke-demo-20260916/`.
It includes exact pre-change staging/user-file hashes, workload rollback snapshots, SQL backups,
original Alex outputs/receipts/state, live receipt/GCS hash proof and test/build logs. Do not upload
this evidence directory publicly. The tracked guide and sanitized Risk report are sufficient for
presentation.

### Regenerate the local synthetic report

Use an external Python environment with Alex's declared numerical dependencies. Alex's checkout is
read-only; the existing demo archives reviewed `e7246e1` if HEAD has moved. No re-pin is performed.

```sh
python scripts/demo-state-YU18-alex-pricing.py --engine /path/to/JAX_Risk_Engine
# Take the emitted evidence directory, then choose a NEW private output outside a Git checkout:
python3 scripts/prepare-state-YU18-risk-demo.py --evidence /private/path/real-run --output /private/path/new-package
```

The preparer revalidates original producer bytes and custody, uses the independent Decimal reference,
creates two distinct jobs with unchanged identity semantics, checkpoints SQLite and verifies its
integrity. It emits `risk-demo.json` and `state/`. It does not run a cloud worker or create pricing
from a fresh cloud extract. The raw-result hash differs intentionally from the coordinator's
aggregate receipt+result custody hash.

### Build and package the console

```sh
# The repo ignores dependency lockfiles; preserve the generated lock in private build evidence.
npm --prefix web-front-end-console install --package-lock-only --ignore-scripts --no-audit --no-fund
docker build --platform linux/amd64 -t traderx/console:UNIQUE -f web-front-end-console/Dockerfile web-front-end-console
CONSOLE_IMAGE=traderx/console:UNIQUE RISK_RUNTIME_IMAGE=traderx/console-runtime:UNIQUE \
  bash scripts/package-state-YU18-risk-runtime.sh
```

The runtime adds matching Python modules, with no private state baked into the image. A private
Secret carries a consistent synthetic state archive into an init container; the serving container
mounts the unpacked state read-only with directory mode 0700. The separate sanitized report is
mounted from a ConfigMap. `EOD_STATUS_SCRIPT`, `EOD_COORDINATOR_STATE`, `EOD_PYTHON` and
`RISK_DEMO_ARTIFACT` are explicit. Missing/corrupt configuration returns unavailable, never an empty
success. The image build must include `risk-demo.mjs` next to `server.mjs`.

### Read-only verification and rollback

```sh
K=(kubectl --context gke_traderx-505400_us-east1-b_traderx-bench -n traderx)
"${K[@]}" get nodes
"${K[@]}" get pods -o custom-columns='POD:.metadata.name,READY:.status.containerStatuses[*].ready,IMAGE:.spec.containers[*].image,DIGEST:.status.containerStatuses[*].imageID'
curl --max-time 20 -fsS https://yaakovseif.dev/risk/demo
curl --max-time 20 -fsS https://yaakovseif.dev/eod/jobs
curl --max-time 20 -fsS https://yaakovseif.dev/order-matcher/health
```

Use scoped strategic patches under `specs/YU18-eod-risk-bundles/generation/kubernetes/gke-demo/`.
Do not apply historical full overlays: they would revert newer live configuration/images. Rollback
uses the saved preflight workload snapshot and targeted `kubectl set image`; do not restore whole
objects blindly or restore stale trading rows into the new epoch. Previous service tags were
`trade-processor:yu17-gke5`, `price-publisher:yu17-6374c110`, `cluster-node:yu17-session-scope` for
risk-extract, and `web-front-end-console:yu17-gke49`. Console rollback also removes its demo init
container/volume/env patch. Core rollback/reset is not part of a console rollback.

Measured tests: source133/generated133 EOD tests, publisher107, focused arrival/staleness tests,
exporter/receipt tests, live Deployment+Pod counterparty guard and byte parity, real pinned Alex
bill/note acceptance and the live trade→P&L→extract/receipt/GCS hash proof. No hosted CI result is
claimed. Keep the separate local pricing and live operational evidence labels visible.

## Final public console verification

Accepted UI commits: `70347c16`, `1f87ca80`, `1396161d`, `a1f5ceaf` (including the bounded
1 MiB + 1-byte filesystem read). Console image tag: `yu18-risk-private-ui-20260916`; digest above.
The serving Pod has Node 20.19.2 and Python 3.13.5, private state mode 0700 owned by the runtime
user, and read-only state/report mounts. The exact report bytes were checked through public
`/risk/demo`; `/eod/jobs` returned both jobs VERIFIED. Public POST `/risk/demo` returned 405,
and GET had `Cache-Control: no-store`. The refreshed route tests pass 9/9. Claude's unchanged UI
suite passed 76/76; the final production image built successfully (bundle-size warning retained).

Safari was used against the actual public `/risk` page: both distinct verified jobs, local producer
execution label, long/short comparisons, note +/-1bp amounts, accrual bounds and unsupported/pending
coverage were observed. The first desktop screenshot rendered correctly; later native screenshots
returned unusable thumbnails, so a narrow/mobile visual check is not claimed. The guide's engine
health claim comes from actual endpoint and member/SQL checks, not the shell's cosmetic pill.

To seed the two deployment inputs from a newly prepared package:

```sh
# Run after reviewing that the package contains ONLY the two admitted synthetic fixtures.
tar -czf /private/path/state.tar.gz -C /private/path/new-package/state .
kubectl --context gke_traderx-505400_us-east1-b_traderx-bench -n traderx create secret generic risk-demo-state-NEW --from-file=state.tar.gz=/private/path/state.tar.gz
kubectl --context gke_traderx-505400_us-east1-b_traderx-bench -n traderx create configmap risk-demo-report-NEW --from-file=risk-demo.json=/private/path/new-package/risk-demo.json
# Update the two names and immutable console digest in a reviewed copy of console.patch.json.
kubectl --context gke_traderx-505400_us-east1-b_traderx-bench -n traderx patch deployment/web-front-end-console --type=strategic --patch-file specs/YU18-eod-risk-bundles/generation/kubernetes/gke-demo/console.patch.json
```

The init container copies the archive, sets root ownership and private directory mode; the main
container reads it without mutating or recovering jobs. A rollout reconstructs the snapshot from
the Secret. The runtime image contains no coordinator state or raw portfolio artifacts.

### Presentation correction

The deployed presentation removes contributor names, commits, hashes, job/bundle identifiers,
operational timestamps, internal codes and expandable custody details. It retains separate bill/note
statuses, numeric comparisons, date 2025-06-02, the assumed flat 3% curve and the local/synthetic/
non-production labels. The nine focused rendered UI tests passed, including exclusion of internal
provenance and raw errors. Safari's actual public accessibility tree confirmed the corrected content;
native screenshot capture still returned an unusable thumbnail. No new visual-layout claim is made.
This changes the presentation only; the read-only API contracts retain their existing fields.
