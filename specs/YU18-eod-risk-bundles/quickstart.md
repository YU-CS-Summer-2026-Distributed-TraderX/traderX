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
