# Local Batch result ingestion

The Risk tab includes a Cloud Batch portfolio section backed by an explicitly selected,
verified local import. The engine remains external to TraderX. Import and status reads
make no cloud calls and never resubmit a portfolio.

## Import and selection

Run `python3 scripts/risk-result-ingest.py import --work BUILD_DIRECTORY
--evidence BATCH_DOWNLOAD_DIRECTORY --store PRIVATE_STORE_DIRECTORY` as one command.
The build directory is the retained Cloud Build/Batch handoff evidence; the download
directory contains the terminal job, generation-pinned control/completion metadata and
artifacts. The importer re-runs the full frozen package/build/config/UID/control/result
verification before publishing a complete run directory outside Git.

The output gives `directory` and `sha256`. Configure the console operator environment:

- `RISK_BATCH_DIRECTORY`: that absolute run directory.
- `RISK_BATCH_SHA256`: the returned admission digest, retained independently of imported files.
- `RISK_BATCH_STATUS_SCRIPT`: absolute path to `scripts/risk-result-ingest.py` in the owned checkout,
  or the generated `risk-portfolio-tools/result_ingest.py`.
- `EOD_PYTHON`: optional Python executable; defaults to `python3`.

For the isolated local UI, build `web-front-end-console`, set those variables and run
`node scripts/ri03-console.mjs` from the checkout. `PORT` selects the loopback port and
`STATIC_ROOT` optionally selects the built console directory. This host serves the existing
Risk tab and its read-only adapters without cluster/cloud proxies. Unconfigured legacy
sections report unavailable; the gateway is not started by this host.

`GET /risk/batch-result` uses the same bounded subprocess pattern as `/eod/jobs`.
The browser cannot select a directory, digest or run. Reads rehash the entire retained
inventory and re-run full offline verification. Missing, changed or invalid custody returns
503 without a price or private filesystem diagnostics. The UI clears stale values before
refresh, performs no periodic polling and makes no writes. The existing `/eod/jobs`
contract remains unchanged; Batch portfolio results have a separate contract.

## Custody and replay

Each run has its own directory. No latest/current pointer is advanced automatically.
Exact replay is idempotent; a different retained evidence set for the same run is rejected.
Concurrent publication cannot overwrite an existing nonempty directory. Files are copied
and checked in a staging directory before rename; no partial stage is served. An interrupted
import may leave an unselected `.import-*` directory for operator inspection.

Absolute package locators are preserved as original provenance. Working copies use exactly
`../package`, resolved relative to the copied build directory; other relative paths are refused.
The selected directory is portable. Symlinks, special files, missing/extra files, changed hashes,
wrong pins, mismatched generations and changed account/run/cut/job identity are refused.
Local operator selection and its independently retained digest are the trust boundary, not
production authentication or immutable storage enforced against a privileged administrator.

## Scope and validation

This profile admits a nonempty synthetic USD Treasury bill portfolio with explicit synthetic curve assumptions. The UI displays account, run, valuation time, cut, trade count, NPV and provenance.
Scenario risk, VaR/ES and general financial validation remain unavailable/not performed.
It does not assert that the imported cut matches current live positions.

Run `python3 scripts/test-risk-result-ingest.py --work BUILD_DIRECTORY
--evidence BATCH_DOWNLOAD_DIRECTORY --output NEW_PRIVATE_PROOF_DIRECTORY` as one command
for real-artifact admission, idempotent/concurrent replay, relocation, tamper, wrong UID,
control generation, account/cut, extra-file, symlink, path traversal and conflicting replay checks.
The input evidence is preserved; mutations use disposable copies.

The October 15 local proof used the downloaded 32-artifact mapped Batch run and displayed
its verified one-trade result in the actual built Angular UI. Saved private evidence is at
`/private/tmp/risk-ingestion-proof-final`; source/generated checks and UI tests are recorded
in the bounded task. This is local integration evidence; no cloud deployment or shared-branch
integration is implied. The local UI uses a development build; production font inlining needs
network access and was not qualified in this offline proof.
