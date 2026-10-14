# LOCAL-CPU-PORTFOLIO-INTEGRATION-20261014

Updated: 2026-10-14. Status: reviewed; awaiting integration approval. Author: chat
`01a138ba-f509-7f50-8884-3d0b8fd3a5b4`; coordinator:
`01a12b5c-4c93-7e62-b90a-3e56223dee78`.

Parents: RI03/14/15/16; portfolio-generator component; LR03. Human assignment:
update integration for Alex's latest engine, generate portfolios and get the
local CPU loop working end to end. No cloud, Git push or canonical integration.

## Ownership and candidates

TraderX checkout `/Users/yaakov/dev/lmax/traderX-risk-local-cpu`, branch
`codex/risk-local-cpu`, accepted base `c2b234af639fb6ffbcf67e84f39fe0bb0af2266e`.
Operative owner: YU18 `generation/runtime-overrides/risk-portfolio-tools`, composed
by the existing YU18 renderer. New CLI, contract and component docs stay in this
task. Canonical routing/index is coordinator-owned. CLAIM:
`20261014T130239Z-local-cpu-claim-08e36587.txt`; own status
`coordination/eod-integration/status/local-cpu-portfolio.txt` in the parent workspace.

External numerical base `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`; isolated
engine checkout `/Users/yaakov/dev/lmax/jax-risk-local-cpu`, branch
`codex/risk-local-cpu-engine`, current candidate `270a907a313bc0184ddcc7cda2e50904d103fce3` (prior `2003ae82` superseded).
This carries portfolio identity/recovery controls into current routes while
retaining current job kinds/artifact support. Numerical kernels and `/eod`
sources match upstream. It is a separate local candidate, not adoption into the
original Alex checkout or replacement of accepted C8 container/EOD `e770f912`.

Review target: scoped TraderX integration into `traderX-risk-integration`, with
the explicit external candidate dependency above. Separate engine adoption
target remains a coordinator/human decision. No canonical source incorporation
or original/accepted-engine write was performed. Integration approval pending.

## Implementation and observed proof

- Current profile removes retired request fields and binds manifest/profile and
  execution identity. Legacy fixture bytes, manifest profile and EOD pins remain.
- Current row results use `/jobs`; POST-once and GET recovery retain exact raw
  custody. Offline intake revalidates hashes, binding, coverage/order, restricted
  cashflow NPVs, sum, CPU/float64 precision and selected exposure grids.
- Actual installed CPU HTTP/subprocess-worker loop: seven normal portfolios,
  1,412 positions, including heterogeneous 1,000-position base pricing and an
  eight-position/eight-path exposure case. One further four-position job recovered
  after an actual consumer exit 75 immediately following POST; recovery made zero
  POSTs. Eight executed jobs and 1,416 positions total.
- Every selected synthetic dirty NPV agrees with independently enumerated regular
  coupon/redemption cashflows under the assumed flat curve; golden accrued values
  independently checked. Future exposure values have no model oracle here.
- Restart recovers the same completed job. Real 409/422/400 refusals add no jobs;
  offline corruption is rejected. Independent SQLite request bytes and persisted
  execution contracts match. Both owned process groups are absent and the service
  port refuses connections after cleanup.
- 146 focused engine source tests; 43 tests against the installed package; 24
  source and 24 generated client tests; 15 source/generated module files identical;
  native current-schema proof on 100 heterogeneous positions; full YU18 composition,
  component check, all four repository gates and whitespace check pass.

The first exposure validator assumed a t=0 cube column and failed closed. The
actual current API's cube contains only future dates, and exposure profiles
prepend t=0. Corrected checks and a discriminating unit case now cover this.
Initial failed evidence is preserved alongside successful loop-02 and final proof.

## Evidence and review

Durable private evidence:
`/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/local-cpu-portfolio-20261014/`.
`summary.json`, `SHA256SUMS.json`, final report/raw custody/SQLite state,
independent cleanup verification and test/generation logs are retained. Compilation
caches are omitted from the durable copy; live temporary environment is under
`/private/tmp/traderx-local-cpu-20261014/`.

Coordinator independent re-review passed for runtime `5c9c31a0` and engine
`270a907a`. Coordinator repeated all27 client tests, the changed-seed recovery
and explicit-job refusals, exact long-decimal/bool digest controls, offline
checks of7 saved real jobs/1412 positions and98 installed/source file hashes.
Author148/45 engine test and actual8-job proof claims remain separately attributed. Required local proof for this direct synthetic profile has passed.
LR03 Stage B remains open for sequenced TraderX exporter/account-bound connected
intake/UI; no broad LR closure is requested. Local processes are not a container
qualification. General financial, market-risk VaR/ES, account authorization,
production UI, fleet/GPU/TPU and cloud acceptance remain outside this task.

## Integration and closeout

Self-review correction: initial runner used the wrong environment variable for
the unused EOD router's import-time directory initialization. The current runner
sets `JAX_EOD_STORE_ROOT` to the private run root and asserts it remains empty.
The corrected `loop-isolated` proof passes all eight jobs/recovery/refusal checks;
independent cleanup confirms groups 8078/8109 absent and port 56209 refused. Read-only
inspection found zero files in the default EOD store; no EOD endpoint was invoked
or shared-store cleanup attempted. The raw-reply budget was also clarified to
exclude separately bounded retained request/result copies.

Runtime correction commits: `6154f5ee` and `13ce5044`, following initial TraderX
candidate `45e32ec9`. Corrected proof/source and generated client suites each pass
24 tests, with all 15 module files byte-identical. Correction evidence is under
the durable root's `corrections-13ce5044/` directory.

Candidate review and explicit scope/target approval pending. Task is not done;
canonical/original-engine integration, archival and broader parent closure have
not occurred. See the local CPU guide for a reproducible isolated run.

## Coordinator correction — complete request binding

Coordinator independently reproduced a blocker in the initial transport: changing
simulation seed could recover an old job/result because identity witnesses did
not bind the consumer's supplied request content. Initial candidates are not
accepted for integration. This finding is distinct from financial-oracle checks.

Engine `270a907a313bc0184ddcc7cda2e50904d103fce3` adds the v2 transport's
`request_content_sha256` to admission, submission lookup and every job state,
computed from persisted complete JSON content, kind and execution contract.
Tagged exact-decimal canonicalization preserves distinct numeric values and
boolean/number types while normalizing formatting/key order/equal numbers.
Capabilities name the algorithm. Semantic/default-expanded idempotent admission
remains separate; different content snapshots are conservatively refused.

Current client and offline intake verify this digest against retained request and
service witnesses before accepting recovered or explicit jobs. Actual corrected
`loop-v2` passes eight executed jobs/1,416 positions, changed seed and sample count
refusal on both GET recovery and explicit-job paths (zero POST/acceptance),
equivalent-content GET recovery, explicit/default semantic POST equivalence,
restart/lost-reply recovery, offline rewritten-request refusal and prior controls.
The isolated unused EOD store remains empty. Revised evidence and nonzero tests
are delivered for independent coordinator re-review; no self-acceptance or
integration is claimed. Engine source tests148, installed tests45, source and
generated client tests27 each. Current runtime module contains16 matching files.

## Reviewed delivery — current runtime

Coordinator confirmed the content-binding blocker resolved for TraderX runtime
`5c9c31a0cbd6485f913556ab699b65aa21048c8e` and engine
`270a907a313bc0184ddcc7cda2e50904d103fce3`. Final author exact-HEAD engine148,
installed45, source/generated client27 each,16-file parity and four gates pass.
Immutable final evidence:
`coordination/eod-integration/review-evidence/local-cpu-portfolio-20261014/recovery-v2-270a907/`
in the parent workspace (`summary.json`,195-file hash manifest, raw proof and logs).
Coordinator evidence was supplied at
`/var/folders/cy/w468zcxs19j6vl8337g248n40000gn/T/local-cpu-v2-review-blych27r`;
coordinator owns its durable copy and canonical routing updates.

Integration remains pending direct user scope/target approval. Canonical advanced
independently to `00ad904f` through RI30 work and retains this task's base ancestry;
this lane made no canonical changes. Recommended adoption is a separate owned
local CPU profile using the isolated engine branch, preserving the older accepted
C8/EOD target. Adopting into that older target requires separate full reconciliation;
original Alex adoption is not authorized by this local delivery. Task is reviewed,
not marked done or archived. This final metadata update changes no runtime.
