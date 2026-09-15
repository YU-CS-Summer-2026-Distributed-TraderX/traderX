# Quickstart: EOD Risk Bundles

Run from the repository root with Python 3.10+. The added workflow uses no cluster, network, TAQ conversion or paid resources.

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
