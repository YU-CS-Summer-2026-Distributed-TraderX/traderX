# Local synthetic USD Treasury tools

Python standard library for generation, validation and client. Engine verification additionally needs the pinned engine's dependency environment. Source owner is YU18; the renderer copies this directory to `generated/code/target-generated/risk-portfolio-tools`. The root wrapper runs authoritative source directly.

All inputs and outputs here are synthetic. Requests represent direct engine portfolios, not TraderX exporter lineage. Default output is base dirty NPV only, with no scenarios or Greeks. This is separate from `/eod/price` and does not change the accepted EOD container.

## Generate and validate files

From the repository root:

```sh
python3 scripts/risk-portfolio.py generate /private/tmp/ri14-demo --seed 42 --trades 100 --mode heterogeneous
python3 scripts/risk-portfolio.py validate /private/tmp/ri14-demo
# Use the engine dependency environment for the optional native schema proof:
/path/to/engine-venv/bin/python scripts/risk-portfolio.py validate /private/tmp/ri14-demo --engine-root /path/to/clean/pinned/engine
```

Output directory must be new and outside Git checkouts. `--portfolios 10 --trades 100` means ten independent portfolios of 100 positions each. `--mode repeated` repeats bill/note economic shapes with paired long/short signed face; heterogeneous mode varies face, bill maturity, note coupon and number of regular semiannual periods. Dates advance from an anchor with day <=28, keeping regular periods valid at month ends. Rates are decimal annual continuous rates; `--asof` is ISO date. `--samples 8` adds three future simulation dates; it remains exposure simulation, not market-risk VaR/ES.

Hard limits: 100000 total trades across at most 1000 portfolios; 128 MiB total corpus bytes including manifest. Generation streams one trade at a time and cleans staging on error. It never overwrites a destination. Same settings/seed reproduce request bytes, manifest and IDs; change seed for a distinct corpus. Unsupported currencies/instruments are rejected by the deliberately narrow local profile. Native validation reuses the engine schema/validators.

## Local submission, polling and recovery

```sh
python3 scripts/risk-portfolio.py submit /private/tmp/ri14-demo/portfolio-0000.json --manifest /private/tmp/ri14-demo/manifest.json --origin http://127.0.0.1:8000 --evidence /private/tmp/ri14-submit
python3 scripts/risk-portfolio.py poll /private/tmp/ri14-demo/portfolio-0000.json --job-id JOB_ID --origin http://127.0.0.1:8000 --evidence /private/tmp/ri14-poll
python3 scripts/risk-portfolio.py recover /private/tmp/ri14-demo/portfolio-0000.json --manifest /private/tmp/ri14-demo/manifest.json --origin http://127.0.0.1:8000 --evidence /private/tmp/ri14-recover
```

Loopback HTTP only. Each evidence directory must be new and outside Git. Manifest binds raw bytes and portfolio/submission identity; an explicit `--submission-id` can be used without a manifest, for example with the golden fixture. POST happens once at most. Before sending, receipt records submission uncertainty. HTTP500, malformed acceptance and lost reply preserve uncertainty; no automatic retry. Recovery is a GET to the RI16 submission lookup, then GET polling. Base engine without RI16 lacks this lookup and may ignore the key. Unknown lookup is not evidence an in-flight submission can never commit. Failed/interrupted are terminal and never automatically rerun; interrupted completion remains unknown. Timeout or client interruption retains any known job identity.

`receipt.json` records request SHA256, exact request snapshot, HTTP status/headers, elapsed transport times and every bounded raw reply file/hash. `reply-NNNN.bin` preserves original bytes; partial replies are retained on error. Defaults: 10s socket timeout, 120s total deadline, .5s polling interval, at most 1000 polls. Configure with `--timeout`, `--deadline`, `--interval`, `--max-polls`. Receipt timing measures submission/poll transport and observed total completion; it does not separately measure engine queue wait, compute or serialization.

Exit0 means DONE with identity/count/sign/accounting checks; other outcomes exit2. Golden fixture additionally checks independent dirty NPV. Other generated corpora have no independent pricing oracle. Source assertions reject missing/nonfinite/inconsistent results. No cancellation, worker-readiness or exactly-once claim.

## Bounds and size ladder

| Trades per portfolio | File request bytes | Generation/validation scope |
|---|---|---|
| 100 | 48893 measured, heterogeneous seed42 | Local file smoke and native engine schema validation |
| 1000 | 489469 measured, heterogeneous seed42 | Local file smoke, bounded structural validation |
| 10000 | about 4.9 MB projected from 1000 | Not generated or submitted; actual heterogeneous shape/bytes may differ |
| 100000 | about 49 MB projected from 1000 | Not generated or submitted; file-only target, exceeds client limit |

A measured 1000-trade generation call took 0.01245s, process maximum RSS 23085056 bytes on macOS arm64/Python3.14. This is one local generator observation, not stable throughput or engine capacity. CLI startup and native schema memory are separate. Large output/results remain outside source control.

Validation/client request limit8 MiB; reply16 MiB; accumulated raw replies128 MiB. Request snapshot plus bounded parsing, reply parsing and metadata also consume memory/disk. Optional scenario sample cap128 with three future dates: raw float64 cube lower bound `samples * 4 * trades * 8` includes t=0 and must be <=64 MiB. This omits intermediates, retained outputs and JSON expansion; it cannot guarantee engine memory fits. Base pricing has cube lower bound0. No massive service load, GPU/device campaign or cloud work was run.

## Reproduce verification

```sh
bash scripts/test-portfolio-generator.sh
PYTHONDONTWRITEBYTECODE=1 /path/to/engine-venv/bin/python specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/verify_engine.py --engine-root /path/to/clean/pinned/engine --evidence /private/tmp/ri14-native-proof
PYTHONDONTWRITEBYTECODE=1 /path/to/engine-venv/bin/python specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/verify_http.py --engine-root /path/to/engine --evidence /private/tmp/ri14-http-proof
# When a committed RI16 candidate is available, explicit opt-in:
PYTHONDONTWRITEBYTECODE=1 /path/to/engine-venv/bin/python specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/verify_http.py --engine-root /path/to/ri16-clone --engine-revision COMMIT --identity-recovery --evidence /private/tmp/ri14-ri16-proof
```

Delivered evidence: 17 source tests passed. The golden completed through the real baseline API/subprocess worker and matched the analytic reference; its owned processes stopped. RI16 commit `f120072f0a24b13a3f12194cdda579445d23848c` also completed the golden through its archived CPU API/worker; GET submission lookup recovered the same DONE job with zero recovery POSTs. All owned processes stopped. Coordinator service/financial contract acceptance remains pending. Four repository gates passed; YU18 source composition and module byte parity were checked with dependency refresh/API explorer installation skipped. No full generated-runtime build was performed.

Unit suite uses real localhost transport with honest service doubles and closes every server. Native proof reuses clean pinned schema/validation/pricing, compares the four-position golden against explicit independently enumerated cashflows and coupon elapsed days, and tests five engine refusals. HTTP proof archives only a committed engine revision into its private evidence directory, starts a separate CPU API/worker/queue/cache, submits four positions and stops its owned process group in `finally`. It never restarts a retained rig or modifies Alex's checkout. Dependency installation is not performed.

The golden reference covers regular flat-curve bills and notes, signed dirty PV, zero bill accrual, signed note accrual, clean-dirty relation and cancellation of opposite positions. It does not validate general interpolation, irregular schedules, simulations, Greeks, credit, FX or market-risk aggregation. Synthetic price agreement is narrow numerical evidence; `financial_validation=false` stays in manifests/receipts.
