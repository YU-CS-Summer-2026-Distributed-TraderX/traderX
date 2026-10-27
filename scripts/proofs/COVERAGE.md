# Proof coverage against the specs

Audited 2026-10-27 against the spec packs on `traderX-risk-integration` at `b28fd9f3` (state
`YU18-risk-integration` and every YU state it carries). Written **black-box, from the specs only**:
spec prose, ADRs, contracts, data models, messaging maps, `quickstart.md`, `docs/` and the existing
proof scripts. No implementation source was read (`generated/`, `src/`, `runtime-overrides/`).
Where a spec was too vague to assert, that is listed as a spec gap below, not resolved from code.

## How to read this

Each requirement ID in a state pack is one row, in one class:

| Class | Meaning | Counted in coverage |
|---|---|---|
| `LIVE` | runtime behaviour a live proof can assert at the effect end | yes |
| `EXT` | live, but exercised by a script outside `scripts/proofs/` (`scripts/ri07/order-types-live.py`, 31 console-path cases) | listed, not counted |
| `CI` | build, static, offline-tool, generation, perf or traceability requirement; owned by unit tests, `test-state-*`, `test_prepare`, bench | no |
| `RET` | retired by yaakov (the TAQ replay path, 2026-10-27): skipped, not missing | no |

A `LIVE` row is covered when a proof drives the deployed system and asserts the effect (SQL row,
read model, member state, extract file, log line in Loki), not a status code alone. YU01 (the
single-node sequencer) and YU11 (dual-transport replication) are superseded by the YU12 Aeron
Cluster tier; their runtime claims are counted where the cluster tier inherits them. SC-* and NFR-*
rows are success criteria / non-functional budgets and are owned by CI and bench; they are not in
the counts. "Covered" is per requirement ID, not per clause: a proof that exercises one arm of a
multi-clause FR covers the row (the Note column says PARTIAL where that matters).

## Coverage before → after

| State | Live-provable | Covered before | Covered after | Live script outside proofs/ | CI/offline/static |
|---|---|---|---|---|---|
| YU02 | 1 | 1 | 1 | 0 | 8 |
| YU03 | 19 | 14 | 14 | 0 | 9 |
| YU04 | 3 | 3 | 3 | 0 | 4 |
| YU05 | 18 | 18 | 18 | 0 | 1 |
| YU06 | 17 | 17 | 17 | 0 | 2 |
| YU07 | 3 | 0 | 0 | 0 | 12 |
| YU08 | 9 | 6 | 6 | 0 | 0 |
| YU09 | 0 | 0 | 0 | 0 | 10 |
| YU10 | 13 | 9 | 9 | 0 | 0 |
| YU11 | 0 | 0 | 0 | 0 | 27 |
| YU12 | 14 | 13 | 13 | 0 | 3 |
| YU13 | 16 | 14 | 14 | 0 | 1 |
| YU14 | 5 | 5 | 5 | 0 | 2 |
| YU15 | 19 | 17 | 17 | 0 | 0 |
| YU16 | 26 | 17 | 17 | 0 | 40 |
| YU17 | 35 | 34 | 34 | 0 | 8 |
| YU18 | 106 | 4 | 26 | 41 | 190 |
| **all** | **304** | **172 (57%)** | **194 (64%)** | 41 | 317 |

Coverage means a proof exists that asserts the row at the effect end; whether it passed on the tip is the
run table below — three covered rows are covered by proofs that FAIL, and those failures are suspects.

YU18 went from **4 of 106** live-provable requirements under a proof to **26**, and the whole carried
lineage from **172 of 304 (57%)** to **194 of 304 (64%)**. The 41 order-type requirements (FR-OT) are
live-tested by `scripts/ri07/order-types-live.py` through the console path and are not duplicated here.
The 317 CI rows are build, generation, admission, offline-tool and perf requirements; they belong to
`test-state-*`, unit suites, `test_prepare.py` and the bench, and a live proof would add nothing.

## Proofs added by this audit

All share [`lib-yu18.sh`](lib-yu18.sh) (explicit `CTX`/`NS`, credentials from the rig's own Secrets,
self-owned port-forwards). Each asserts at the effect end and carries a control that fails it.

| Proof | Requirements | Effect end it reads | The control that can fail it |
|---|---|---|---|
| `yu18-gateway-auth-boundary.sh` | FR-ERH-S1, S2, S4, S6, F4; FR-MAR01/02; FR-IMRG08/30 | every member's `/symbols`; `GET /accounts/{id}/orders` | the operator `/seed` must make the ticker appear (the reading can see a registration); the entitled bearer must place and be listed |
| `yu18-cluster-ingress-auth.sh` | ADR-079 §4 | proof-client `CONNECTED`; `traderx_cluster_ingress_rejected`; member logs | same image/endpoints/placement with only the secret changed must be seen retrying and refused |
| `yu18-projection-effect-end.sh` | FR-DRS-02, FR-DRS-04, FR-AC14 | members' trade counters; SQL `trades`, `positions` vs the signed sum of the rows; `/v2/projection-feed` | `NEGATIVE=1`: a cross booked with the projection down must be replayed or counted missing |
| `yu18-jetstream-feed.sh` | FR-DRS-02, -05, -07, -08; SC-DRS-01/02 | `/jsz` stream config; SQL positions after a SIGKILL; `projection_cursor`; the order view vs `orderbook` | engine trade count is the independent tally; `orderbook` must have NO row for the view's order |
| `yu18-member-runtime.sh` | FR-GMR-01, -03, -04, -05 | pod volumes and PVC phase; `/data` vs boot-disk device; `/proc` of the JVM; Loki; the kubelet's terminated state | exit code must be exactly 143 (137 = killed at the grace deadline) |
| `yu18-console-sessions.sh` | FR-CS-01, -04, -05; FR-ERH-C1 | `Cache-Control`; `/auth/me` per cookie jar; read model | signed-out writes must book nothing; signing one jar out must leave the other in |
| `yu18-legacy-trade-bearer.sh` | FR-ERH-C2 | members' trade counters; SQL `trades` | the bare gateway `/trades` must be 401; a qty-0 ticket must book nothing |
| `yu18-extract-off-projection.sh` | ADR-079 §5; FR-RXT01; FR-EOD21/22; FR-EB13 | the extract's positions file; the `.ready.json` receipt | SQL must NOT hold the cross (else the run cannot tell the sources apart) |

## Existing proofs changed (proof bugs, not behaviour)

| Script | Change | Why |
|---|---|---|
| `yu03-risk-proof.sh` | sends an admin bearer; an `{"error":…}` body is read as ERROR, not NEW | since ADR-082 S1 every order answered 401 and the parser read "no reason" as NEW: 5 of 13 controls "failed" on orders the venue never saw |
| `yu13-readmodel-effect-end.sh`, `yu13-clordid-suppression.sh`, `yu13-cancel-ingress.sh`, `yu13-otel-trace-join.sh`, `yu13-otel-reject-trace-log-join.sh`, `yu12-gke-recovery.sh`, `yu12-gke-failover-transparency.sh`, `yu12-gke-cross-epoch-idreuse.sh`, `yu13-gke-replace-proof.sh`, `yu12-gke-restore-from-gcs.sh`, `seed-option-chain.sh` | gateway calls carry the `lib-gateway-auth.sh` bearer | 401 since ADR-082 S1 (`/seed`: "operator credentials required") |
| `lib-gateway-auth.sh` | reads `auth-secrets/dev-token-master-secret` before the kind literal | the CompSci/GKE profile generates the secret; the literal fails as a bare 401 |
| `yu05-common.sh`, `recon-proof-subject.py` | honour `NS` instead of a hard-coded `-n traderx` | on `traderx-demo` that is production; the recon helper pauses trade-processor and resets a recon checkpoint |
| `scripts/yu15/seed-proof-fixtures.sh` | `PA` added to the throwaway-ticker sweep | the yu18 proofs mint `PA<digits>`; their positions otherwise halt the EOD P&L (FR-EOD32) for later proofs |
| `yu18-networkpolicy-enforced.sh` | proof client limited to one probe order; whole-log grep | the client sequences a real resting order every 10 ms (3,285 resting AAL buys in 60 s on the audit rig) and the rollout doc pointed it at production; `logs \| grep -q` under pipefail read a long log as "never tried" |
| `lib-gke-replay-gates.sh` | explicit `REPLAY_RETIRED=1` waiver, printed on every gate it passes | the five GKE recovery/failover proofs refuse without the TAQ tape, retired 2026-10-27 |

## Ranked gap list (live-provable, still uncovered)

| Rank | Risk class | State | Requirement | Why uncovered |
|---|---|---|---|---|
| 1 | money/positions | YU13 | `FR-LOB04` | market order w/o limit; tick never fills: no live arm (ri07 covers MARKET) |
| 2 | money/positions | YU13 | `FR-LOB05` | market order w/o limit; tick never fills: no live arm (ri07 covers MARKET) |
| 3 | money/positions | YU16 | `FR-CDM07` | ETFs tradable through the inherited paths: no live proof |
| 4 | money/positions | YU16 | `FR-CDM08` | ETFs tradable through the inherited paths: no live proof |
| 5 | money/positions | YU16 | `FR-CDM23` | Rejected trade state + fail-closed Treasury metadata: no live arm |
| 6 | money/positions | YU16 | `FR-CDM24` | Rejected trade state + fail-closed Treasury metadata: no live arm |
| 7 | money/positions | YU17 | `FR-OTC09` | MAX_CONTRACTS refusal: no live arm |
| 8 | money/positions | YU18 | `FR-APR01` | disabled by default; disposable profile only |
| 9 | money/positions | YU18 | `FR-APR02` | disabled by default; disposable profile only |
| 10 | money/positions | YU18 | `FR-APR03` | disabled by default; disposable profile only |
| 11 | money/positions | YU18 | `FR-APR04` | disabled by default; disposable profile only |
| 12 | money/positions | YU18 | `FR-APR05` | disabled by default; disposable profile only |
| 13 | money/positions | YU18 | `FR-APR06` | disabled by default; disposable profile only |
| 14 | money/positions | YU18 | `FR-APR07` | disabled by default; disposable profile only |
| 15 | money/positions | YU18 | `FR-APR08` | disabled by default; disposable profile only |
| 16 | money/positions | YU18 | `FR-APR09` | disabled by default; disposable profile only |
| 17 | money/positions | YU18 | `FR-APR10` | disabled by default; disposable profile only |
| 18 | money/positions | YU18 | `FR-APR11` | disabled by default; disposable profile only |
| 19 | money/positions | YU18 | `FR-DRS-06` | BRIDGE_PARTITIONS>1 on members: no live proof (needs a member env change) |
| 20 | money/positions | YU18 | `FR-ER01` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 21 | money/positions | YU18 | `FR-ER02` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 22 | money/positions | YU18 | `FR-ER03` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 23 | money/positions | YU18 | `FR-ER04` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 24 | money/positions | YU18 | `FR-ER05` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 25 | money/positions | YU18 | `FR-ER06` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 26 | money/positions | YU18 | `FR-ER07` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 27 | money/positions | YU18 | `FR-ER08` | operator recovery: ri03-recovery.py (local), no proofs/ script |
| 28 | risk decisions | YU03 | `FR-IMRG07` | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| 29 | risk decisions | YU03 | `FR-IMRG09` | stale-price classification: no live arm |
| 30 | risk decisions | YU03 | `FR-IMRG19` | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| 31 | risk decisions | YU03 | `FR-IMRG26` | table exhaustion -> CAPACITY: no live arm (ERH-F7 same) |
| 32 | risk decisions | YU03 | `FR-IMRG44` | UI shows rejection reason: no UI proof |
| 33 | risk decisions | YU08 | `FR-AE03` | VWAP weighting / empty-profile fallback: no live arm |
| 34 | risk decisions | YU08 | `FR-AE09` | VWAP weighting / empty-profile fallback: no live arm |
| 35 | risk decisions | YU18 | `FR-ERH-F1` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 36 | risk decisions | YU18 | `FR-ERH-F10` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 37 | risk decisions | YU18 | `FR-ERH-F2` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 38 | risk decisions | YU18 | `FR-ERH-F3` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 39 | risk decisions | YU18 | `FR-ERH-F5` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 40 | risk decisions | YU18 | `FR-ERH-F6` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 41 | risk decisions | YU18 | `FR-ERH-F7` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 42 | risk decisions | YU18 | `FR-ERH-F9` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 43 | risk decisions | YU18 | `FR-MAR03` | readout field set: partially read by the new proof |
| 44 | risk decisions | YU18 | `FR-RCM01` | diagnostic metric/readout shape: no live proof (unit) |
| 45 | risk decisions | YU18 | `FR-RCM02` | diagnostic metric/readout shape: no live proof (unit) |
| 46 | risk decisions | YU18 | `FR-RCM03` | diagnostic metric/readout shape: no live proof (unit) |
| 47 | recovery/failover | YU08 | `FR-AE08` | crash-resume replay from JetStream: no live arm (SC-AE06) |
| 48 | recovery/failover | YU10 | `FR-FIX10` | duplicate ClOrdID per session: no live arm (REST twin is yu13-clordid-suppression) |
| 49 | recovery/failover | YU10 | `FR-FIX11` | session persistence / ambiguous-outcome classes: no live arm |
| 50 | recovery/failover | YU12 | `FR-AC12` | partition minority never commits: no live arm |
| 51 | recovery/failover | YU18 | `FR-ERH-F11` | strict replay on a purged archive / ClOrdID ledger bound: no live arm |
| 52 | recovery/failover | YU18 | `FR-ERH-F8` | strict replay on a purged archive / ClOrdID ledger bound: no live arm |
| 53 | recovery/failover | YU18 | `FR-RD01` | diagnostic metric/readout shape: no live proof (unit) |
| 54 | recovery/failover | YU18 | `FR-RD02` | diagnostic metric/readout shape: no live proof (unit) |
| 55 | recovery/failover | YU18 | `FR-RD03` | diagnostic metric/readout shape: no live proof (unit) |
| 56 | recovery/failover | YU18 | `FR-RD04` | diagnostic metric/readout shape: no live proof (unit) |
| 57 | EOD/extract | YU15 | `FR-RXT06` | truncated cut refused; unmarkable row: no live arm (unit) |
| 58 | EOD/extract | YU15 | `FR-RXT09` | truncated cut refused; unmarkable row: no live arm (unit) |
| 59 | EOD/extract | YU18 | `FR-EB29` | console EOD status distinctions: no live proof |
| 60 | security/auth | YU10 | `FR-FIX04` | OrderStatusRequest: no live arm |
| 61 | security/auth | YU10 | `FR-FIX12` | session persistence / ambiguous-outcome classes: no live arm |
| 62 | security/auth | YU18 | `FR-CS-02` | hosted Desk: not in the CompSci side-namespace render |
| 63 | security/auth | YU18 | `FR-CS-03` | hosted Desk: not in the CompSci side-namespace render |
| 64 | security/auth | YU18 | `FR-ERH-S3` | FIX_SESSION_AUTH: CompSci profile leaves it unset (acceptor logs INSECURE); no live proof |
| 65 | security/auth | YU18 | `FR-ERH-S5` | refuse-to-start on dev secret: no live arm |
| 66 | UI/ops | YU07 | `FR-TS12` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 67 | UI/ops | YU07 | `FR-TS13` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 68 | UI/ops | YU07 | `FR-TS14` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 69 | UI/ops | YU16 | `FR-CDM01` | /instruments and /stocks response shapes: no live proof |
| 70 | UI/ops | YU16 | `FR-CDM02` | /instruments and /stocks response shapes: no live proof |
| 71 | UI/ops | YU16 | `FR-CDM03` | /instruments and /stocks response shapes: no live proof |
| 72 | UI/ops | YU16 | `FR-CDM04` | /instruments and /stocks response shapes: no live proof |
| 73 | UI/ops | YU16 | `FR-CDM09` | /instruments and /stocks response shapes: no live proof |
| 74 | UI/ops | YU18 | `FR-AM01` | diagnostic metric/readout shape: no live proof (unit) |
| 75 | UI/ops | YU18 | `FR-AM02` | diagnostic metric/readout shape: no live proof (unit) |
| 76 | UI/ops | YU18 | `FR-AM03` | diagnostic metric/readout shape: no live proof (unit) |
| 77 | UI/ops | YU18 | `FR-AM04` | diagnostic metric/readout shape: no live proof (unit) |
| 78 | UI/ops | YU18 | `FR-AM05` | diagnostic metric/readout shape: no live proof (unit) |
| 79 | UI/ops | YU18 | `FR-AM06` | diagnostic metric/readout shape: no live proof (unit) |
| 80 | UI/ops | YU18 | `FR-CUI-01` | combined desk UI: no live proof |
| 81 | UI/ops | YU18 | `FR-CUI-02` | combined desk UI: no live proof |
| 82 | UI/ops | YU18 | `FR-CUI-03` | combined desk UI: no live proof |
| 83 | UI/ops | YU18 | `FR-CUI-04` | combined desk UI: no live proof |
| 84 | UI/ops | YU18 | `FR-CUI-05` | combined desk UI: no live proof |
| 85 | UI/ops | YU18 | `FR-CUI-06` | combined desk UI: no live proof |
| 86 | UI/ops | YU18 | `FR-CUI-07` | combined desk UI: no live proof |
| 87 | UI/ops | YU18 | `FR-CUI-08` | combined desk UI: no live proof |
| 88 | UI/ops | YU18 | `FR-CUI-09` | combined desk UI: no live proof |
| 89 | UI/ops | YU18 | `FR-CUI-10` | combined desk UI: no live proof |
| 90 | UI/ops | YU18 | `FR-CUI-11` | combined desk UI: no live proof |
| 91 | UI/ops | YU18 | `FR-CUI-12` | combined desk UI: no live proof |
| 92 | UI/ops | YU18 | `FR-CUI-13` | combined desk UI: no live proof |
| 93 | UI/ops | YU18 | `FR-CUI-14` | combined desk UI: no live proof |
| 94 | UI/ops | YU18 | `FR-CUI-15` | combined desk UI: no live proof |
| 95 | UI/ops | YU18 | `FR-CUI-16` | combined desk UI: no live proof |
| 96 | UI/ops | YU18 | `FR-CUI-17` | combined desk UI: no live proof |
| 97 | UI/ops | YU18 | `FR-CUI-18` | combined desk UI: no live proof |
| 98 | UI/ops | YU18 | `FR-CUI-19` | combined desk UI: no live proof |
| 99 | UI/ops | YU18 | `FR-CUI-20` | combined desk UI: no live proof |
| 100 | UI/ops | YU18 | `FR-CUI-21` | combined desk UI: no live proof |
| 101 | UI/ops | YU18 | `FR-MUI01` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 102 | UI/ops | YU18 | `FR-MUI02` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 103 | UI/ops | YU18 | `FR-MUI03` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 104 | UI/ops | YU18 | `FR-MUI04` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 105 | UI/ops | YU18 | `FR-MUI05` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 106 | UI/ops | YU18 | `FR-MUI06` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 107 | UI/ops | YU18 | `FR-MUI07` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 108 | UI/ops | YU18 | `FR-MUI08` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 109 | UI/ops | YU18 | `FR-MUI09` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| 110 | UI/ops | YU18 | `FR-MUI10` | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |

## Findings from the live run

Measured on `b28fd9f3`. Since then the tip has moved (`334e9c45`), and three commits there claim to address
findings below; **none of them is re-verified by this audit**: `334e9c45` (member SIGTERM forwarded through
the launch shell: suspect 1), `6ef26b36` (receipt publish specified as ADR-083: spec gap 9; recon
holds a core-mode side for the projection grace), `a961d89a` (FIX logon authenticated by default on GKE:
FR-ERH-S3). Re-run `yu18-member-runtime.sh DESTRUCTIVE=1` on an image of `334e9c45` to close gap 1.

### Suspects (the spec and the system disagree; cause not judged here)

Classified blind: each names the spec, what the spec leads a tester to expect, what was observed, and the evidence. Code-aware triage is the coordinator's.

1. **FR-GMR-04 / ADR-081 §3 — a member does not exit 143 on SIGTERM on the GKE tier.** The member
   container's PID 1 is `/bin/sh -c "java -cp … ClusterNodeMain & wait $!"`. `/proc/1/status` SigCgt is
   `0x10002` (SIGINT, SIGCHLD only): as PID 1 the shell neither handles nor forwards SIGTERM, so the
   kernel drops it and the JVM never sees it. Measured on `traderx-proofaudit` (image `cluster-node:pa-b28fd9f3`,
   digest `sha256:ed1e565f…`): `kubectl delete pod order-matcher-cluster-2` took 12 s and the watch saw
   `terminated.exitCode=137` (SIGKILL at the 10 s grace deadline), finishedAt 2026-10-27T19:37:28Z. A
   hand-sent `kill -TERM 1` was ignored for 120 s (restartCount stayed 0). SC-GMR-01's unit test runs the
   JVM directly, so it passes while the deployed command defeats it. Proof: `yu18-member-runtime.sh` step 04
   (run 4: exit 137 at exactly the 10 s grace). Interface evidence: the rendered StatefulSet's command is
   `["/bin/sh","-c","java -cp … ClusterNodeMain & wait $!"]`.

2. **YU04 durable control feeds are not wired on the CompSci GKE tier.** `yu04-live-delta`: a ticker POSTed
   to reference-data advances the source watermark (543 → 544, count 533 → 534) but the gateway replica never
   sees it (TIMEOUT 10 s); `yu04-offline-catchup`: not seen 90 s after the replica is ready. The YU18 GKE
   render (prepare.py) carries no control-feed subscriber; ADR-082 S4 still names "the reference-data control
   feed" as a registration path, and FR-IMRG04/05 (YU04) are inherited. On this tier a security added to
   reference-data reaches the venue only when an operator re-runs `initialize-reference-data.py`. Either the
   tier must carry the feed or the spec must scope FR-IMRG04/05 out of it. Same root makes
   `yu15-option-persistence` step 3b time out waiting for `CONTROL-FEED applied`. Interface evidence: the
   kind gateway manifests (`specs/YU15…YU17/generation/kubernetes/cluster/gateway.yaml`) set
   `CONTROL_FEED_SUBSCRIBER`; the GKE gateway the CompSci render uses carries no CONTROL_FEED* env.

3. **FR-DRS-02 — trade sides lost across a projection outage are not counted.** Default tier (core feed,
   rows mode). `yu18-projection-effect-end.sh NEGATIVE=1`: trade-processor scaled to 0, a cross booked by
   the engine (members +2), trade-processor back, a third cross. SQL holds 4 of the 6 sides on the ticker
   (positions short by the lost fill) and `/v2/projection-feed` reports `missingTradeSides 0`,
   `firstTradeSeqSinceStart 18056`, `lastTradeSeq 18057`: contiguity is judged from the first sequence the
   new process sees, so a loss across a restart is invisible to the one counter the spec says counts it.
   FR-DRS-02 is unconditional ("SHALL count trade sides that never reached it"). If the intended scope is
   "within one process lifetime", the spec must say so, and then nothing on this tier reports the loss
   except recon (which needs a run descriptor on CompSci, spec gap 12). Either way it is money: positions
   are wrong with every health signal green.

4. **Recon's exact-subject control on the CompSci profile (YU05 FR-PTC04/05; YU18 recovery-identity
   FR-RI05/07).** Expected: `yu05-recon`'s scoped subject control runs on a YU18 tier. Observed: the gateway's
   `GET /run/status` answers `409 {"error":"RUN_DESCRIPTOR_NOT_CONFIGURED"}`, so the control refuses. The spec
   does not say whether the CompSci profile must carry a run descriptor; if it need not, this is a spec gap.
5. **Trace identity (YU13 ADR-060, FR-TR01–03).** Expected: one trace per order, its id a pure function of the
   order's idempotency key. Observed: three gateway-rooted `order` traces exist for the proof's three orders
   (Tempo search, 20:19 UTC: `fcc936c9…`, `52af02c8…`, `5194932a…`), but none under the ids the proof derives
   (404 for each, in Tempo and Loki). ADR-060 does not define the key, so whether the system or the proof
   has drifted cannot be decided black-box (spec gap 11).
6. **Leader election timing under a leader kill (YU12 FR-AC11, NFR-AC03).** Expected: a new leader within
   the failover budget. Observed: `yu17-keyed-ack-correlation` round 3 found "no leader" after two leader kills
   in a row; the cluster was healthy minutes later (one leader, applied 31454 on all three). Possibly the same
   10 s SIGKILL window as suspect 1; not judged here.

### Spec gaps (the spec is silent or self-contradictory; the proof had to pick, and says so)

1. **S4 / FR-ERH-F4 name no response for an unknown ticker.** Live: `POST /orders` naming a never-registered
   ticker answers `504 {"error":"no committed ack"}` in 0 s and consumes no order ref (the next accepted order
   took ref 3). That is a definitive pre-sequencing refusal reported with the ambiguous-outcome status of
   ADR-037: a FIX/REST client following that contract must treat the order as possibly booked. The spec should
   name a definitive status (e.g. 422 `UNKNOWN_SECURITY`, as a collar refusal answers 422 `PRICE_COLLAR`).
2. **FR-DRS-05 vs FR-DRS-08: "one durable consumer per owned partition" is not what snapshot mode runs.**
   With `TRADE_PROJECTION_MODE=snapshot` every `TRADERX_FEED` consumer is ephemeral (`durable_name` null,
   `inactive_threshold` 300 s; trades consumers start `by_start_sequence` from the `projection_cursor`, orders
   `by_start_time` for `ORDER_VIEW_REPLAY_SECONDS`). Each restart adds 2–3 ephemerals that linger 5 min (16 seen
   after four restarts). Replay correctness held (SC-DRS-01/02 PASS); the spec should say which mode owns a
   durable, and that snapshot mode replays from the cursor with ephemerals.
3. **FR-DRS-02 does not say over what window `missingTradeSides` counts.** `/v2/projection-feed` reports
   `firstTradeSeqSinceStart`, suggesting contiguity since process start; trade sides lost while the projection
   was down (core feed) may never be counted. `yu18-projection-effect-end.sh NEGATIVE=1` measures it (result in
   the run table).
4. **The REST order ack is not specified as a contract.** `kind` codes (1 accepted/rested, 2 rejected, 5
   canceled, …), the `{"orderRef","kind","reason"}` shape and the 422/504/403/401 mapping are spread over ADR
   prose and implementation-status notes; no `contracts/` file names them.
5. **Console login body and session-id field are unspecified.** FR-CS-01 says `/auth/login` returns a session id;
   it does not name the request fields (`user`, not `username`) or the response key (`session`). "Old-format
   cookies SHALL be refused" gives no format, so it cannot be asserted black-box.
6. **`/v2/projection-feed` response shape is unspecified** beyond "backlog, dropped and missing counts" (live:
   `trades.{mode,backlog,dropped,applied,missingTradeSides,…}`, `orders.{mode,persist,…}`).
7. **ADR-082 S1 vs the order read model.** `GET trade-processor /accounts/{id}/orders` answers 200 with no bearer
   (any account's orders). S1 scopes only the gateway's write routes and FR-PTC40 only YU05's endpoints, so no
   spec says whether read-model routes are authenticated. Not asserted either way; needs a decision.
8. **The batch-refusal granularity.** FR-ERH-S1 says every element of a batch is checked; live, one foreign
   element refuses the whole batch with 403 (own element not booked). The spec does not say which.
9. **Receipt publish (48655be8) has no spec.** `/health.bridges`, `traderx_bridge_ingest_rejected` and the
   receipt lanes are described only in `docs/engineering/downstream-at-400k.md`; no FR to prove.
10. **Gateway gen-ZGC (e201a1bc) has no spec row** (ADR-081 covers members only).
11. **ADR-060 never defines the idempotency-key function** the trace id is derived from ("set by the gateway
    from the ClOrdID"). `yu13-otel-trace-join` reimplemented it as FNV-1a(ClOrdID) from the code; on the tip
    the three orders produced three gateway-rooted traces in Tempo, but under ids FNV-1a(ClOrdID) does not
    predict (404 for each). The rollout doc names an unspecced YU18 change, "G1: idempotency key scoped by
    account". The derivation is the contract a reader needs to find a trace; it belongs in the spec.
12. **yu05-recon's exact-subject control needs a managed run descriptor** (also suspect 4). Live: gateway `/run/status` answers
    409 `RUN_DESCRIPTOR_NOT_CONFIGURED` on the CompSci profile, so the subject control refuses. Recon's own
    classification ran: 6,408 matched, 0 field mismatches, 5,608 "missing" — the trade sides
    `yu18-jetstream-feed` booked in snapshot mode, which by ADR-080 keeps only the last 100 fills per account
    in SQL and is out of recon's scope. Not a defect; the run order put a snapshot-mode proof before recon.

### Proof hazards / stale scripts found

- `yu18-networkpolicy-enforced.sh` (and `proof-client.yaml`): the "connect probe" is a canary that sequences a
  REAL resting order every `PROOF_INTERVAL_MS` (10 ms) — 3,285 resting AAL buys for account 11 in 60 s on the
  side rig, and the rollout doc told operators to run it against production `traderx`. Its `logs | grep -q`
  under `pipefail` also reads a long log as "never tried". Fixed (interval 10 min, whole-log grep). The
  manifest's image (`traderx-505400`) and `.traderx.svc` endpoints are stale for any other project/namespace.
- Seven proofs called the gateway without a bearer and fail 401 since ADR-082 S1: yu03-risk-proof (and its
  parser read the 401 body as NEW), yu13-readmodel-effect-end, yu13-clordid-suppression, yu13-cancel-ingress,
  yu13-otel-trace-join, yu12-gke-restore-from-gcs, seed-option-chain. Fixed (lib-gateway-auth).
- `yu05-common.sh` hard-coded `-n traderx` (on traderx-demo that is production). Fixed to honour NS.
- 53 proof/runner files default `NS=traderx`; on the traderx-demo context that default is production.
  Not changed wholesale (kind is their home); the live run used a kubectl guard that refuses `traderx`.
- `lib-gateway-auth.sh` defaulted the dev-token secret to the kind literal. Fixed: read auth-secrets first.
- The five `lib-gke-replay-gates` proofs (yu12-gke-*, yu13-gke-replace-proof) refuse without the TAQ tape,
  which is retired. Added an explicit `REPLAY_RETIRED=1` waiver that prints on every gate it passes.
- `recon-proof-subject.py` (run by yu05-recon) hard-coded `-n traderx` and pauses trade-processor and resets
  a recon checkpoint: on the traderx-demo context that is production. Fixed to honour NS.
- `yu13-otel-trace-join.sh` derives trace ids from a key function copied from the implementation; stale on the
  tip (spec gap 11).
- `yu13-otel-reject-trace-log-join.sh` seeded and ordered without credentials (401). Fixed.
- `yu15-option-persistence.sh` step 3b waits for a gateway `CONTROL-FEED applied` line; the CompSci profile
  registers symbols through `/risk/control` and has no control-feed subscriber, so it times out there (kind-only).

## Live run record (2026-10-27, 3:25–6:30 pm ET)

- **Rig**: GKE cluster `traderx-demo`, project `project-compsci-484414`, side namespace `traderx-proofaudit`,
  on pools created for the run and deleted after it: `pa-engine` (3× c4d-standard-8, taint
  `proofaudit=engine`) and `pa-support` (2× e2-standard-8, taint `proofaudit=support`). Production
  `traderx`, its pools and the shared `order-matcher-archive` StorageClass were not touched; every
  kubectl call went through a guard that refuses namespace `traderx`.
- **Build**: tip `b28fd9f3`, empty-root render with `SOURCE_DATE_EPOCH`, `build-images.py` on the
  throwaway VM `proofaudit-build-20261027` (deleted), 12 images pushed as `pa-b28fd9f3`
  (`cluster-node@sha256:ed1e565f…`). Candidate from `prepare.py` with `TRADERX_NAMESPACE`, retargeted to
  the audit pools (StorageClass dropped from the apply; console `NAMESPACE` env corrected — `prepare.py`
  leaves it `traderx`). `initialize-accounts.py --apply` then `initialize-reference-data.py --apply`
  (fence 1291 on all three members), then `scripts/yu15/seed-proof-fixtures.sh`.
- **Rig accommodations** (disposable rig, so the inherited suite could run unmodified): the DB user's
  password set to the kind default `traderx` with the pod-env password still accepted for `@localhost`
  (MariaDB OR-chained auth); `execution-algo-engine` scaled to 0 per the README. One self-inflicted
  fault: the first password change broke the DB readiness probe for ~15 min, voiding run 1 from
  yu05-recon onward; those proofs were re-run (run 2).
- **Env for the suite**: `CTX=pa NS=traderx-proofaudit`, secrets read from the rig's `auth-secrets`,
  the README's fixed forwards re-established before every proof; destructive block with
  `DESTRUCTIVE=1 EXPECT=after EXPECT_CTX=pa REPLAY_RETIRED=1 IMAGE=<the member image>`.
- **Raw logs** (outside Git): shared notes folder, `proof-audit-20261027/`.

### Results (latest run of each proof)

| Proof | Result | Run | Triage |
|---|---|---|---|
| `yu03-risk-proof` | PASS | run2 4s | PASS after a proof fix (bearer; error body no longer read as NEW). Run 1: 5/13 "failed" on 401s. |
| `yu05-auth-entitlements` | PASS | run1 5s |  |
| `yu15-option-persistence` | FAIL | run1 167s | SUSPECT 2: step 3b waits for a gateway `CONTROL-FEED applied` line; the CompSci GKE profile has no control-feed subscriber (suspect 2). Kind-only as written. |
| `yu05-settlement` | PASS | run1 6s |  |
| `yu13-clordid-suppression` | PASS | run1 23s | PASS after the bearer fix. |
| `yu10-fix-session` | PASS | run1 18s |  |
| `yu05-recon` | FAIL | run2 97s | SUSPECT 4 + a run-order proof bug of mine: classification ran (6,408 matched, 0 field mismatches); 5,608 "missing" are the snapshot-mode sides yu18-jetstream-feed booked earlier, out of recon scope by ADR-080; the exact-subject control refuses because `/run/status` is 409 `RUN_DESCRIPTOR_NOT_CONFIGURED` on this profile (suspect 4). Run 1 also hit an audit-rig DB-login fault of mine (fixed). |
| `yu13-readmodel-effect-end` | PASS | run3 21s | PASS after the bearer fix (run 2 failed on the audit rig's DB login, mine, fixed). |
| `yu05-regulatory-reproducible` | PASS | run2 4s |  |
| `yu04-live-delta` | FAIL | run2 19s | SUSPECT 2: source watermark 543 -> 544, gateway replica never sees the ticker (YU04 feed not wired on the CompSci GKE tier). |
| `yu04-offline-catchup` | FAIL | run2 154s | SUSPECT 2 (same root): not seen 90 s after the replica is ready. |
| `yu13-otel-trace-join` | FAIL | run2 28s | SUSPECT 5: three gateway-rooted traces exist for the three orders, under ids the proof's FNV-1a(ClOrdID) key does not predict; ADR-060 never defines the key and YU18 scoped it by account without a spec. |
| `yu13-otel-reject-trace-log-join` | FAIL | run3 329s | SUSPECT 5 (derived id 404 in Tempo and Loki), plus a PROOF BUG not fixed (rig torn down): it signs in to Grafana with default credentials, 401 here. Seed/order 401s fixed (proof bug). |
| `yu06-quality-gate` | PASS | run2 47s |  |
| `yu06-consumer-halt` | PASS | run2 46s |  |
| `yu16-treasury-pricing` | PASS | run3 2s |  |
| `yu16-bond-position` | PASS | run3 30s |  |
| `yu16-accrued-interest` | PASS | run3 3s |  |
| `yu17-session-opens-from-close` | SKIP/REFUSED | run3 2s | PRECONDITION: needs a PUBLISHED session strictly before today; a fresh epoch has none. Honest SKIP. |
| `yu16-book-grid` | PASS | run3 187s |  |
| `yu17-band-follows-market` | PASS | run4 17s | PASS with EXPECT=after (run 3 refused: the variable is required, unlike the nine format-8 proofs). |
| `yu17-option-collar` | PASS | run3 4s |  |
| `yu17-fnma-collar` | PASS | run3 3s |  |
| `yu17-fine-grid` | PASS | run3 5s |  |
| `yu17-book-retick` | FAIL | run3 65s | SUSPECT 2: its precondition is the control feed applying the minted ticker. |
| `yu17-retick-determinism` | PASS | run3 205s |  |
| `yu17-session-closed-rejects` | PASS | run3 6s |  |
| `yu17-preopen-queue-open` | PASS | run3 23s |  |
| `yu17-keyed-ack-correlation` | FAIL | run3 39s | SUSPECT 6: rounds 1-2 stranded nothing, then "no leader before round 3"; a deleted leader keeps running ~10 s until SIGKILL here (suspect 1), which this proof's leader wait does not budget for. Cause not judged here. |
| `yu17-swap-netting` | PASS | run3 74s |  |
| `yu17-swaption-terms` | PASS | run3 13s |  |
| `yu17-fx-credit` | PASS | run3 57s |  |
| `yu16-ready-tracks-commit` | PASS | run3 76s |  |
| `yu16-liveness-restarts-wedge` | PASS | run3 215s |  |
| `yu18-gateway-auth-boundary` | PASS | run3 26s | NEW. Notes spec gaps 1 (504 for an unknown ticker) and 8 (batch refused whole). |
| `yu18-cluster-ingress-auth` | PASS | run3 112s | NEW. |
| `yu18-console-sessions` | PASS | run3 9s | NEW. Desk arm SKIP (not deployed in the side namespace). |
| `yu18-legacy-trade-bearer` | PASS | run3 15s | NEW. |
| `yu18-projection-effect-end` | FAIL | run6 41s | NEW. Main arm PASS (run 3). `NEGATIVE=1` arm (run 6) FAILS = SUSPECT 3: 2 of 6 sides never reached SQL and `missingTradeSides` stayed 0. |
| `yu15-risk-extract` | PASS | run4 53s | PASS after the fixture sweep. Run 3 failed `halted=3`: FR-EOD32 working as specified on positions my own minted tickers had left (now PA<digits>, swept by seed-proof-fixtures). |
| `yu13-cancel-ingress` | FAIL | run3 3s | PROOF BUG (not fixed, rig torn down): requires the kind-local image `traderx/cluster-node:yu15-cancel`, so it cannot run on GKE. |
| `yu17-halt-survives-failover` | PASS | run4 348s |  |
| `yu17-closed-survives-restart` | PASS | run4 167s |  |
| `yu12-gke-recovery` | PASS | run5 114s | PASS after the credential fix, with REPLAY_RETIRED=1. |
| `yu12-gke-failover-transparency` | PASS | run5 108s | PASS after the credential fix, with REPLAY_RETIRED=1. |
| `yu12-gke-cross-epoch-idreuse` | PASS | run5 62s | PASS after the credential fix, with REPLAY_RETIRED=1. |
| `yu13-gke-replace-proof` | FAIL | run5 2s | PROOF BUG (not fixed, rig torn down): after the credential fix it still reads `printReplay` from price-publisher /health outside the gates and refuses. |
| `yu18-member-runtime` | FAIL | run4 22s | NEW. Step 04 is SUSPECT 1. |
| `yu18-jetstream-feed` | PASS | run5 53s | NEW. Killed at 2,000 of 6,000 sides applied; positions exact after replay. Spec gap 2 (no durable consumer). |
| `yu18-extract-off-projection` | PASS | run5 32s | NEW. |
| `yu08-algo-slicing` | PASS | run6 53s | algo engine scaled to 1 for this proof only. |
| `yu17-taq-replay` | NOT RUN | not run  | RETIRED (TAQ path, yaakov 2026-10-27). Not run. |
| `yu17-replay-attribution` | NOT RUN | not run  | RETIRED with the TAQ path: step 1 requires the replay writing. Not run. |
| `yu13-stp-and-replace` | NOT RUN | not run  | NOT RUN: rolls kind-only historical images (`:yu15-pre-1k`). |
| `yu12-gke-restore-from-gcs` | NOT RUN | not run  | NOT RUN: the CompSci profile strips RESTORE_FROM_GCS; bearer fix applied. |
| `yu18-networkpolicy-enforced` | NOT RUN | not run  | NOT RUN: traderx-demo has no Dataplane V2, so the policy is inert by the deployment doc; hazard fixed. |
| `yu18-var-es-e2e` | NOT RUN | not run  | NOT RUN: local, needs Alex's engine at RISK_ENGINE_ORIGIN. |
| `yu16-invisible-orders-repro` | NOT RUN | not run  | NOT RUN: a defect reproducer, not a proof. |
| `failover-nodeclock.sh` | NOT RUN | not run  | NOT RUN: a measurement. |

## Appendix: requirement matrix

| State | Requirement | Class | Proofs (before) | Proofs (added) | Note |
|---|---|---|---|---|---|
| YU02 | `FR-LK01` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK02` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK03` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK04` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK05` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK06` | LIVE | yu12-gke-recovery |  | replay-gated readiness now = cluster recovery |
| YU02 | `FR-LK07` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK08` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU02 | `FR-LK09` | CI |  |  | lineage/port/structure; superseded by YU12 cluster tier |
| YU03 | `FR-IMRG01` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG06` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG07` | LIVE |  |  | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| YU03 | `FR-IMRG08` | LIVE | yu03-risk-proof | yu18-gateway-auth-boundary | unknown security refused; S4 lookup-only |
| YU03 | `FR-IMRG09` | LIVE |  |  | stale-price classification: no live arm |
| YU03 | `FR-IMRG10` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG11` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG12` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG13` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG14` | LIVE | yu13-clordid-suppression |  |  |
| YU03 | `FR-IMRG15` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG16` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG17` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG19` | LIVE |  |  | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| YU03 | `FR-IMRG20` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG21` | LIVE | yu12-gke-recovery yu05-regulatory-reproducible |  |  |
| YU03 | `FR-IMRG22` | LIVE | yu12-gke-recovery yu05-regulatory-reproducible |  |  |
| YU03 | `FR-IMRG23` | LIVE | yu05-regulatory-reproducible |  | rejection journaled, absent from book |
| YU03 | `FR-IMRG24` | LIVE | yu03-risk-proof |  |  |
| YU03 | `FR-IMRG26` | LIVE |  |  | table exhaustion -> CAPACITY: no live arm (ERH-F7 same) |
| YU03 | `FR-IMRG27` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG30` | LIVE | yu03-risk-proof | yu18-gateway-auth-boundary | operator pair on /risk/control, /seed |
| YU03 | `FR-IMRG40` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG41` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG42` | LIVE | yu13-clordid-suppression |  |  |
| YU03 | `FR-IMRG43` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG44` | LIVE |  |  | UI shows rejection reason: no UI proof |
| YU03 | `FR-IMRG45` | CI |  |  | CI/static/offline |
| YU04 | `FR-IMRG-OUTBOX-ORDER` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG03` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG04` | LIVE | yu04-live-delta yu04-offline-catchup |  | FAIL on the CompSci GKE tier: suspect 2 |
| YU04 | `FR-IMRG05` | LIVE | yu04-live-delta yu04-offline-catchup |  | FAIL on the CompSci GKE tier: suspect 2 |
| YU04 | `FR-IMRG32` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG33` | LIVE | yu04-live-delta yu04-offline-catchup |  | FAIL on the CompSci GKE tier: suspect 2 |
| YU04 | `FR-IMRG34` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU05 | `FR-IMRG41` | CI |  |  | CI/static/offline |
| YU05 | `FR-PTC01` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC02` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC03` | LIVE | yu05-recon |  |  |
| YU05 | `FR-PTC04` | LIVE | yu05-recon |  |  |
| YU05 | `FR-PTC05` | LIVE | yu05-recon |  |  |
| YU05 | `FR-PTC06` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC07` | LIVE | yu05-settlement yu05-recon |  |  |
| YU05 | `FR-PTC08` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC10` | LIVE | yu05-recon |  |  |
| YU05 | `FR-PTC20` | LIVE | yu05-regulatory-reproducible yu05-auth-entitlements |  |  |
| YU05 | `FR-PTC21` | LIVE | yu05-regulatory-reproducible yu05-auth-entitlements |  |  |
| YU05 | `FR-PTC22` | LIVE | yu05-regulatory-reproducible yu05-auth-entitlements |  |  |
| YU05 | `FR-PTC30` | LIVE | yu05-auth-entitlements |  | TCA reached only as an auth target; arrival/TWAP/slippage numbers unasserted (PARTIAL) |
| YU05 | `FR-PTC31` | LIVE | yu05-auth-entitlements |  | TCA reached only as an auth target; arrival/TWAP/slippage numbers unasserted (PARTIAL) |
| YU05 | `FR-PTC32` | LIVE | yu05-auth-entitlements |  | TCA reached only as an auth target; arrival/TWAP/slippage numbers unasserted (PARTIAL) |
| YU05 | `FR-PTC40` | LIVE | yu05-auth-entitlements |  |  |
| YU05 | `FR-PTC41` | LIVE | yu05-auth-entitlements |  |  |
| YU05 | `FR-PTC42` | LIVE | yu05-auth-entitlements |  |  |
| YU06 | `FR-EOD01` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD02` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD03` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD04` | CI |  |  | CI/static/offline |
| YU06 | `FR-EOD05` | LIVE | yu15-risk-extract yu06-quality-gate |  | admin bearer used; no refusal arm (PARTIAL) |
| YU06 | `FR-EOD06` | LIVE | yu06-consumer-halt |  | MISSING instrument |
| YU06 | `FR-EOD10` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD11` | LIVE | yu06-quality-gate |  | band selection arm |
| YU06 | `FR-EOD12` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD13` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD20` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD21` | LIVE | yu15-risk-extract | yu18-extract-off-projection |  |
| YU06 | `FR-EOD22` | LIVE | yu15-risk-extract | yu18-extract-off-projection |  |
| YU06 | `FR-EOD23` | LIVE | yu06-quality-gate |  |  |
| YU06 | `FR-EOD30` | LIVE | yu06-consumer-halt yu15-risk-extract |  |  |
| YU06 | `FR-EOD31` | LIVE | yu06-consumer-halt yu15-risk-extract |  |  |
| YU06 | `FR-EOD32` | LIVE | yu06-consumer-halt yu15-risk-extract |  |  |
| YU06 | `FR-EOD33` | LIVE | yu06-consumer-halt yu15-risk-extract |  |  |
| YU06 | `FR-EOD40` | CI |  |  | CI/static/offline |
| YU07 | `FR-TS01` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS02` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS03` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS04` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS05` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS06` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS07` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS08` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS09` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS10` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS11` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU07 | `FR-TS12` | LIVE |  |  | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| YU07 | `FR-TS13` | LIVE |  |  | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| YU07 | `FR-TS14` | LIVE |  |  | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| YU07 | `FR-TS15` | CI |  |  | offline ingest/query recipes + q self-checks |
| YU08 | `FR-AE01` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE02` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE03` | LIVE |  |  | VWAP weighting / empty-profile fallback: no live arm |
| YU08 | `FR-AE04` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE05` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE06` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE07` | LIVE | yu08-algo-slicing |  |  |
| YU08 | `FR-AE08` | LIVE |  |  | crash-resume replay from JetStream: no live arm (SC-AE06) |
| YU08 | `FR-AE09` | LIVE |  |  | VWAP weighting / empty-profile fallback: no live arm |
| YU09 | `FR-OH01` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU09 | `FR-OH02` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU09 | `FR-OH03` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU09 | `FR-OH04` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU09 | `FR-OH20` | CI |  |  | journal archival belongs to the retired single-node journal; cluster tier = ADR-079 §3 offload |
| YU09 | `FR-OH21` | CI |  |  | journal archival belongs to the retired single-node journal; cluster tier = ADR-079 §3 offload |
| YU09 | `FR-OH22` | CI |  |  | journal archival belongs to the retired single-node journal; cluster tier = ADR-079 §3 offload |
| YU09 | `FR-OH23` | CI |  |  | journal archival belongs to the retired single-node journal; cluster tier = ADR-079 §3 offload |
| YU09 | `FR-OH30` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU09 | `FR-OH40` | CI |  |  | manifest/secret hygiene, build, runbook |
| YU10 | `FR-FIX01` | LIVE | yu10-fix-session |  |  |
| YU10 | `FR-FIX02` | LIVE | yu10-fix-session |  |  |
| YU10 | `FR-FIX03` | LIVE | yu13-cancel-ingress |  | yu13-fix-cancel.mjs |
| YU10 | `FR-FIX04` | LIVE |  |  | OrderStatusRequest: no live arm |
| YU10 | `FR-FIX05` | LIVE | yu10-fix-session |  |  |
| YU10 | `FR-FIX06` | LIVE | yu13-cancel-ingress |  | yu13-fix-cancel.mjs |
| YU10 | `FR-FIX07` | LIVE | yu10-fix-session |  | logon; superseded by ERH-S3 FIX_SESSION_AUTH |
| YU10 | `FR-FIX08` | LIVE | yu10-fix-session |  | logon; superseded by ERH-S3 FIX_SESSION_AUTH |
| YU10 | `FR-FIX09` | LIVE | yu10-fix-session |  |  |
| YU10 | `FR-FIX10` | LIVE |  |  | duplicate ClOrdID per session: no live arm (REST twin is yu13-clordid-suppression) |
| YU10 | `FR-FIX11` | LIVE |  |  | session persistence / ambiguous-outcome classes: no live arm |
| YU10 | `FR-FIX12` | LIVE |  |  | session persistence / ambiguous-outcome classes: no live arm |
| YU10 | `FR-FIX13` | LIVE | yu10-fix-session |  |  |
| YU11 | `FR-AR01` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR02` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR03` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR04` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR05` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR06` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR07` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR08` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR09` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR10` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR11` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR12` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR13` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR14` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR15` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR16` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR17` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR18` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR19` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR20` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR21` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR22` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR23` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR24` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR25` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR26` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU11 | `FR-AR27` | CI |  |  | dual-transport replication retired by YU12 Aeron Cluster |
| YU12 | `FR-AC01` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC02` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC03` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC04` | CI |  |  | CI/static/offline |
| YU12 | `FR-AC05` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC06` | CI |  |  | CI/static/offline |
| YU12 | `FR-AC07` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC08` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC09` | LIVE | yu12-gke-cross-epoch-idreuse |  |  |
| YU12 | `FR-AC10` | LIVE | yu12-gke-recovery |  |  |
| YU12 | `FR-AC11` | LIVE | yu12-gke-failover-transparency failover-nodeclock |  |  |
| YU12 | `FR-AC12` | LIVE |  |  | partition minority never commits: no live arm |
| YU12 | `FR-AC13` | LIVE | yu12-gke-failover-transparency failover-nodeclock |  |  |
| YU12 | `FR-AC14` | LIVE | yu13-readmodel-effect-end | yu18-projection-effect-end |  |
| YU12 | `FR-AC15` | LIVE | yu16-ready-tracks-commit yu16-liveness-restarts-wedge |  |  |
| YU12 | `FR-AC16` | CI |  |  | CI/static/offline |
| YU12 | `FR-AC17` | LIVE | yu16-ready-tracks-commit yu16-liveness-restarts-wedge |  |  |
| YU13 | `FR-LOB01` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB02` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB03` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB04` | LIVE |  |  | market order w/o limit; tick never fills: no live arm (ri07 covers MARKET) |
| YU13 | `FR-LOB05` | LIVE |  |  | market order w/o limit; tick never fills: no live arm (ri07 covers MARKET) |
| YU13 | `FR-LOB06` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB07` | LIVE | yu17-keyed-ack-correlation |  |  |
| YU13 | `FR-LOB08` | LIVE | yu16-book-grid yu17-fine-grid |  |  |
| YU13 | `FR-LOB09` | LIVE | yu12-gke-recovery yu13-gke-replace-proof |  |  |
| YU13 | `FR-LOB10` | LIVE | yu13-cancel-ingress |  |  |
| YU13 | `FR-TR01` | LIVE | yu13-otel-trace-join |  |  |
| YU13 | `FR-TR02` | LIVE | yu13-otel-trace-join |  |  |
| YU13 | `FR-TR03` | LIVE | yu13-otel-trace-join |  |  |
| YU13 | `FR-TR04` | LIVE | yu13-otel-reject-trace-log-join |  |  |
| YU13 | `FR-TR05` | LIVE | yu13-otel-reject-trace-log-join |  |  |
| YU13 | `FR-TR06` | LIVE | yu13-otel-trace-join |  |  |
| YU13 | `FR-TR07` | CI |  |  | CI/static/offline |
| YU14 | `FR-LEO01` | LIVE | yu15-option-persistence yu17-option-collar |  |  |
| YU14 | `FR-LEO02` | LIVE | yu15-option-persistence yu17-option-collar |  |  |
| YU14 | `FR-LEO03` | LIVE | yu15-option-persistence yu17-option-collar |  |  |
| YU14 | `FR-LEO04` | LIVE | yu12-gke-recovery |  | multiplier survives snapshot: indirect (PARTIAL) |
| YU14 | `FR-LEO05` | CI |  |  | CI/static/offline |
| YU14 | `FR-LEO06` | CI |  |  | CI/static/offline |
| YU14 | `FR-LEO07` | LIVE | yu15-option-persistence yu17-option-collar |  |  |
| YU15 | `FR-RXT01` | LIVE | yu15-risk-extract | yu18-extract-off-projection | trigger amended by ADR-079 §5 to eod.prices.ready |
| YU15 | `FR-RXT02` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT03` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT04` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT05` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT06` | LIVE |  |  | truncated cut refused; unmarkable row: no live arm (unit) |
| YU15 | `FR-RXT07` | LIVE | yu15-risk-extract yu16-accrued-interest |  |  |
| YU15 | `FR-RXT08` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT09` | LIVE |  |  | truncated cut refused; unmarkable row: no live arm (unit) |
| YU15 | `FR-RXT10` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT11` | LIVE | yu15-risk-extract yu16-accrued-interest |  |  |
| YU15 | `FR-RXT12` | LIVE | yu15-risk-extract yu16-accrued-interest |  |  |
| YU15 | `FR-RXT13` | LIVE | yu15-risk-extract |  | write-once proven only on a gs:// sink; file sink on CompSci side namespaces |
| YU15 | `FR-RXT14` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT15` | LIVE | yu16-ready-tracks-commit |  |  |
| YU15 | `FR-RXT16` | LIVE | yu15-option-persistence |  |  |
| YU15 | `FR-RXT17` | LIVE | yu06-quality-gate yu15-risk-extract |  |  |
| YU15 | `FR-RXT18` | LIVE | yu06-quality-gate yu15-risk-extract |  |  |
| YU15 | `FR-RXT19` | LIVE | yu06-quality-gate yu15-risk-extract |  |  |
| YU16 | `FR-01601` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01602` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01603` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01604` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01605` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01606` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01607` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01608` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01609` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01610` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01611` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01612` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01613` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01614` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01615` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01616` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01617` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01701` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01702` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01703` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01704` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01705` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01706` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01707` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01708` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01709` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01710` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01711` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01712` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01713` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01714` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01715` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01716` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01717` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01718` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01719` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01720` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-01721` | CI |  |  | TIP 016/017 adoption crosswalk; the adopted behaviour is the FR-CDM row it names |
| YU16 | `FR-CDM01` | LIVE |  |  | /instruments and /stocks response shapes: no live proof |
| YU16 | `FR-CDM02` | LIVE |  |  | /instruments and /stocks response shapes: no live proof |
| YU16 | `FR-CDM03` | LIVE |  |  | /instruments and /stocks response shapes: no live proof |
| YU16 | `FR-CDM04` | LIVE |  |  | /instruments and /stocks response shapes: no live proof |
| YU16 | `FR-CDM05` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM06` | CI |  |  | CI/static/offline |
| YU16 | `FR-CDM07` | LIVE |  |  | ETFs tradable through the inherited paths: no live proof |
| YU16 | `FR-CDM08` | LIVE |  |  | ETFs tradable through the inherited paths: no live proof |
| YU16 | `FR-CDM09` | LIVE |  |  | /instruments and /stocks response shapes: no live proof |
| YU16 | `FR-CDM10` | LIVE | yu04-live-delta yu04-offline-catchup |  |  |
| YU16 | `FR-CDM11` | LIVE | yu04-live-delta yu04-offline-catchup |  |  |
| YU16 | `FR-CDM12` | LIVE | yu04-live-delta yu04-offline-catchup |  |  |
| YU16 | `FR-CDM13` | LIVE | yu04-live-delta yu04-offline-catchup |  |  |
| YU16 | `FR-CDM14` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM15` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM16` | LIVE | yu16-bond-position |  |  |
| YU16 | `FR-CDM17` | CI |  |  | CI/static/offline |
| YU16 | `FR-CDM18` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM19` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM20` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM21` | LIVE | yu16-treasury-pricing |  |  |
| YU16 | `FR-CDM22` | LIVE | yu16-bond-position |  |  |
| YU16 | `FR-CDM23` | LIVE |  |  | Rejected trade state + fail-closed Treasury metadata: no live arm |
| YU16 | `FR-CDM24` | LIVE |  |  | Rejected trade state + fail-closed Treasury metadata: no live arm |
| YU16 | `FR-CDM25` | LIVE | yu16-accrued-interest |  |  |
| YU16 | `FR-CDM26` | LIVE | yu16-accrued-interest |  |  |
| YU16 | `FR-CDM27` | LIVE | yu16-accrued-interest |  |  |
| YU16 | `FR-CDM28` | LIVE | yu16-accrued-interest |  |  |
| YU17 | `FR-OTC01` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC02` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC03` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC04` | LIVE | yu17-swap-netting |  | refusal arms |
| YU17 | `FR-OTC05` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC06` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC07` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC08` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC09` | LIVE |  |  | MAX_CONTRACTS refusal: no live arm |
| YU17 | `FR-OTC10` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC11` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC12` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC13` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC14` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC15` | LIVE | yu17-swap-netting yu17-fx-credit |  |  |
| YU17 | `FR-OTC16` | LIVE | yu17-swap-netting yu17-fx-credit |  |  |
| YU17 | `FR-OTC17` | LIVE | yu17-swap-netting yu17-fx-credit |  |  |
| YU17 | `FR-OTC18` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC19` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC20` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC21` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC22` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC23` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC24` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC25` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC26` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC27` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC28` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC29` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC30` | LIVE | yu17-swap-netting |  |  |
| YU17 | `FR-OTC31` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC32` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC33` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC34` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC35` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC36` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC37` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC38` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC39` | CI |  |  | snapshot/format/abort paths: unit |
| YU17 | `FR-OTC40` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC41` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC42` | LIVE | yu17-swaption-terms |  |  |
| YU17 | `FR-OTC43` | LIVE | yu17-swaption-terms |  |  |
| YU18 | `ADR-079-S4` | LIVE | yu18-networkpolicy-enforced | yu18-cluster-ingress-auth | networkpolicy proof is inert without Dataplane V2 |
| YU18 | `ADR-079-S5` | LIVE | yu15-risk-extract | yu18-extract-off-projection |  |
| YU18 | `FR-AM01` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-AM02` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-AM03` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-AM04` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-AM05` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-AM06` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-APR01` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR02` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR03` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR04` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR05` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR06` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR07` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR08` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR09` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR10` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-APR11` | LIVE |  |  | disabled by default; disposable profile only |
| YU18 | `FR-BMA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-BMA08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CPU01` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU02` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU03` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU04` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU05` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CRP-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CRP-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CRP-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CS-01` | LIVE |  | yu18-console-sessions |  |
| YU18 | `FR-CS-02` | LIVE |  |  | hosted Desk: not in the CompSci side-namespace render |
| YU18 | `FR-CS-03` | LIVE |  |  | hosted Desk: not in the CompSci side-namespace render |
| YU18 | `FR-CS-04` | LIVE |  | yu18-console-sessions |  |
| YU18 | `FR-CS-05` | LIVE |  | yu18-console-sessions |  |
| YU18 | `FR-CUI-01` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-02` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-03` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-04` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-05` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-06` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-07` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-08` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-09` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-10` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-11` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-12` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-13` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-14` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-15` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-16` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-17` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-18` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-19` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-20` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-CUI-21` | LIVE |  |  | combined desk UI: no live proof |
| YU18 | `FR-DA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DRS-01` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-02` | LIVE |  | yu18-projection-effect-end yu18-jetstream-feed | NEGATIVE arm FAILS: suspect 3 |
| YU18 | `FR-DRS-03` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-04` | LIVE |  | yu18-projection-effect-end | indirect: tip bridge payload accepted end to end |
| YU18 | `FR-DRS-05` | LIVE |  | yu18-jetstream-feed |  |
| YU18 | `FR-DRS-06` | LIVE |  |  | BRIDGE_PARTITIONS>1 on members: no live proof (needs a member env change) |
| YU18 | `FR-DRS-07` | LIVE |  | yu18-jetstream-feed |  |
| YU18 | `FR-DRS-08` | LIVE |  | yu18-jetstream-feed |  |
| YU18 | `FR-DRS-09` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-10` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-EB01` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB02` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB03` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB04` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB05` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB06` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB07` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB08` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB09` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB10` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB11` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB12` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB13` | LIVE |  | yu18-extract-off-projection |  |
| YU18 | `FR-EB14` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB15` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB16` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB17` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB18` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB19` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB20` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB21` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB22` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB23` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB24` | CI |  |  | offline eod-risk-bundles CLI: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB25` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB26` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB27` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB28` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB29` | LIVE |  |  | console EOD status distinctions: no live proof |
| YU18 | `FR-EB30` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-EB31` | CI |  |  | offline eod-risk-bundles coordinator/intake: test-state-YU18-eod-risk-bundles.sh |
| YU18 | `FR-ER01` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER02` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER03` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER04` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER05` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER06` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER07` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ER08` | LIVE |  |  | operator recovery: ri03-recovery.py (local), no proofs/ script |
| YU18 | `FR-ERH-C1` | LIVE |  | yu18-console-sessions |  |
| YU18 | `FR-ERH-C2` | LIVE |  | yu18-legacy-trade-bearer |  |
| YU18 | `FR-ERH-F1` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F10` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F11` | LIVE |  |  | strict replay on a purged archive / ClOrdID ledger bound: no live arm |
| YU18 | `FR-ERH-F2` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F3` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F4` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-ERH-F5` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F6` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F7` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F8` | LIVE |  |  | strict replay on a purged archive / ClOrdID ledger bound: no live arm |
| YU18 | `FR-ERH-F9` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-S1` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-ERH-S2` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-ERH-S3` | LIVE |  |  | FIX_SESSION_AUTH: CompSci profile leaves it unset (acceptor logs INSECURE); no live proof |
| YU18 | `FR-ERH-S4` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-ERH-S5` | LIVE |  |  | refuse-to-start on dev secret: no live arm |
| YU18 | `FR-ERH-S6` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-FR01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-FR02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-FR03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-FR04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-FR05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-FR06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GD01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GD02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GD03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GD04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GD05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GMR-01` | LIVE |  | yu18-member-runtime | step 04 FAILS: suspect 1 |
| YU18 | `FR-GMR-02` | CI |  |  | render/admission counts: test_prepare |
| YU18 | `FR-GMR-03` | LIVE |  | yu18-member-runtime | step 04 FAILS: suspect 1 |
| YU18 | `FR-GMR-04` | LIVE |  | yu18-member-runtime | step 04 FAILS: suspect 1 |
| YU18 | `FR-GMR-05` | LIVE |  | yu18-member-runtime | step 04 FAILS: suspect 1 |
| YU18 | `FR-GMR-06` | CI |  |  | render/admission counts: test_prepare |
| YU18 | `FR-GP01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GP02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-GP03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IA08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IRM-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IRM-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IRM-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-IRM-04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LAF06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-LRA-06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-MAR01` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-MAR02` | LIVE |  | yu18-gateway-auth-boundary |  |
| YU18 | `FR-MAR03` | LIVE |  |  | readout field set: partially read by the new proof |
| YU18 | `FR-MUI01` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI02` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI03` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI04` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI05` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI06` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI07` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI08` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI09` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-MUI10` | LIVE |  |  | console UI: specs/.../managed-run-ui/proofs/*.mjs (browser), not in scripts/proofs |
| YU18 | `FR-OD01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-OD02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-OD03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-OD04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-OD05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-ORA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-ORA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-ORA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-ORA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-OT01` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT02` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT03` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT04` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT05` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT06` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT07` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT08` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT09` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT10` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT11` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT12` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT13` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT14` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT15` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT16` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT17` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT18` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT19` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT20` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT21` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT22` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT23` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT24` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT25` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT26` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT27` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT28` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT29` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT30` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT31` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT32` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT33` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT34` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT35` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT37` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT38` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT39` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT40` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT41` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-OT42` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
| YU18 | `FR-PC01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PC08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-PG-09` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-POE-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-POE-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-POE-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RA-06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RCM01` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RCM02` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RCM03` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RD01` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RD02` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RD03` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RD04` | LIVE |  |  | diagnostic metric/readout shape: no live proof (unit) |
| YU18 | `FR-RMA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RMA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RMA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RMA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RMA05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-RP-BATCH-IMPORT` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP-VARES-01` | LIVE | yu18-var-es-e2e |  |  |
| YU18 | `FR-RP-VARES-02` | LIVE | yu18-var-es-e2e |  |  |
| YU18 | `FR-RP01` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP02` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP03` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP04` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP05` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP06` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RP07` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RS01` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-RS02` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-SIP01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-SIP07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TC08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TP01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TP02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TP03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TP04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TP05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS-08` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-TS01` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS02` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS03` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS04` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS05` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS06` | CI |  |  | test-suite-profiles pack |
| YU18 | `FR-TS07` | CI |  |  | test-suite-profiles pack |
| YU18 | `recovery-identity FR-RI01` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI02` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI03` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI04` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI05` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI06` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI07` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI08` | CI |  |  | local custody; offline tests |
| YU18 | `recovery-identity FR-RI09` | CI |  |  | local custody; offline tests |
