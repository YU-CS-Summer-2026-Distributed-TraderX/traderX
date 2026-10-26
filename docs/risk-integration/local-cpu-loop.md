# Current local CPU portfolio loop

The local loop generates direct synthetic USD Treasury portfolios, submits each
once to the real installed CPU API and subprocess worker, and accepts hash-linked
results after independent flat-curve cashflow checks. It is separate from sequenced
TraderX exports, `/eod/price`, account authorization and the Risk UI.

Engine pin: Alex's upstream `f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b`, unmodified. Profile
`current-cpu-v2`, contract [portfolio-current-cpu-v2](../../specs/YU18-risk-integration/contracts/portfolio-current-cpu-v2.md).
The engine's own `POST /runs` / `GET /runs/{run_id}` API, with `idempotency_key` and `keep`,
replaces the owned `jax-risk-local-cpu` service (`270a907a`, submission lookup and content
digest on `c3d7c6a`), which is retired. TraderX source is the YU18 `risk-portfolio-tools`
runtime override. No pricing implementation is copied.

## Reproduce

Tested on macOS arm64, Python 3.14.6, JAX/jaxlib 0.10.2 and
open-source-risk-engine 1.8.16.0, CPU only. Use the engine's pinned constraints.
Create a fresh private directory outside Git, and run from this TraderX checkout:

```sh
CPU_RUN="$(mktemp -d /private/tmp/traderx-cpu-XXXXXX)"
ENGINE=/path/to/clean/JAX_Risk_Engine   # Alex's checkout, at REV
REV=f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b
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

The runner checks the clean upstream checkout at the pin and every installed engine Python
and packaged evidence file against source before launching. Service and worker
import the installed wheel from the private run directory. It retains inputs,
SQLite state, compilation cache, service log, raw replies and report, and stops
its owned local process group on exit. `PASSED` requires nonempty completed
pricing, verified intake and the recovery/refusal checks below; a 202 is insufficient.

## Closed synthetic profile and checks

- Four-position bill/note long/short golden; two repeated and two heterogeneous
  100-position portfolios; one heterogeneous 1,000-position portfolio; one
  8-position, 8-path exposure run. Every run's configuration as run must be float64
  throughout with no per-product or per-trade override, and no warnings; the exposure
  run's precision report must also name CPU devices and the requested path count. An
  npv-only run carries no device report of its own.
- Each position's dirty NPV is checked independently as explicitly enumerated
  semiannual coupons and redemption discounted on the synthetic flat continuous
  ACT/365 curve. The original golden's signed accrued values are checked directly
  against elapsed coupon days. This restricted oracle is not general financial
  validation. The portfolio HTTP result does not expose an accrued-interest field.
- The exposure cube contains the three requested future dates. Profiles prepend
  t=0; intake verifies date/shape/coverage, finite EPE/ENE and the independent t=0
  values. Future exposure values have no independent model oracle here. This
  risk-neutral exposure simulation is not market-risk VaR/ES.
- Every request carries its submission identity as `idempotency_key` and sets `keep`.
  Recovery resends the same bytes: after an API/worker restart, and after an intentional
  consumer exit following a real POST reply, the resend returns the original run and
  the queue gains no row. Reformatted JSON, equal numbers and spelled-out defaults are
  the same body to the queue.
- A changed simulation seed or sample count under a run's key is refused by the queue
  (409, no run) and, polled by run ID, by the result's own echo of the configuration it
  ran; neither is accepted. Offline intake rejects a rewritten request even if its
  local hashes are updated.
- Conflicting submission identity, unknown fields, unsupported products, duplicate
  trade identity and missing market data must fail without adding runs. Offline
  intake must refuse corrupted custody. Queue bytes independently witness the
  submitted body, completion state, key and `keep`.

Every accepted run carries `financial_validation=false` and direct synthetic
provenance. Existing legacy fixture bytes, pins and `/eod` intake are unchanged.
Cloud/container/TPU qualification, sequenced portfolio export and production UI
remain separate milestones. Private runtime evidence stays outside Git.

## Individual portfolios

```sh
python3 scripts/risk-portfolio.py generate /private/tmp/my-new-corpus \
  --profile current-cpu-v2 --trades 100 --portfolios 2 --mode heterogeneous
python3 scripts/risk-portfolio.py validate /private/tmp/my-new-corpus
```

Generation's CLI default is `current-cpu-v2`; `--profile legacy-2df78cb` reproduces
the old wire body. The Python generator function retains its legacy default for
existing callers; select the current profile explicitly. Native schema validation
can use the clean upstream pin through `--engine-root`. Submission/poll/recovery and
offline inspection are provided by `scripts/risk-portfolio.py`.

## Previously accepted C8/EOD profile

C8 names the earlier Alex upstream revision `c8e21ea`; it is not a hardware type. The reconciled service/container version `e770f912` was approved on October 9 in the owned `jax-risk-container-upgrade` checkout, with measured image `sha256:80c885f8305067421a4c34af3eeef95ab1eca5a38376e9dcc689f8fe8bd5483d`. Its scoped container acceptance covers durable submission/recovery behavior and selected synthetic CPU pricing; it does not establish general financial correctness or production deployment. See [the acceptance record](../../issues/risk-integration/resolved/ri16-upstream-c8-upgrade.md) and [LR03](../../issues/risk-integration/live-rig/lr-03-risk-container-pipeline.md).

The older `/eod/price` TraderX adapter and its explicit pins remain unchanged. The current profile runs Alex's `f9cc4c1` unmodified and has a real local installed CPU proof, without new container qualification or sequenced TraderX exporter/account/run lineage. Alex is the engine source of truth: follow his latest upstream and reconcile our integration changes, as specified in the [upstream policy](risk-engine-upstream-policy.md). The preserved C8 checkout records earlier acceptance; it is not a competing current engine. Existing EOD adapter pins require migration proof before their contract changes. The current local integration is [complete](../../issues/risk-integration/resolved/local-cpu-portfolio-integration-20261014.md); broad connected Stage B remains open.
