# Local EOD job-status console

The EOD page includes an Overnight jobs panel backed by `GET /eod/jobs` on the existing console
server. It reads the local coordinator database and revalidates accepted result custody. An extract
alone is not an overnight result. Mock and W0 results always remain unusable for financial risk.

## Run locally

First create synthetic coordinator state using the YU18 quickstart or W0 demo. Then from
`web-front-end-console`, build the app with the existing Angular dependencies and start the server:

```sh
npm run build
PORT=8090 AUTH_MASTER_SECRET='' \
EOD_COORDINATOR_STATE=/private/path/to/existing/coordinator \
EOD_STATUS_SCRIPT=/absolute/path/to/eod-risk-bundles/job_status.py \
node server.mjs
```

`EOD_PYTHON` may select a Python 3.10+ executable. The script must be the authoritative or rendered
YU18 component, with its sibling modules. Paths are server configuration, never browser inputs.
Visit the console's `/eod` page. Other existing panels still require their normal platform services;
this local job route itself makes no worker, cluster or cloud calls. The Angular dev proxy includes
`/eod/jobs`; use its existing `CONSOLE_API` configuration when developing against this server.

A repeatable proof starts and stops the real console server using private synthetic state:

```sh
python3 scripts/demo-state-YU18-job-status.py
```

It checks unavailable state without creation, an empty coordinator, queued and running jobs, accepted
W0 coverage, corrupted result custody and rejection of POST. It only requests `/eod/jobs`.
Pass `--component generated/code/target-generated/eod-risk-bundles` to exercise rendered Python.

## Read contract

Schema: `traderx.eod-job-status.v1`. Successful GET returns `availability=AVAILABLE`, `observedAt`,
`jobs`, `workerConnectivity=NOT_PROBED`, `producerAuthentication=NOT_ESTABLISHED` and
`usableForRisk=false`. Jobs include input bundle/cut/epoch/valuation identity, profile, status,
attempts and errors, current/ambiguous selection, result integrity and validated coverage when
available. Raw portfolio items and internal result paths are omitted. Error text is existing local
coordinator diagnostic text; configure only state appropriate for the audience of this open-read
console. This is not an authenticated remote producer attestation.

`QUEUED` displays Pending; `RUNNING` displays Running / awaiting result, since local W0 intake can be
waiting for a file. `FAILED`, `MOCK_COMPLETE` and `W0_VALIDATED` stay distinct. The panel never selects
an invalid stored result and clears previous data when refresh fails. Coverage is unavailable until
an accepted result passes integrity validation. W0 coverage accounts for outcomes, not priced risk.

A missing configuration, missing database, unsupported profile, timeout or failed read returns 503
and `availability=UNAVAILABLE`, with no manufactured empty job list. Only an existing empty database
returns zero jobs. Responses use `Cache-Control: no-store`; refresh is explicit. A worker is not
probed, so a successful read says nothing about connectivity or liveness. Stored RUNNING can represent
interrupted work until an operator runs coordinator recovery; GET never performs that recovery.

The Python reader opens SQLite in read-only/query-only mode and uses a single read transaction.
It bypasses the coordinator's execution lock without initializing schema or mutating jobs, so it can
observe committed running status during execution. Immutable result custody is checked through the
existing profile-specific validator; file corruption makes the result INVALID. Node executes a fixed
operator-configured script with an argument array, a 10-second timeout and a 4 MiB output ceiling.
Timeouts or larger responses are unavailable, not partial. These are local safety limits, not measured
production sizing. No shell and no request-supplied arguments are used.

Reads follow the console's existing open-read convention; no mutation endpoint was added. Non-GET
requests receive 405, regardless of login. Existing mutation authentication is unchanged.

## Remaining deployment work

No deployment or cloud wiring was performed. A deployed console needs a reviewed private state
mount, the matching Python component/runtime, deliberate audience/access policy, and bounded history
or pagination for larger datasets. The Dockerfile includes the Node route module, but does not embed
private state or the Python component. An unconfigured deployment accurately shows unavailable.
Durable remote submission, worker authentication and financial valuation remain separate milestones.
Order-to-aggregated-position navigation is not implemented.
