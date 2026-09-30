# Managed Desk lifecycle proof

This local synthetic proof runs the shipped Desk, console server, generated reference/account/
position/trade services, MariaDB, NATS, and three successive single-member Aeron runs. The edge
fixture only routes real HTTP/WebSocket services. It uses the accepted RI-06 provisioning,
transition controller and archive recovery; it does not implement another recovery engine.

Prerequisites: Docker, Java 21, Node/npm, Python 3, Chrome. Reserve **28800–28999**. Do not run
another copy concurrently. No Kubernetes client is used; the launcher writes an isolated empty
kubeconfig. The retained `traderx-yu12-cluster` must remain stopped and untouched.

From the owned worktree:

```sh
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
npm --prefix generated/code/target-generated/reference-data ci
npm --prefix generated/code/target-generated/reference-data run build
npm --prefix web-front-end-console install
npm --prefix web-front-end-console-combined-prototype install
npm --prefix web-front-end-console-combined-prototype run build
export JAVA_HOME=/absolute/path/to/java21
export OUT=/absolute/path/to/new-proof-output
export DESK_PROOF_NAME=traderx-desk-unique-local-name
bash scripts/desk-managed-recovery/run.sh
```

The output directory and container name must be new. SQL uses the generated additive desired-state
schema, plus accepted RI-06 migrations, on a new empty database. It never applies the destructive
demo initializer to retained data. The two named containers are stopped and retained on exit;
the launcher records their IDs. Child JVMs and Chrome use owned PIDs and private storage. Logs,
SQL dump, descriptors, archive/storage, screenshots and executable hashes remain under `OUT`.

The proof asserts real managed activation, submit and partial fill, account and historical
refusals, total-quantity replacement, actual receiving-gateway descriptor refusal, browser replace
and cancel, core/gateway restart retaining storage, missed events during consumer outage, accepted
archive catch-up, unchanged catch-up retry economics and a managed-to-managed transition. The
open Desk reconciles rows and shows populated history without mutation controls. Wrong gateway
routing after SQL selection refuses instead of acting on reused numeric references.

This is a trading-path full-stack proof, not full-platform readiness or HA. It omits optional
algo/OTC/observability services and a market-price publisher. Blank marks are expected; no fixture
price is presented as live. The inline editor supports Limit replacement; all seven submission
payloads and STP group behavior have separate source/generated tests. No production authentication,
financial validation, latency, cloud deployment or retained-rig activation is claimed.

2026-09-30 evidence: coordinator `review-evidence/desk-managed-recovery-20260930/`. The final proof
passed 29 named checks plus SQL/browser wait assertions. Initial incomplete-driver and browser-label
failures are preserved alongside passing attempts. A bounded forced stop of an owned process during
restart exercises retained-storage recovery; it is not an HA failover.
