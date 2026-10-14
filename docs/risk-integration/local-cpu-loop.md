# Current local CPU portfolio loop

The local loop generates direct synthetic USD Treasury portfolios, submits each
once to the real installed CPU API and subprocess worker, and accepts hash-linked
results after independent flat-curve cashflow checks. It is separate from sequenced
TraderX exports, `/eod/price`, account authorization and the Risk UI.

Numerical pin: Alex `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`. Approved separate owned local CPU service
profile: `270a907a313bc0184ddcc7cda2e50904d103fce3`, branch
`codex/risk-local-cpu-engine` in the isolated `jax-risk-local-cpu` checkout. This
profile adds durable portfolio submission identities and v2 content-bound GET recovery; it does
not replace the accepted older C8 container/EOD profile. TraderX source is the
YU18 `risk-portfolio-tools` runtime override. No pricing implementation is copied.

## Reproduce

Tested on macOS arm64, Python 3.14.6, JAX/jaxlib 0.10.2 and
open-source-risk-engine 1.8.16.0, CPU only. Use the engine's pinned constraints.
Create a fresh private directory outside Git, and run from this TraderX checkout:

```sh
CPU_RUN="$(mktemp -d /private/tmp/traderx-cpu-XXXXXX)"
ENGINE=/Users/yaakov/dev/lmax/jax-risk-local-cpu
REV=270a907a313bc0184ddcc7cda2e50904d103fce3
mkdir "$CPU_RUN/engine-source"
git -C "$ENGINE" archive "$REV" | tar -x -C "$CPU_RUN/engine-source"
uv venv --python python3.14 "$CPU_RUN/venv"
uv pip install --python "$CPU_RUN/venv/bin/python" \
  -c "$CPU_RUN/engine-source/constraints.txt" "$CPU_RUN/engine-source[api,dev]"
PYTHONDONTWRITEBYTECODE=1 JAX_PLATFORMS=cpu "$CPU_RUN/venv/bin/python" \
  scripts/risk-local-cpu.py --engine-root "$ENGINE" --engine-revision "$REV" \
  --evidence "$CPU_RUN/proof"
python3 scripts/risk-portfolio.py inspect "$CPU_RUN/proof/job-00"
```

The runner checks the clean committed candidate and every installed engine Python
and packaged evidence file against source before launching. Service and worker
import the installed wheel from the private run directory. It retains inputs,
SQLite state, compilation cache, service log, raw replies and report, and stops
its owned local process group on exit. `PASSED` requires nonempty completed
pricing, verified intake and the recovery/refusal checks below; a 202 is insufficient.

## Closed synthetic profile and checks

- Four-position bill/note long/short golden; two repeated and two heterogeneous
  100-position portfolios; one heterogeneous 1,000-position portfolio; one
  8-position, 8-path exposure run. Base pricing is float64; simulation, market and
  pricing precision must report float64 on CPU without warnings.
- Each position's dirty NPV is checked independently as explicitly enumerated
  semiannual coupons and redemption discounted on the synthetic flat continuous
  ACT/365 curve. The original golden's signed accrued values are checked directly
  against elapsed coupon days. This restricted oracle is not general financial
  validation. The portfolio HTTP result does not expose an accrued-interest field.
- The exposure cube contains the three requested future dates. Profiles prepend
  t=0; intake verifies date/shape/coverage, finite EPE/ENE and the independent t=0
  values. Future exposure values have no independent model oracle here. This
  risk-neutral exposure simulation is not market-risk VaR/ES.
- API/worker restart preserves the same completed job, recovered with GET and zero
  recovery POSTs. An intentional consumer exit after a real POST reply leaves an
  uncertain intent; GET lookup/poll recovers it without another submission.
- Recovery and explicit-job lookups must match a complete persisted request digest. Changed simulation seed/sample count is refused with GET only and no acceptance; formatting and equal numeric representation recover the same job. Explicit/omitted-default HTTP admission remains idempotent. Offline witnesses reject a rewritten request even if its local hashes are updated.
- Conflicting submission identity, unknown fields, unsupported products, duplicate
  trade identity and missing market data must fail without adding jobs. Offline
  intake must refuse corrupted custody. Queue bytes independently witness the
  submitted body, completion state and execution contract.

Every accepted run carries `financial_validation=false` and direct synthetic
provenance. Existing legacy fixture bytes, pins and `/eod` intake are unchanged.
Cloud/container/TPU qualification, sequenced portfolio export and production UI
remain separate milestones. Private runtime evidence stays outside Git.

## Individual portfolios

```sh
python3 scripts/risk-portfolio.py generate /private/tmp/my-new-corpus \
  --profile current-cpu-v1 --trades 100 --portfolios 2 --mode heterogeneous
python3 scripts/risk-portfolio.py validate /private/tmp/my-new-corpus
```

Generation's CLI default is `current-cpu-v1`; `--profile legacy-2df78cb` reproduces
the old wire body. The Python generator function retains its legacy default for
existing callers; select the current profile explicitly. Native schema validation
can use the clean original numerical pin through `--engine-root`; service proofs
use the separate installed identity candidate. Submission/poll/recovery and
offline inspection are provided by `scripts/risk-portfolio.py`.

## Previously accepted C8/EOD profile

C8 names the earlier Alex upstream revision `c8e21ea`; it is not a hardware type. The reconciled service/container version `e770f912` was approved on October 9 in the owned `jax-risk-container-upgrade` checkout, with measured image `sha256:80c885f8305067421a4c34af3eeef95ab1eca5a38376e9dcc689f8fe8bd5483d`. Its scoped container acceptance covers durable submission/recovery behavior and selected synthetic CPU pricing; it does not establish general financial correctness or production deployment. See [the acceptance record](../../issues/risk-integration/resolved/ri16-upstream-c8-upgrade.md) and [LR03](../../issues/risk-integration/live-rig/lr-03-risk-container-pipeline.md).

The older `/eod/price` TraderX adapter and its explicit pins remain unchanged. The newer `270a907a` profile uses numerical revision `c3d7c6a` and has a real local installed CPU proof, without new container qualification or sequenced TraderX exporter/account/run lineage. Keeping both profiles preserves the earlier measured contract while this newer portfolio path is developed. The current local integration is [complete](../../issues/risk-integration/resolved/local-cpu-portfolio-integration-20261014.md); broad connected Stage B remains open.
