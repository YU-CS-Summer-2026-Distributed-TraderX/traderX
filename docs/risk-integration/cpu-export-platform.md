# Local CPU platform packaging and TraderX export mapping

This path connects a sequenced synthetic TraderX export to the regular
`POST /portfolio/price` API and verifies the retained result offline. It runs the
risk engine as a separate process inside a local container. It does not update
the existing EOD coordinator or Risk UI, authorize accounts, or activate cloud jobs.

## Ownership and dependencies

Platform source lives in YU18's `risk-portfolio-tools` runtime override. Its
`packaging/` directory owns the Dockerfile, Linux AMD64 CPU wheel hash lock, and
installed-file verifier. The existing `eod-risk-bundles/bundle.py` and
`instrument_terms.py` validators are included from the same TraderX commit.

The numerical engine revision is `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`.
The separately owned, accepted API service dependency is
`270a907a313bc0184ddcc7cda2e50904d103fce3`, including content-bound submission
recovery. Preparing a package requires a clean checkout at that exact service
commit; unmodified Alex upstream alone does not supply this service extension.
Alex's original checkout is read-only. No numerical engine code is copied into
TraderX or changed by packaging.

Preparation reads committed Git objects, refuses dirty source checkouts, exports
complete engine source and platform files, and records hashes and revisions.
It uses a digest-pinned Python base, 31 hash-pinned Linux wheels and a pinned
Cloud Build Docker builder. Repeating preparation with the same committed
sources, run and mapped input produces an identical package manifest and build
context. This is source-package reproducibility, not a promise of identical
OCI image digests across separate Docker builds.

## Admitted mapping

The initial profile is `traderx-synthetic-usd-bill-v1`:

- A nonempty explicitly selected account in a validated synthetic v2 bundle.
- USD Treasury zero-coupon positions, complete explicit reference terms,
  unadjusted maturity payment, unit redemption and same-day settlement.
- Signed integer USD face, exactly representable in float64. Quantity maps
  directly to face; the face denomination is not a multiplier. Fractional face
  is refused instead of rounded.
- An explicitly supplied synthetic flat continuous ACT/365F discount curve with
  the same date as the export cut and valuation time. Missing market inputs
  never select a default curve.
- Every position in the selected account must be supported. Selected-account
  OTC contracts, unsupported terms/currencies and empty coverage are refused.
  Unselected accounts are counted separately; their positions are not netted
  against the selected account.
- Base dirty NPV only. No Greeks, scenario risk, observed market-data claim or
  general financial validation.

The bill's engine `accrual_day_count` field is `ACT/ACT (ICMA)` to satisfy the
accepted engine profile; zero coupon and an empty coupon schedule make coupon
accrual inapplicable. Discounting uses the explicitly supplied ACT/365F curve.
Exporter closing marks are retained in the source bundle, not substituted for
that curve.

The mapping retains the bundle ID, epoch, full cut, valuation time, source-file
hashes, market assumption/hash, account/run identity and security-to-trade map.
Bundle validation checks artifact integrity and paired cut metadata. The local
proof additionally runs the generated sequenced exporter tests; it does not
claim a live distributed cluster or independently authenticate a caller's account.

## Local reproduction

Use a clean committed TraderX checkout and the exact owned service dependency.
The commands below execute only locally. All run artifacts stay outside Git.
Replace `ENGINE_ROOT` with that dependency's checkout. `PROOF` must name a new
private directory.

```bash
ENGINE_ROOT=/Users/yaakov/dev/lmax/jax-risk-local-cpu
PROOF="$(mktemp -d /private/tmp/traderx-cpu-export.XXXXXX)"
export TRADERX_GENERATED_ROOT="$PROOF/generated"
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
bash scripts/demo-state-YU18-shared-examples.sh
```

The exporter proof prints its private evidence directory. Set `EXPORT_PROOF` to
that directory. Its `shared/bill/v2` is the freshly exported validated bundle.
The test's account `22214` has positive USD 100,000 face; `42422` has the offsetting
short position. Use one account to avoid a zero aggregate hiding a mapping error.

```bash
cat > "$PROOF/market.json" <<'JSON'
{"schema":"traderx.synthetic-flat-curve.v1","origin":"synthetic","asof":"2025-06-02","rate":"0.03","compounding":"continuous","day_count":"ACT/365F","description":"Explicit synthetic flat curve assumption; not observed market data."}
JSON
RUN_ID=risk-cpu-export-local-01
python3 scripts/risk-export-portfolio.py \
  --bundle "$EXPORT_PROOF/shared/bill/v2" --market "$PROOF/market.json" \
  --account 22214 --run-id "$RUN_ID" \
  --expected-bundle-id c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d \
  --output "$PROOF/mapped"
python3 scripts/risk-cpu-batch.py prepare \
  --engine-root "$ENGINE_ROOT" --output "$PROOF/package" --run-id "$RUN_ID" \
  --upstream-sha c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8 \
  --mapped-input "$PROOF/mapped"
python3 scripts/risk-cpu-batch.py check --package "$PROOF/package"
docker build --platform=linux/amd64 -t traderx-risk-cpu:local "$PROOF/package/context"
mkdir -m 777 "$PROOF/output"
docker run --rm --platform=linux/amd64 --network=none --cpus=2 --memory=6g \
  --pids-limit=256 --env "PILOT_RUN_ID=$RUN_ID" \
  --mount "type=bind,source=$PROOF/output,target=/evidence" \
  traderx-risk-cpu:local --local-input /opt/pilot/mapped/request.json --evidence /evidence/cpu
python3 scripts/risk-cpu-batch.py local-verify \
  --package "$PROOF/package" --evidence "$PROOF/output/cpu"
```

The output directory is writable by the container's nonroot UID; its enclosing
private directory remains mode 700. Keep the package and result directory for
verification. A failed or uncertain POST is not automatically retried. Reusing a
partially completed evidence directory is refused; inspect its retained receipt
before deciding how to reconcile an uncertain submission. This local runner is
not a distributed run scheduler or a durable cross-container replay fence.

Omitting `--mapped-input` selects only the original four-position frozen golden.
That profile uses `/opt/pilot/fixtures/usd-treasury-current.json` as local input.
Each image admits only the exact request and lineage baked into its manifest;
it is not yet a reusable arbitrary-portfolio production image.

## Result admission

The existing Driver records one POST intent, uses content-bound submission
recovery, and verifies the regular portfolio execution contract. Existing intake
checks retained HTTP custody and independently calculates the supported flat
curve cashflows. The wrapper verifies one completed SQLite job with matching
request/result bytes and requires the EOD store to contain only the four empty
initial directories. Files, symlinks and unexpected directories are refused.

`connected-result.json` adds the checked export lineage around the existing
portfolio acceptance. `local-verify` takes an independently retained package,
checks its complete context and expected build fingerprint, recomputes the mapping,
and binds the accepted result to that account/run/cut. Inner direct-portfolio
acceptance alone makes no TraderX lineage claim. None of these receipts is a
cryptographic attestation against a hostile operator who can replace the trusted
package itself.

## Measured local evidence, 2026-10-15

Runtime source `0531d0a2` follows implementation `5d2e30f7` and local-only CLI
correction `1d14decb`. The last runtime change only corrects the event label to
`local_cpu_portfolio_verified` for both profiles.

- 40 source and 40 generated contract tests passed at `1d14decb`.
- 56 generated Java exporter tests passed without failures/skips; all three
  shared exporter examples reproduced their frozen bytes. The six EOD mock runs
  in that existing proof are transport checks, separate from actual CPU pricing.
- A separately installed Linux AMD64 image on local Apple Silicon Docker
  emulation priced one positive USD 100,000 bill from account `22214`, cut
  sequence `8`, valuation date `2025-06-02`, maturity `2025-12-02`, flat 3% curve.
  Base NPV: **USD 98,507.14563826029**. One job/POST, no warnings, independent
  supported cashflow check passed; `financial_validation=false`.
- Ten mutations of retained real execution artifacts were refused: account,
  run, cut, job, submitted request, mapped request, result, queue completion,
  self-selected build and replay under a different expected package.
- The frozen four-position installed CPU control also passed, with base NPV zero.
- Repeated package preparation produced byte-identical package manifests.

Private evidence: `/private/tmp/risk-platform-proof-20261015/` (source packages,
Docker build/run logs, receipts, installed results, JUnit XML, mutation controls).
Fresh exporter evidence is also retained in the directories recorded by
`exporter-proof.log` and `final-exporter-proof.log`. Exact image/job identities
and cleanup are recorded in the private proof summary. This is local synthetic
CPU evidence, not a throughput benchmark, live cluster proof, cloud rollout or
production financial acceptance.

## Next integration gates

The maintained CLI exposes `prepare`, `check` and `local-verify`. It prepares a
Cloud Build configuration but does not submit it or qualify a cloud build's
output. Actual Batch UID handoff and generation-pinned build-source/receipt
admission remain pending. Google Batch does not automatically supply the
wrapper's `BATCH_JOB_UID` environment variable. A successful build status or a
matching image tag alone is insufficient evidence of this package's contents.

The previously successful cloud pilot digest `58148c2019f1112f837d979eb7ca3d0749c15f5ef14a08ba3f6de64c1786f21e`
remains a separate frozen-input proof. It cannot accept this mapped portfolio.
Next work is qualified cloud handoff and connected intake/status integration,
including caller authorization, durable run selection and replay policy. TPU/GPU,
additional asset classes and real market inputs require separate profiles/proofs.
