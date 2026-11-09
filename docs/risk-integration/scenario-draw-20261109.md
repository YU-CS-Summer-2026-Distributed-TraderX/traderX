# One scenario draw on every host: November 9

**What changed.** TraderX's engine fork now draws its Monte Carlo scenarios from the covariance's
symmetric square root (fork commit `4b2fd3a`, on `traderx/engine-perf`; the proof is pinned to that
branch's head, `acef170`). Before, the draw read the eigenvectors `np.linalg.eigh` returned, and those
are not unique where eigenvalues repeat. The book's covariance repeats them across its 50 equally
correlated equities, so one request gave another VaR on another machine.

**Why it matters.** A risk figure that depends on which machine drew the scenarios cannot be replayed
or audited. The engine's own account, with the alternatives it rejected, is the fork's
`docs/instruments/traderx-fork.md`, "One draw on every host".

## What moved

The distribution is the same. The scenarios are different ones, so every Monte Carlo figure moved
once, by sampling noise. Historical scenarios are untouched.

| Request | VaR99 before | VaR99 now | ES97.5 before | ES97.5 now |
|---|---|---|---|---|
| The VaR/ES proof, 17 trades, 1,024 scenarios | 373,857.16 | 381,036.79 (+1.9%) | 381,254.68 | 379,224.28 (−0.5%) |
| Every tenth trade of the book, 1,136 trades, 256 scenarios | 59,689,775.14 on the Mac, 59,274,718.60 on a pod | 63,607,091.37 on both (+6.6%, +7.3%) | — | 58,760,256.65 |

The book's own figures (73,560,770.06 at 256 scenarios and 77,759,354.61 at 1,024, in
[gke-risk-test-20261108.md](gke-risk-test-20261108.md)) are the old draw's, as a Linux pod drew it.
The book has not been run on the new draw.

## Checks

- **The proof** (`scripts/proofs/yu18-var-es-e2e.sh`, local, engine `acef170`, `modified=false`):
  PASSED. Every instrument has finite non-zero VaR and ES, and the four negative controls are refused
  with a 400.
- **ORE parity** (`ore_parity.py` on the proof's reply): 15 of 15 referenced trades agree, max 2.7e-5
  relative, the 3 bounds hold. Unchanged: today's values do not depend on the scenarios.
- **Across machines.** The same two requests on the Mac (Accelerate, CPU) and on an A100 pod
  (OpenBLAS, GPU, image `fork-4b2fd3a-20261109-cuda13`): VaR99 and ES97.5 agree within 1.9e-14, and
  the shocks within 2.0e-15. On the old draw the sample's VaR99 was 0.7% apart.
- **No expected value came from the engine.** The proof asserts finite non-zero figures and recomputes
  VaR from the engine's P&L by its own rank rule; it holds no Monte Carlo number. The fork's suite
  compares against closed forms and ORE. One ORE-parity sweep tolerance widened, for a 32-helper
  American 3.3e-10 of its notional from ORE in one new scenario: the fork's page records why that is
  ORE's side.

## Reproduce

```bash
S=<scratch>; git clone --no-checkout ~/dev/lmax/jax-risk-traderx $S/snap && git -C $S/snap -c core.autocrlf=false checkout acef170
cd $S/snap && PYTHONPATH=$S/snap JAX_PLATFORMS=cpu JAX_RISK_JOB_QUEUE=$S/q/jobs.sqlite3 python -m uvicorn engine.api.app:app --port 8772
RISK_ENGINE_ORIGIN=http://127.0.0.1:8772 scripts/proofs/yu18-var-es-e2e.sh "$(mktemp -d /private/tmp/var-es.XXXXXX)/out"
```

`acef170` is on `traderx/engine-perf`; `traderx/engine-fork` is at `3e1d1d9` until it is fast-forwarded.
