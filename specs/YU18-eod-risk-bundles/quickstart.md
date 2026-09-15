# Quickstart: EOD Risk Bundles

Run from the repository root with Python 3.10+. The core workflow uses no cluster, TAQ conversion or paid runtime resources. The test suite includes localhost HTTP socket tests; loopback networking must be permitted. Section 8 may download development dependencies. Optional section 9 reads private GCS objects using an authenticated gcloud installation.

## 1. Test the source component

```bash
bash scripts/test-state-YU18-eod-risk-bundles.sh
```

## 2. Build, validate and consume a synthetic bundle

```bash
component="$PWD/specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles"
eod_demo_dir="$(mktemp -d /tmp/traderx-eod-demo.XXXXXX)"
python3 "$component/bundle.py" build \
  --positions "$component/tests/fixtures/positions.csv" \
  --contracts "$component/tests/fixtures/contracts.csv" \
  --epoch synthetic-demo-epoch --valuation-time 2025-06-02T16:00:00-04:00 \
  --origin synthetic --output "$eod_demo_dir/bundle"
python3 "$component/bundle.py" validate "$eod_demo_dir/bundle"
python3 "$component/bundle.py" mock "$eod_demo_dir/bundle" --output "$eod_demo_dir/result"
cat "$eod_demo_dir/result/results.json"
```

Expected: MOCK_COMPLETE, usableForRisk=false, submitted=2, priced=0, null NPVs. No service remains running. The files remain in the printed/selected temporary directory until you remove them.

## 3. Package actual local exports

Use the same build command with actual position/contract paths, `--origin export`, the actual cluster epoch and the intended offset-aware valuation time. Keep inputs and outputs outside the public repository. The validator checks consistency, not producer authenticity or financial priceability.

## 4. Generate the state

```bash
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-eod-risk-bundles
bash scripts/test-state-YU18-eod-risk-bundles.sh generated/code/target-generated/eod-risk-bundles
```

Full generation composes the inherited runtime locally. Skipping lockfile refresh avoids npm lockfile network refresh; the local component commands above do not depend on generation.

## 5. Run the local durable coordinator

Continue using `component` and `eod_demo_dir` from step 2, or set fresh private paths:

```bash
mkdir -p "$eod_demo_dir/inbox"
python3 "$component/bundle.py" build \
  --positions "$component/tests/fixtures/exchange/positions.csv" \
  --contracts "$component/tests/fixtures/exchange/contracts.csv" \
  --epoch synthetic-demo-epoch --valuation-time 2025-06-02T16:00:00-04:00 \
  --origin synthetic --output "$eod_demo_dir/inbox/cut-1"
python3 "$component/coordinator.py" --state "$eod_demo_dir/coordinator" discover "$eod_demo_dir/inbox"
python3 "$component/coordinator.py" --state "$eod_demo_dir/coordinator" run
python3 "$component/coordinator.py" --state "$eod_demo_dir/coordinator" status
```

Expected: one job, one MOCK_COMPLETE attempt, VERIFIED result integrity and usableForRisk=false.
All three fixture instruments remain NOT_PRICED. Repeating discover/run adds no job or attempt.
Stopping and restarting `run` recovers interrupted jobs. To retry a FAILED job explicitly, copy its
`job_id` from status and run `coordinator.py --state DIRECTORY retry JOB_ID`, then `run` again.
Inputs, SQLite state and results remain in this private directory. Keep it on a local filesystem;
shared/network filesystems and multi-host workers are not supported by this locking protocol.
The tests intentionally terminate worker processes before and after result publication to check recovery.

## 6. Prove the real exporter-to-coordinator path locally

After generation, with Java 21 and the existing Gradle dependencies cached:

```bash
bash scripts/demo-state-YU18-eod-export.sh
```

The script creates a private temporary directory and prints its path. It drives two matched order
pairs (equity and Treasury bill) and one SOFR booking through the real service's sequenced message
handler, captures the actual export cut, runs both production CSV renderers, and publishes the same
completion payload the producer uses. It checks four signed position rows and one OTC row through
the bridge and coordinator, including a repeated-delivery check. Java tests must actually run and
pass; stale generated files or empty test reports are failures.

This is an in-process engine/exporter proof. It does not exercise HTTP ingress, live Aeron consensus,
the pricing/P&L EOD services, SQL mark loading, NATS delivery or Alex's engine. Marks/reference data
are synthetic and every returned risk item remains NOT_PRICED. No services remain running afterward.
An optional argument names a new private output directory outside Git; existing directories are refused.
The script uses offline Gradle and fails with a log path if dependencies have not yet been cached.

## 7. Package completed exports from a local producer

Configure the YU18 producer's existing `RISK_EXTRACT_SINK_URI` as a local file URI and set
`RISK_EXTRACT_READY_DIRECTORY` to a private (0700) directory. After both exports are written, the
producer atomically publishes a `.ready.json` receipt there before announcing the existing NATS
notification. Leaving the new setting unset retains the previous notification-only behavior.

```bash
python3 "$component/bridge.py" \
  --receipts /private/path/to/receipts --artifact-root /private/path/to/exports \
  --inbox /private/path/to/inbox --epoch ACTUAL_CLUSTER_EPOCH \
  --session-date 2025-06-02 --valuation-time 2025-06-02T16:00:00-04:00 --origin export
```

Replace the date, timestamp and epoch with the actual cut context. Epoch and origin are explicit
caller assertions; the existing readiness event does not authenticate them. Receipt directories must
not mix cluster epochs. Other business dates are reported as skipped, never relabelled. Invalid
receipts make the command fail while reporting their reason. The bridge supports local file URIs
within the allowed artifact root only; it does not fetch GCS or subscribe to NATS. Run coordinator
`discover` and `run` on this inbox as in section 5. These are explicit commands, not a scheduler.

## 8. Validate the proposed exchange schemas

The validator is a development dependency, separate from the stdlib runtime:

```bash
python3 -m venv /private/tmp/traderx-schema-check
/private/tmp/traderx-schema-check/bin/python -m pip install \
  -r specs/YU18-eod-risk-bundles/contracts/proposed/requirements-test.txt
PYTHONDONTWRITEBYTECODE=1 /private/tmp/traderx-schema-check/bin/python -m unittest discover \
  -s specs/YU18-eod-risk-bundles/contracts/proposed -p 'test_schemas.py' -v
```

Four tests check both meta-schemas, positive examples and negative field/date/identity-shape/measure
cases. Format-checking dependencies are required: plain jsonschema can silently skip date-time checks.
Examples are hand-authored, non-executed illustrations with non-resolving paths and placeholder hashes;
they must not be submitted to a worker or treated as prices measured from an engine. These checks do
not establish cross-file identity, correct financial terms or Alex's acceptance of the draft.

## 9. Stage private GCS exports locally

This command makes read-only GCS requests and starts no compute. Select a narrow allowed prefix and a per-object byte limit. Use a private, canonical local path outside Git (on macOS, `/private/tmp` avoids the `/tmp` symlink).

For an actual captured `risk.extract.ready` payload whose two URIs are GCS objects:

```bash
component="$PWD/specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles"
gcs_demo_dir="$(mktemp -d /private/tmp/traderx-gcs.XXXXXX)"
python3 "$component/gcs_stage.py" --receipt /private/path/to/producer-event.json \
  --allowed-prefix gs://YOUR_BUCKET/YOUR_EXPORT_PREFIX/ --max-bytes 1000000 \
  --output "$gcs_demo_dir/staged"
```

Then pass `--receipts "$gcs_demo_dir/staged" --artifact-root "$gcs_demo_dir/staged"` to the bridge command in section 7, supplying the recorded cluster epoch, business date, valuation time and origin. Continue with coordinator discovery/run in section 5. Retain `source.json` and `source-receipt.json` with the private staging directory: the current three-file bundle does not embed GCS provenance. Repeating staging at the same destination accepts identical bytes/generations; changed inputs require a new destination.

For historical files without a completion receipt:

```bash
python3 "$component/gcs_stage.py" \
  --archive-positions gs://YOUR_BUCKET/2025-06-02/v1/seq-42.csv \
  --allowed-prefix gs://YOUR_BUCKET/2025-06-02/v1/ --max-bytes 1000000 \
  --output "$gcs_demo_dir/archive"
```

Archive mode reads the positions object and its `-contracts.csv` and `.cut` siblings. It verifies source-cut SHA-256 and matching metadata, records each immutable generation, and emits `ARCHIVE_ONLY_NO_COMPLETION_RECEIPT`. It does not queue a job or fabricate epoch/valuation-time/adjacent-witness evidence. A missing OTC object is an error; a valid empty OTC file is accepted. A specific positions generation can be selected with a quoted `gs://...csv#GENERATION` URI; sibling generations are resolved individually and recorded.

The 1,000,000-byte limit in these examples is a caller-selected transfer ceiling per object, not a throughput setting or a proven production sizing recommendation. All objects must fit before publication. Authentication, permission, timeout, missing-generation and integrity failures exit nonzero and publish no new staging directory. Existing cloud objects are never changed. Keep real exports and results outside the public repository.

## 10. Exercise the provisional HTTP worker locally

Run the standalone demonstration (it starts and stops its own fake worker):

```bash
python3 scripts/demo-state-YU18-http.py
```

The demo uses synthetic equity/bill/SOFR exports. It completes one HTTP mock job, repeats delivery,
restarts the fake-worker process, and uses a fresh consumer to retrieve the same durable worker
result. Both consumers have one local job/attempt; they refer to the same worker attempt. The
original worker result bytes and modification time must remain unchanged. Private evidence stays
in the printed temporary directory; the fake-worker processes are stopped even on failure.

For interactive use, start the fake worker in a terminal:

```bash
component="$PWD/specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles"
http_demo_dir="$(mktemp -d /private/tmp/traderx-http.XXXXXX)"
python3 "$component/fake_worker.py" --state "$http_demo_dir/worker"
```

The first line prints its `http://127.0.0.1:PORT` URL. In another terminal, set `component` and
`http_demo_dir` to the same paths, then supply that URL with every coordinator command:

```bash
worker_url=http://127.0.0.1:PORT
python3 "$component/coordinator.py" --state "$http_demo_dir/coordinator" --http-worker "$worker_url" discover /private/path/to/inbox
python3 "$component/coordinator.py" --state "$http_demo_dir/coordinator" --http-worker "$worker_url" run
python3 "$component/coordinator.py" --state "$http_demo_dir/coordinator" --http-worker "$worker_url" status
```

Use a separate coordinator state from earlier in-process mock runs. HTTP run exits 2 when remote
work remains pending/uncertain, 1 on a failure or invalid result, and 0 when no pending/failed work
remains. Run again to reconcile a pending attempt; use explicit retry only for FAILED jobs. Status
can verify accepted local results while the worker is offline. Stop the fake worker with Ctrl-C.

This deliberately uses a local mock protocol, inline base64 CSVs, a fixed non-pricing profile and
no authentication. It accepts only literal 127.0.0.1 URLs and refuses redirects/proxies. It must not
be presented as Alex's API or a deployed risk service. See contracts/http-mock-draft-1.md for the
exact boundary and production gaps. No cloud operation is needed.

## 11. Frozen v1 hashes and exporter-produced bundle v2 examples

After generating YU18, run:

```bash
python3 specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/verify_golden.py
bash scripts/demo-state-YU18-shared-examples.sh
```

The first command needs only Python. The second uses cached Java 21/Gradle dependencies offline,
creates a private temporary evidence directory, runs the in-process exporter for three cases,
and compares fresh output with committed synthetic fixtures. No GKE, market download or TAQ
conversion is performed. Local Gradle cache/daemon permissions may be needed.

The package for Alex lives under
`specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/tests/fixtures/shared/`.
Each case contains v1/v2 directories, a cut and a case.json acceptance expectation; provenance.json
pins the exporter source. The note contains both long and short positions. SOFR unsupported is
an expected response from Alex, not a result fabricated by our mock.

For an explicit v2 build, use the existing build arguments and add `--terms instrument-terms.json`.
No terms argument means v1. The local coordinator accepts either; use separate candidate inputs
for demonstrations so equivalent cuts with distinct bundle versions are not ambiguous. The HTTP
draft-1 adapter refuses v2. Terms/reference format and assumptions are documented in
[the v2 contract](contracts/bundle-v2-and-terms.md); exact v1 encoding is in [golden vectors](contracts/golden-v1.md).

## 12. Cross-platform fixture compatibility

Run `python3 scripts/test-state-YU18-checkout.py` to prove byte preservation through real Git CRLF checkout filters. This creates only temporary local repositories and requires no network. See [compatibility-v3](contracts/compatibility-v3.md) for the new terms-version example and the deliberately invalid missing-accrual fixture.

### Actual Alex W0 adapter, locally

Run `python3 scripts/demo-state-YU18-alex-w0.py --engine /path/to/JAX_Risk_Engine` against clean engine commit `cb9b277a9de702b2ba4f0bcda431a396f54c029a`. This accepts bill/note accrual conversions and explicit SOFR refusals into a separate local coordinator profile. `W0_VALIDATED` is not pricing completion; `usableForRisk` remains false. See `docs/risk-integration/local-w0-intake.md` for compatibility limits, manual intake and evidence semantics.

## 13. Dated synthetic market inputs

```bash
python3 scripts/demo-state-YU18-market-inputs.py
component=specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles
python3 "$component/market_inputs.py" build \
  --metadata "$component/tests/fixtures/market-inputs/metadata.json" \
  --observations "$component/tests/fixtures/market-inputs/observations.json" \
  --output /private/tmp/CHOOSE-A-NEW-MARKET-PACKAGE-DIRECTORY
python3 "$component/market_inputs.py" validate /private/tmp/CHOOSE-A-NEW-MARKET-PACKAGE-DIRECTORY
```

The demo repeats the build and checks a fixed package ID. Its par-yield/fixing values and timestamps
are invented synthetic examples, not observed market data or release schedules. Exit 2 means valid
structure but unsuitable selection; exit 1 means corrupt/invalid structure. See
[the contract](contracts/market-input-package-v1.md). Pricing remains unavailable.
