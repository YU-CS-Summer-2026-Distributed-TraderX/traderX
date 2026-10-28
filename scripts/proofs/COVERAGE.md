# Proof coverage against the specs

Audited 2026-10-27 against the spec packs on `traderX-risk-integration`, round 1 at `b28fd9f3`, round 2 at `40d93f6b` and round 3 at `41a77949` / `071cde6d` (state
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

## Round 3 (2026-10-28 overnight, tip `41a77949`; FIX on `071cde6d`)

Same rules: black-box, assertions from the specs, interface material (contracts, manifests, env names, deploy
scripts, the spec packs' own proof drivers) only for how to reach the system. The tip adds persistent member
volumes (ADR-087, suspect 15), persistent FIX sessions (ADR-086), FIX fill reports (ADR-084), the FIX password
redaction (suspect 12), the control-stream republish (ADR-085) and the ready-gate/election fixes. The FIX proofs
were then re-run on a gateway built from `071cde6d` (the durable fill feed, FR-FFR-01..11, and the session
hardening, FR-GFS-01..13); `41a77949` → `071cde6d` changes only gateway code and three gateway env values, so
only the gateway was rolled. "Before" is round 2's end state, re-counted on the `071cde6d` spec packs (32 new
requirement IDs: FR-CFR-01..05, FR-GMR-08..10, FR-FFR-01..11, FR-GFS-01..13).

**Coverage: 218 → 270 of 352 live-provable requirements (62% → 77%).** YU18 38 → 90 of 154.

### Live run, round 3

Fresh side namespace `traderx-proofaudit` on `traderx-demo`. Images `pa-41a77949` (13, desk included) from an
empty-root render on VM `proofaudit-build-20261028` (c4d-standard-16, 12:23–12:32 am ET, deleted), and
`pa-071cde6d` from VM `proofaudit-build-20261028b` (1:29–1:42 am ET, deleted). Pools `pa-engine`
(3× c4d-standard-8) and `pa-support` (2× e2-standard-8), 12:23–2:41 am ET, deleted. The members' persistent
claims used a proof-owned copy of the archive class (`pa-hyperdisk`, identical parameters, reclaim Delete), so
nothing bound to production's `order-matcher-archive`; claims and the class were deleted at the end and no
proof-audit volume is left. The hosted Desk was rendered with `render-desk-deployment.py`'s own derivation,
minus the public certificate and NEG. gcloud auth expired at 1:14 am ET mid-batch and was renewed at 1:29 am; the
four proofs it interrupted were re-run.

**85 runs. Final verdict per proof: 64 pass, 5 fail, 1 honest skip.** The 5: suspect 11 (`yu18-market-order`),
suspects 13+14 (`yu05-recon`), the trace-key spec gap (`yu13-otel-trace-join`, `yu13-otel-reject-trace-log-join`)
and the new suspect 16 (`yu18-desk`). Every other failure on the way was the auth expiry or a proof bug, all fixed
and re-run green. Round-2 suspects 7, 8, 9, 10, 12 and 15 are closed on this tip.

### Proofs added in round 3

| Proof | Covers | Result |
|---|---|---|
| [`yu18-console-run-scope.sh`](yu18-console-run-scope.sh) + `.mjs` | FR-MUI01..09 (browser), FR-MUI07 (proxy) | PASS, including DESTRUCTIVE=1 (database gone, hung read) |
| [`yu18-desk.sh`](yu18-desk.sh) + `.mjs` | FR-CUI-01, 02, 04..17, 19..21 (browser + effect end) | FAIL on suspect 16 only (soft); every other check PASS |
| [`lib-cdp.mjs`](lib-cdp.mjs) | headless Chrome over CDP, no dependencies (the spec packs' own pattern) | — |
| [`yu18-control-republish.sh`](yu18-control-republish.sh) | FR-CFR-01, 02, 04 (ADR-085) | PASS: complete stream untouched; purged and deleted streams refilled (536 rows, version order) |
| [`yu18-fix-fill-feed.sh`](yu18-fix-fill-feed.sh) + `.mjs` | FR-FFR-02/03/04/07/08/09, FR-GFS-07/08/10/12 | PASS on 071cde6d |

Re-run, written by other lanes: `yu18-member-disk-survives` (MODE=scale and delete) and the SIGSTOP
`yu18-minority-no-commit` PASS on 41a77949; `yu18-fix-session`, `yu18-fix-resume` (and NEGATIVE=1) and
`yu18-fix-store-faults` PASS on 071cde6d.

**The UI proofs, briefly.** The console proof is the spec pack's SC-MUI04/06 browser driver on a live tier:
registry phases and scoped rows are the spec's own labelled SQL fixtures applied on the rig's database, and
put back on exit; a hung read is a session holding WRITE locks on the registry tables. The Desk proof creates
a workspace and an account through the UI and reads every claim the browser cannot settle at the effect end:
SQL rows, the gateway's admission (a REST order on the new account accepted, a never-created account refused
`UNKNOWN_ACCOUNT`), no password, hash or salt in any downloaded bundle, a WebSocket RST leaving the Desk server
up and unrestarted. On this unmanaged run (`legacy-unknown`) the Desk refuses every submit before sending,
as FR-CUI-17 requires, so its acceptance half joins the managed-run tier gap (spec gap 20).

### Proof bugs fixed in round 3

- `yu18-member-runtime`: step 01 still asserted the generic ephemeral volume ADR-087 removed; it now asserts
  the retained `data-<pod>` claim, the class's parameters and Retain/Retain (`STORAGE_CLASS` names a side
  namespace's copy). PASS.
- `yu18-fix-fill-feed` (new this round): its first version deleted every `/orders` consumer, including
  trade-processor's durable `tp-orders-p0`, which stopped the order read model until trade-processor restarted
  (and failed the two FIX proofs after it). It now deletes only the gateways' ordered consumers. Observed on
  the way: trade-processor does not recreate a deleted durable consumer until it restarts (no spec line covers
  it; noted, not a suspect).
- `yu18-console-run-scope`: NATS monitoring is on the pod, not the Service.
- `yu18-fix-session`: step 8 asserts the FIX password is in no gateway log (suspect 12's regression check).
- `lib-jetstream.py`: `consumers`, and the destructive `cdelete` / `purge` / `delete` for the proofs above.

### Coverage by state (round 3)

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
| YU10 | 13 | 13 | 13 | 0 | 0 |
| YU11 | 0 | 0 | 0 | 0 | 27 |
| YU12 | 14 | 14 | 14 | 0 | 3 |
| YU13 | 16 | 16 | 16 | 0 | 1 |
| YU14 | 5 | 5 | 5 | 0 | 2 |
| YU15 | 19 | 17 | 17 | 0 | 0 |
| YU16 | 26 | 21 | 21 | 0 | 40 |
| YU17 | 35 | 35 | 35 | 0 | 8 |
| YU18 | 154 | 38 | 90 | 42 | 190 |
| **all** | **352** | **218 (62%)** | **270 (77%)** | 42 | 317 |

### Findings, round 3

**After round 3 (2026-10-28, not re-run live):** suspect 16 fixed (the Desk server marks a pre-send
refusal `sent:false`; the Desk shows `Refused: <reason>`, FR-CUI-19). Spec gap 18 closed: ADR-060 §1a and
FR-TR08 define the key, which is account-scoped (ENGINE-REVIEW G1); both `yu13-otel-*` proofs predicted
the unscoped key and now derive the scoped one (checked against the Java functions on four ClOrdID/account
pairs). Spec gaps 19 and 20 closed in FR-CUI-21 (hosted profile) and the unmanaged-run paragraph of the
trader-workspace spec.

### New suspects (round 3)

16. **FR-CUI-19: the Desk shows a server refusal that sent nothing as "Outcome unknown".** On the hosted Desk
    (unmanaged run, `legacy-unknown`), Submit order posts `/desk-api/orders/orders` and the Desk server answers
    `503 {"error":"Current run could not be confirmed. Nothing sent."}`: the FR-CUI-17 preflight, correctly
    refusing because no ACTIVE registered descriptor exists. The ticket then says `Outcome unknown. Check orders
    before submitting again; this request will not be retried.` FR-CUI-19 separates the two: "Server reads can
    refuse safely before sending; transport failure after sending remains an unknown outcome." The draft is
    kept and nothing is retried (both correct, both asserted), and SQL holds no order from it; only the label is
    wrong. It matters because a trader told "unknown" is told to go and check for an order that cannot exist.

### Round-1/2 suspects re-run on 41a77949 (FIX rows also on 071cde6d, below)

- **Suspects 7, 8, 9, 10: CLOSED.** `yu18-fix-session` PASSES every check on 41a77949: OrderStatus answers
  from the read model (7), the fill reports arrive (8), an abrupt reconnect continues the sequence (9), an
  unknown symbol is answered `OrdStatus=8 rejected: UNKNOWN_SECURITY` (10).
- **Suspect 12: CLOSED.** The refused `NOTBOUND` Logon is logged `554=********`; the password string occurs
  in no gateway log line. `yu18-fix-session.sh` now asserts that (step 8).
- **Suspect 15: CLOSED by ADR-087** (see the disk-loss and minority rows).
- **Suspect 11 (FR-LOB04 PRICE_MISSING): still open.** A market Buy on an untraded, empty book answers
  `200 {"kind":1}` and ends CANCELED.
  **CLOSED 2026-10-28: a spec gap, not a defect.** YU13 ADR-051 decides that a tick seeds the mark before
  the first print, "enabling … market-order validation before the first cross"; FR-LOB04 named only the
  last trade. FR-LOB04 now names the mark, `yu18-market-order` step 1 expects CANCELED unfilled, and
  `LimitOrderBookTest.marketOrderOnEmptyBookValidatesAtTheTickSeededMark` pins it (it fails if validation
  reads the last trade only). No engine change.
- **Suspect 13 (recon bond prices at 3 dp) and 14 (missingTradeSides 0 for DB-refused sides): still open.**
  `yu05-recon`: `field_mismatch=6`, `missing_in_projection=2`, exactly as in round 2.
- **Suspect 5 (trace id): now a spec gap, not a defect** (spec gap 18).

### New spec gaps (round 3)

18. **ADR-060 does not define the trace key, so no black-box proof can predict a trace id.** It says the key is
    "a client idempotency key ... set by the gateway from the ClOrdID" and names `TRACE_SALT`, `SAMPLE_SALT`,
    `CLUSTER_SALT` and a mask without values; no spec gives the ClOrdID → key function. `yu13-otel-trace-join`
    predicts with FNV-1a(ClOrdID) and the salts from an older reading of the code; on 41a77949 all three
    predicted ids 404. **Observed, on the same rig:** Tempo holds 19 gateway-rooted `order` traces, and **all 19**
    carry the member's `cluster.commit` and `cluster.apply` spans parented on the gateway's own
    `cluster.consensus` span, with the same `traderx.order_ref`. So the property ADR-060 is for (one trace
    across the consensus boundary, derived independently on both tiers) holds; only the proof's guess at the
    key is wrong. Also observed: 1,798 member-only traces with no gateway root, almost all `order_ref 0`
    (sequenced non-order commands such as reference-data registration and account admission, which the
    gateway does not trace) and a few with real refs (6 spans of ref 27, 2 of ref 21). Whether a member should
    trace a command the gateway does not is not specified either.
19. **FR-CUI-21's membership rule cannot apply on the hosted Desk.** "Account projection reads require owned
    membership" is written for non-admin local-mode sessions; every hosted Desk session holds the admin role
    (the operator password is required for each), and a hosted session reads a foreign account's positions,
    trades and orders with 200. Not a defect by the text; the spec does not say what the hosted profile
    promises instead.
20. **FR-CUI-16/17/19 order flows are managed-run only, so the hosted Desk on an unmanaged run cannot trade.**
    By FR-CUI-17 every direct Desk submit needs a registered ACTIVE descriptor; the CompSci render is unmanaged
    (`legacy-unknown`), so every Desk submit is refused before sending. The acceptance half of FR-CUI-16 and
    all of FR-CUI-18/19 join the managed-run tier gap (with FR-ER01..07). If the hosted Desk is meant to place
    orders on the CompSci demo, that is a product question for the spec, not something a proof can settle.

### Run table, round 3

| Run | Proof | Result | Triage |
|---|---|---|---|
| A, 41a77949 | `scripts/yu15/seed-proof-fixtures.sh` | PASS (40s) |  |
| A, 41a77949 | `yu03-risk-proof` | PASS (4s) |  |
| A, 41a77949 | `yu05-auth-entitlements` | PASS (6s) |  |
| A, 41a77949 | `yu05-settlement` | PASS (7s) |  |
| A, 41a77949 | `yu13-clordid-suppression` | PASS (23s) |  |
| A, 41a77949 | `yu10-fix-session` | FAIL (6s) | A: FIX tunnel refused once (not reached). PASS on re-run and on 071cde6d. |
| A, 41a77949 | `yu13-readmodel-effect-end` | PASS (23s) |  |
| A, 41a77949 | `yu05-regulatory-reproducible` | PASS (2s) |  |
| A, 41a77949 | `yu04-live-delta` | PASS (2s) |  |
| A, 41a77949 | `yu04-offline-catchup` | PASS (19s) |  |
| A, 41a77949 | `yu15-option-persistence` | PASS (36s) |  |
| A, 41a77949 | `yu17-book-retick` | PASS (7s) |  |
| A, 41a77949 | `yu13-otel-trace-join` | FAIL (27s) | Spec gap 18 (was suspect 5): the proof predicts ids from an undefined key; Tempo shows 19/19 gateway traces joined to the member spans. |
| A, 41a77949 | `yu13-otel-reject-trace-log-join` | FAIL (234s) | Spec gap 18, same key. |
| A, 41a77949 | `yu06-quality-gate` | PASS (69s) |  |
| A, 41a77949 | `yu06-consumer-halt` | PASS (43s) |  |
| A, 41a77949 | `yu16-treasury-pricing` | PASS (3s) |  |
| A, 41a77949 | `yu16-bond-position` | PASS (33s) |  |
| A, 41a77949 | `yu16-accrued-interest` | PASS (4s) |  |
| A, 41a77949 | `yu17-session-opens-from-close` | SKIP (2s) | Honest skip: precondition (no prior session close on a fresh rig). |
| A, 41a77949 | `yu16-book-grid` | PASS (157s) |  |
| A, 41a77949 | `yu17-band-follows-market` | PASS (19s) |  |
| A, 41a77949 | `yu17-option-collar` | PASS (4s) |  |
| A, 41a77949 | `yu17-fnma-collar` | PASS (4s) |  |
| A, 41a77949 | `yu17-fine-grid` | PASS (6s) |  |
| A, 41a77949 | `yu17-retick-determinism` | PASS (205s) |  |
| A, 41a77949 | `yu17-session-closed-rejects` | PASS (23s) |  |
| A, 41a77949 | `yu17-preopen-queue-open` | PASS (27s) |  |
| A, 41a77949 | `yu17-swap-netting` | PASS (49s) |  |
| A, 41a77949 | `yu17-swaption-terms` | PASS (15s) |  |
| A, 41a77949 | `yu17-fx-credit` | PASS (47s) |  |
| A, 41a77949 | `yu16-ready-tracks-commit` | PASS (69s) |  |
| A, 41a77949 | `yu16-liveness-restarts-wedge` | PASS (218s) |  |
| A, 41a77949 | `yu18-gateway-auth-boundary` | PASS (29s) |  |
| A, 41a77949 | `yu18-cluster-ingress-auth` | PASS (115s) |  |
| A, 41a77949 | `yu18-console-sessions` | PASS (10s) |  |
| A, 41a77949 | `yu18-legacy-trade-bearer` | PASS (15s) |  |
| A, 41a77949 | `yu18-projection-effect-end` | PASS (15s) |  |
| A, 41a77949 | `yu18-fix-session` | PASS (38s) | Suspects 7-10 and 12 CLOSED (step 8 asserts no password in any gateway log). |
| A, 41a77949 | `yu18-market-order` | FAIL (40s) | Suspect 11 (FR-LOB04 PRICE_MISSING arm); steps 2-4 PASS. |
| A, 41a77949 | `yu18-cdm-instruments` | PASS (16s) |  |
| A, 41a77949 | `yu18-operator-recovery` | PASS (6s) |  |
| A, 41a77949 | `scripts/yu15/seed-proof-fixtures.sh` | PASS (41s) |  |
| A, 41a77949 | `yu15-risk-extract` | FAIL (0s) | A: gcloud auth expired mid-run (kubectl unreachable). PASS on re-run. |
| A, 41a77949 | `yu13-cancel-ingress` | FAIL (1s) | A: auth expired. PASS on re-run. |
| A, 41a77949 | `yu05-recon` | FAIL (0s) | Suspects 13 + 14 (field_mismatch=6, missing_in_projection=2), unchanged from round 2. |
| A, 41a77949 | `yu17-keyed-ack-correlation` | FAIL (0s) | A: auth expired. PASS on re-run. |
| A re-run after auth, 41a77949 | `scripts/yu15/seed-proof-fixtures.sh` | PASS (58s) |  |
| A re-run after auth, 41a77949 | `yu15-risk-extract` | PASS (41s) |  |
| A re-run after auth, 41a77949 | `yu13-cancel-ingress` | PASS (18s) |  |
| A re-run after auth, 41a77949 | `yu05-recon` | FAIL (26s) | Suspects 13 + 14 (field_mismatch=6, missing_in_projection=2), unchanged from round 2. |
| A re-run after auth, 41a77949 | `yu17-keyed-ack-correlation` | PASS (96s) |  |
| A re-run after auth, 41a77949 | `yu10-fix-session` | PASS (17s) |  |
| B (destructive), 41a77949 | `yu18-console-run-scope` | PASS (127s) |  |
| B (destructive), 41a77949 | `yu18-desk` | FAIL (112s) | Suspect 16 (soft): a pre-send refusal labelled "Outcome unknown". First B run used the earlier proof version (no working row); C is the final. |
| B (destructive), 41a77949 | `yu18-control-republish` | PASS (121s) |  |
| B (destructive), 41a77949 | `yu18-projection-effect-end+NEGATIVE_1` | PASS (51s) |  |
| B (destructive), 41a77949 | `yu18-cdm-instruments` | PASS (55s) |  |
| B (destructive), 41a77949 | `yu18-bridge-receipts` | PASS (21s) |  |
| B (destructive), 41a77949 | `yu18-jetstream-feed` | PASS (62s) |  |
| B (destructive), 41a77949 | `yu18-extract-off-projection` | PASS (32s) |  |
| B (destructive), 41a77949 | `yu08-algo-slicing` | PASS (54s) |  |
| B (destructive), 41a77949 | `yu18-swap-capacity` | PASS (35s) |  |
| B (destructive), 41a77949 | `yu17-halt-survives-failover` | PASS (111s) |  |
| B (destructive), 41a77949 | `yu17-closed-survives-restart` | PASS (114s) |  |
| B (destructive), 41a77949 | `yu18-member-runtime` | FAIL (0s) | B: PROOF BUG (step 01 still expected the ephemeral volume ADR-087 removed); fixed, PASS in C. |
| B (destructive), 41a77949 | `yu13-gke-replace-proof` | PASS (323s) |  |
| B (destructive), 41a77949 | `yu12-gke-failover-transparency` | PASS (108s) |  |
| B (destructive), 41a77949 | `yu12-gke-recovery` | PASS (97s) |  |
| B (destructive), 41a77949 | `yu12-gke-cross-epoch-idreuse` | PASS (86s) |  |
| B (destructive), 41a77949 | `yu18-member-disk-survives` | PASS (53s) | FR-GMR-10: members 1+2 return on the same claims at the same applied/hashes (suspect 15 closed). |
| B (destructive), 41a77949 | `yu18-member-disk-survives+MODE_delete` | PASS (22s) | FR-GMR-10: members 1+2 return on the same claims at the same applied/hashes (suspect 15 closed). |
| B (destructive), 41a77949 | `yu18-minority-no-commit` | PASS (103s) | SIGSTOP minority: FR-AC12 holds and admission returns (suspect 15 closed). |
| C (fixed proofs), 41a77949 | `yu18-member-runtime+STORAGE_CLASS_pa-hyperdisk` | PASS (44s) |  |
| C (fixed proofs), 41a77949 | `yu18-desk` | FAIL (45s) | Suspect 16 (soft): a pre-send refusal labelled "Outcome unknown". First B run used the earlier proof version (no working row); C is the final. |
| C (fixed proofs), 41a77949 | `yu18-desk` | FAIL (103s) | Suspect 16 (soft): a pre-send refusal labelled "Outcome unknown". First B run used the earlier proof version (no working row); C is the final. |
| FIX, gateway 071cde6d | `yu18-fix-session` | PASS (40s) | Suspects 7-10 and 12 CLOSED (step 8 asserts no password in any gateway log). |
| FIX, gateway 071cde6d | `yu18-fix-resume` | PASS (28s) |  |
| FIX, gateway 071cde6d | `yu18-fix-fill-feed` | FAIL (106s) | FIX: PROOF BUG: it deleted trade-processor's durable /orders consumer too, stalling the order read model (and the two FIX proofs after it); fixed to spare durables, PASS in the re-run. |
| FIX, gateway 071cde6d | `yu10-fix-session` | FAIL (18s) | A: FIX tunnel refused once (not reached). PASS on re-run and on 071cde6d. |
| FIX, gateway 071cde6d | `yu18-fix-store-faults` | FAIL (195s) | FIX: failed only on the read-model stall above; PASS in the re-run. |
| FIX re-run, gateway 071cde6d | `yu18-fix-fill-feed` | PASS (58s) |  |
| FIX re-run, gateway 071cde6d | `yu10-fix-session` | PASS (17s) |  |
| FIX re-run, gateway 071cde6d | `yu18-fix-store-faults` | PASS (48s) |  |
| FIX negative, gateway 071cde6d | `yu18-fix-resume+NEGATIVE_1` | PASS (14s) | Store unset: B restarts at 1 (the TD-AC01 behaviour), as the control requires. |

## Round 2 (2026-10-27 evening, tip `40d93f6b`)

Same rules as round 1: black-box, assertions taken from the specs, interface material (contracts,
manifests, env names, the deploy scripts) used only for how to reach the system. The 40d93f6b tip adds
FIX logon auth (a961d89a), SIGTERM forwarding (334e9c45), the GKE control feed (61fafad4), the recon grace
(6ef26b36), the durable feed on the production render (40d93f6b) and the receipt-publish spec (ADR-083,
FR-DRS-11..25). "Before" below is round 1's end state, re-counted on the 40d93f6b spec packs (16 new
requirement IDs: FR-DRS-11..25, FR-GMR-07).

**Coverage: 197 → 218 of 320 live-provable requirements (62% → 68%).** 21 rows newly covered: YU10 +4,
YU12 +1, YU13 +2, YU16 +4, YU17 +1, YU18 +9 (29 → 38 of 122).

### Live run, round 2

All on a fresh side namespace `traderx-proofaudit` on `traderx-demo`, images `pa-40d93f6b` (12, built from an
empty-root render on throwaway VM `proofaudit-build-20261027b`, c4d-standard-16, 8:04–8:12 pm ET, deleted),
on pools created for the run and deleted after it: `pa-engine` (3× c4d-standard-8) and `pa-support`
(2× e2-standard-8), 8:10–10:11 pm ET. Production `traderx`, its pools and the `order-matcher-archive`
StorageClass were not touched (kubectl guard). Rig accommodations: the DB user's password is the kind
default (`traderx`) so the inherited literals work; `auth-secrets` minted locally with every key the render
requires, including `fix-session-password`.

**62 runs: 55 pass, 6 fail, 1 honest skip.** The 6 failures are suspects (new 7–15; open 4 and 5), and no
proof bug is left open. Round-1 suspects 1, 2, 3 and 6 are closed on this tip. The table follows the findings.

### Proofs added in round 2

| Proof | Covers | Result on 40d93f6b |
|---|---|---|
| [`yu18-operator-recovery.sh`](yu18-operator-recovery.sh) | FR-ER08; FR-ER01/06, NFR-ER02 refusals where reachable | PASS (FR-ER08 + no write); refusals SKIP: tier-closed |
| [`yu18-fix-session.sh`](yu18-fix-session.sh) + `.mjs` | FR-ERH-S3, FR-FIX04/05/10/11/12 | FAIL: S3, FIX10, FIX12(b) and the effect end pass; suspects 7–10. PASS on fp-7ea8db5c (ADR-086 rig, 2026-10-28) |
| [`yu18-fix-resume.sh`](yu18-fix-resume.sh) + `.mjs` | FR-FIX04/11/12(d) across replicas, ADR-086 | added after round 2; PASS on fp-7ea8db5c, and `NEGATIVE=1` without the store fails it (B's Logon at 1) |
| [`yu18-fix-store-faults.sh`](yu18-fix-store-faults.sh) + `.mjs` | FR-GFS-04, FR-GFS-09 (ADR-086): SIGSTOP takeover with fencing, NATS outage | added after round 2, DESTRUCTIVE=1, side namespaces only; PASS on fp-533ad3c7 (takeover fenced, outage drop 1.6 s, resume) |
| [`yu18-market-order.sh`](yu18-market-order.sh) | FR-LOB04/05 | FAIL: suspect 11 (PRICE_MISSING arm); the rest passes |
| [`yu18-cdm-instruments.sh`](yu18-cdm-instruments.sh) | FR-CDM07/08/23/24 | PASS (both arms) |
| [`yu18-swap-capacity.sh`](yu18-swap-capacity.sh) | FR-OTC09 | PASS: 8 + 4088 = 4096, then CAPACITY ×4 |
| [`yu18-bridge-receipts.sh`](yu18-bridge-receipts.sh) + [`lib-jetstream.py`](lib-jetstream.py) | FR-DRS-06/11/12/13/14/17/24 | PASS at P=1 and at P=4 (two partitions) |
| [`yu18-minority-no-commit.sh`](yu18-minority-no-commit.sh) | FR-AC12 | FR-AC12 PASS; step 4 FAILS: suspect 15 |

### Proof bugs fixed in round 2 (all verified on the rig)

- `yu13-gke-replace-proof`: the TAQ tape gates now honour `REPLAY_RETIRED=1`, and `/replace` carries the
  bearer. PASS.
- `yu13-cancel-ingress`: on a registry-backed cluster the forward claim runs against the image the gateway
  already serves; the regression half needs `IMAGE_PRE` named. PASS.
- `yu13-otel-reject-trace-log-join`: Grafana's password is read from the rig's `grafana-credentials`. The
  401 is gone; it still fails on suspect 5.
- `yu10-fix-session`: Logon carries the rig's `fix-session-password` when one exists (FR-ERH-S3 supersedes
  FR-FIX07's JWT). PASS, 10,282 FIX lifecycles.
- `yu18-jetstream-feed`: on exit it restores trade-processor's own env. Unsetting it would have dropped
  the render's `PROJECTION_FEED_MODE=jetstream`.
- `lib-yu18.sh`: `pf … tcp` for ports that speak no HTTP (FIX, NATS).

### Operator recovery: the rig it needs (tier gap, FR-ER01..07)

The positive catch-up path cannot run on the CompSci GKE render, and on it even the refusals cannot be
told apart: `POST /v2/projection-recovery/catch-up` answers 401 with the risk-control token, an operator
header AND an admin bearer, because the render gives trade-processor no `RISK_CONTROL_TOKEN`.
`projection_recovery` does not exist either (`ri06-event-recovery.sql` is not applied). The route fails
closed, which is safe. A rig that proves FR-ER01..07 needs:

1. a managed epoch-v1 run: the same `run-identity.json` on every member's storage root
   (`recovery-identity/provision.py --offline`), and `RUN_DESCRIPTOR_PATH` on members and gateways;
2. `ri06.sql` and `ri06-event-recovery.sql` applied to the retained database;
3. `RISK_CONTROL_TOKEN` on trade-processor, equal to the gateway's;
4. `RECON_BLOTTER_CAPACITY` on the members and the complete archive from genesis;
5. a gateway reachable from trade-processor at a loopback origin (NFR-ER02), e.g. a sidecar;
6. a registered, selected scope: `transition.py adopt-legacy`, then a transition from a frozen
   predecessor, so two clusters (old and fresh) for the length of the setup.

Then the proof: scale trade-processor to 0, book a cross on the core feed (the loss), restore, call
catch-up, and assert SQL trades/orders/positions converge to the archive with one checkpoint; plus the
refusals of FR-ER02/03/07 (truncated archive, conflicting retained row, unexplained balance) leaving SQL
and checkpoints unchanged.

### Coverage by state (round 2)

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
| YU10 | 13 | 9 | 13 | 0 | 0 |
| YU11 | 0 | 0 | 0 | 0 | 27 |
| YU12 | 14 | 13 | 14 | 0 | 3 |
| YU13 | 16 | 14 | 16 | 0 | 1 |
| YU14 | 5 | 5 | 5 | 0 | 2 |
| YU15 | 19 | 17 | 17 | 0 | 0 |
| YU16 | 26 | 17 | 21 | 0 | 40 |
| YU17 | 35 | 34 | 35 | 0 | 8 |
| YU18 | 122 | 29 | 38 | 41 | 190 |
| **all** | **320** | **197 (62%)** | **218 (68%)** | 41 | 317 |

### Findings, round 2

### New suspects (round 2; the spec and the system disagree, cause not judged here)

7. **FR-FIX04 — OrderStatusRequest has no read model on the GKE gateway.** Expected: `35=H` answered by
   `ExecutionReport ExecType=I` with the order's state. Observed (every run): `35=8 150=I 39=8 37=NONE
   58="order status unavailable (no read model configured)"` for a resting order (`ord-21`, SQL `NEW`) and
   again after it filled (SQL `FILLED`). No manifest under `specs/*/generation/kubernetes/**` sets a
   read-model or status variable for the gateway, so nothing in the render could configure one.
8. **FR-FIX05 — no fill ExecutionReports reach the FIX session.** *Fixed by YU18 ADR-084: fills are read
   from the leader's `/orders` feed, because the egress record carries no fill quantity or price.* A resting FIX Sell and a crossing FIX Buy
   on the same session: both get `ExecType=0 New`; neither gets a fill report within 15 s, though SQL shows
   both orders `FILLED`, two trade rows at the resting price, and every member's `trades` moved by 2.
9. **FR-FIX11 — the session sequence restarts on reconnect.** With one gateway replica, after an abrupt TCP
   drop (no Logout) and a Logon without `ResetSeqNumFlag`, continuing our own `MsgSeqNum`, the acceptor's
   Logon came back at `MsgSeqNum 1` (it had sent up to 7). A clean Logout reconnect does the same. So no
   ResendRequest can re-deliver the earlier reports. TD-AC01 puts session state "on the gateway instance",
   and this was the same instance.
   **CLOSED 2026-10-28 (ADR-086):** sessions persist in JetStream and resume on any replica;
   `yu18-fix-session` and `yu18-fix-resume` pass on `fp-7ea8db5c`. Suspect 7 closed by the same change
   (`ORDER_READMODEL_URL` on the GKE gateway), suspect 10 by refusing an unresolvable ticker as
   `UNKNOWN_SECURITY` instead of answering nothing.
   **Open, found while proving it:** `OrderStatusRequest` for a CANCELED order reports `CumQty = qty − remaining`,
   so a cancel that filled nothing shows the whole quantity filled (`yu18-fix-resume` step 5: S2 Sell 3,
   canceled unfilled, answered `OrdStatus=4 CumQty=3`). Pre-existing in `statusReportFor` (YU13); not fixed.
10. **FR-FIX12(c) — an unknown symbol is not answered.** `D` with `55=ZZNOSUCH9` gets no message of any kind
    in 20 s. The gateway logs `FIX order <clOrdId> ambiguous (no committed ack)`, i.e. it classed an
    application reject as outcome (d). The spec wants `ExecutionReport OrdStatus=Rejected`. (b) is correct:
    a `D` without `Side(54)` gets `Reject(3) RefTagID=54 "Required tag missing"`.
11. **FR-LOB04 — a market order on an untraded, empty book is accepted, not refused PRICE_MISSING.** Fresh
    ticker seeded by `/seed` (a tick: no trade), empty book. Market Buy 4 → `200 {"kind":1}`; SQL then holds
    it `CANCELED`, `remainingquantity 0`, nothing booked. The spec says: priced at the last trade, else the
    opposite best, else PRICE_MISSING. Here it was validated against something else, most likely the
    tick-seeded mark (FR-LOB05 lets a tick seed the mark until the first trade). Either the engine uses the
    mark or the spec should name it. The rest of FR-LOB04/05 passes: fill at the resting price, remainder
    cancelled in place, never rests, a tick through a resting Buy fills nothing.
12. **The FIX logon password is written to the gateway log in clear.** A Logon from a CompID outside
    `FIX_SESSION_AUTH` is logged by QuickFIX/J as `Disconnecting; received message for unknown session:
    8=FIX.4.4…49=NOTBOUND…554=<the CLIENT1 password>…`, and the gateway logs go to Loki. ADR-082 S3 says the
    console "SHALL NOT return it to the browser"; no spec line covers logs, so this is a suspect against the
    intent of S3, not a clause. The password was a throwaway on a deleted rig.

13. **The recon blotter carries bond prices at 3 dp, so every bond side is FIELD_MISMATCH (YU05 recon vs
    YU16 ADR-057 / NFR-CDM08).** `yu05-recon`: `field_mismatch=6` on a fresh classification. The six are the
    `yu16-bond-position` sides: member `GET /recon/trades/blotter` has `UST-20310630 price 0.997`,
    `UST-BILL-20270812 0.96`, `CORP-GS-20360315 0.991`; SQL `trades` has 0.996650 / 0.959560 / 0.991230, the
    6-dp fraction of par the YU16 contract stores. NFR-CDM08 requires exact bond arithmetic in the read model;
    the blotter, the source the recon compares against, has lost three digits.
14. **`missingTradeSides` reads 0 while two engine-booked sides never committed (FR-DRS-02, 2026-10-27 text).**
    `yu15-option-persistence` deliberately narrows the symbol columns, so its "before" option fill
    (27-S/28-B, `AAPL260918C00260000`) is refused by MariaDB and never reaches SQL `trades`. After the next
    trade-processor restart (yu05-recon's own `rollout restart`), `/v2/projection-feed` reports
    `missingTradeSides 0` and `projection_cursor` holds `trade-seq:` at 53: the watermark advanced past sides
    that never committed. FR-DRS-02 now says the count SHALL equal "the trade sides the engine booked in the
    current epoch that never committed to SQL `trades`, and SHALL NOT read 0 while any such side exists". The
    recon does see them (`missing_in_projection=2`), so the loss is visible, just not where FR-DRS-02 puts it.
15. **After a quorum loss and return, the cluster commits nothing until every member restarts (YU12
    FR-AC11/13).** `yu18-minority-no-commit`: FR-AC12 itself holds (member 0 alone: never LEADER, applied
    25046 unchanged for 45 s, the window's order 504). With the StatefulSet back at 3: member 0 LEADER after a
    normal election (`ROLE-CHANGE role=LEADER`), 1 and 2 FOLLOWER, all three `applied 25046`, phase OPEN, but
    every order answers `504 no committed ack` for over 20 minutes. The gateway goes unready, and its liveness
    never fires (`/live` noAckStreak 32 of a 100 limit, starved once readiness evicts it, as in the open
    gateway-wedge issue). A fresh gateway pod is equally unready, so the gateway is not the cause. A rolling
    restart of all three members restores admission at once. Evidence (member logs and metrics, gateway log)
    is in the run directory.

### Round-1 suspects re-run on 40d93f6b

- **Suspect 1 (FR-GMR-04, member SIGTERM): CLOSED.** A deleted follower exits 143 in 0 s (grace 10 s) and
  rejoins at the leader's applied sequence; the launch shell now traps TERM and forwards it (334e9c45).
- **Suspect 2 (YU04 control feed on GKE): CLOSED.** `yu04-live-delta`, `yu04-offline-catchup`,
  `yu17-book-retick` and `yu15-option-persistence` all PASS on 40d93f6b (61fafad4, FR-GMR-07).
- **Suspect 3 (FR-DRS-02 across an outage): CLOSED on both feeds.** Production render (jetstream): 6 of 6
  sides replayed, positions +15/-15 equal to the rows, `missingTradeSides 0` (SC-DRS-05). Core feed: 4 of 6,
  `missingTradeSides 2`. (Suspect 14 is a different path: a side refused by the DB, not one never delivered.)
- **Suspect 4 (recon run descriptor):** unchanged; `yu05-recon`'s scoped control still refuses.
- **Suspect 6 (leader election under a leader kill): did not reproduce.** `yu17-keyed-ack-correlation` PASS.
- **Suspect 5 (ADR-060 trace id): still reproduces.** `yu13-otel-trace-join` 3/3 traces 404 under the
  derived id; `yu13-otel-reject-trace-log-join` the same for the rejected order (its Grafana sign-in now
  reads the rig's own Secret).

### New spec gaps (round 2)

13. **FR-FIX10 says a duplicate ClOrdID "SHALL be rejected"; YU12's snapshot matrix says a retried key
    "answers original ref".** Live, on the cluster tier, the resend is answered with the ORIGINAL order
    (`ExecType=0`, same OrderID), and no second order exists. The proof asserts the effect end (one order)
    and records which answer it got; the two packs need one rule.
14. **FR-FIX07 vs FR-ERH-S3.** YU10 has `Password(554)` carry a JWT; YU18 S3 binds it to a static
    per-CompID password. On GKE only the password works (`yu10-fix-session` was stale for exactly that and
    now reads the rig's `fix-session-password`). YU10 should say it is superseded.
15. **Operator recovery has no deployable tier.** FR-ER01..07 require a managed epoch-v1 run, the ri06
    migrations and loopback-only origins (NFR-ER02). The CompSci render gives trade-processor no
    `RISK_CONTROL_TOKEN` and applies no `ri06-event-recovery.sql` (`projection_recovery` is absent), so the
    route is 401 for every caller, credentialed or not. That is fail-closed and consistent with the docs
    ("nothing enabled on any GKE profile"), but no spec says which tier the requirement is for.
16. **FR-OTC09 credit.** "A refused booking consumes no credit" names no observable for the credit an OTC
    booking consumes, so no live proof can assert it.
17. **FR-DRS-15/16 have no way in on a live tier.** They need a stream-ingest 429; the GKE queue
    (2,000,000 msgs) is sized so a demo-scale burst never trips it, and no spec names a test knob.

### Run table, round 2

| Proof | Result | Triage |
|---|---|---|
| `yu03-risk-proof` | PASS (4s) |  |
| `yu05-auth-entitlements` | PASS (5s) |  |
| `yu05-settlement` | PASS (10s) |  |
| `yu13-clordid-suppression` | PASS (22s) |  |
| `yu10-fix-session` | PASS (18s) | PASS after a proof fix (FR-ERH-S3 password in 554). |
| `yu13-readmodel-effect-end` | PASS (25s) |  |
| `yu05-regulatory-reproducible` | PASS (1s) |  |
| `yu04-live-delta` | PASS (2s) | Suspect 2 CLOSED. |
| `yu04-offline-catchup` | PASS (19s) | Suspect 2 CLOSED. |
| `yu15-option-persistence` | PASS (35s) | Suspect 2 CLOSED. |
| `yu17-book-retick` | PASS (9s) | Suspect 2 CLOSED. |
| `yu13-otel-trace-join` | FAIL (26s) | SUSPECT 5 (unchanged): 3/3 traces 404 under the derived id. |
| `yu13-otel-reject-trace-log-join` | FAIL (229s) | SUSPECT 5; the Grafana 401 proof bug is fixed. |
| `yu06-quality-gate` | PASS (51s) |  |
| `yu06-consumer-halt` | PASS (42s) |  |
| `yu16-treasury-pricing` | PASS (2s) |  |
| `yu16-bond-position` | PASS (32s) |  |
| `yu16-accrued-interest` | PASS (4s) |  |
| `yu17-session-opens-from-close` | SKIP (1s) | PRECONDITION: needs a published session before today; a fresh epoch has none. Honest skip. |
| `yu16-book-grid` | PASS (131s) |  |
| `yu17-band-follows-market (EXPECT=after)` | PASS (19s) |  |
| `yu17-option-collar` | PASS (4s) |  |
| `yu17-fnma-collar` | PASS (4s) |  |
| `yu17-fine-grid` | PASS (6s) |  |
| `yu17-retick-determinism` | PASS (244s) |  |
| `yu17-session-closed-rejects` | PASS (7s) |  |
| `yu17-preopen-queue-open` | PASS (26s) |  |
| `yu17-swap-netting` | PASS (50s) |  |
| `yu17-swaption-terms` | PASS (16s) |  |
| `yu17-fx-credit` | PASS (44s) |  |
| `yu16-ready-tracks-commit` | PASS (78s) |  |
| `yu16-liveness-restarts-wedge` | PASS (225s) |  |
| `yu18-gateway-auth-boundary` | PASS (30s) |  |
| `yu18-cluster-ingress-auth` | PASS (113s) |  |
| `yu18-console-sessions` | PASS (11s) | PASS (first try lacked CONSOLE_PASSWORD in my driver). |
| `yu18-legacy-trade-bearer` | PASS (17s) |  |
| `yu18-projection-effect-end` | PASS (15s) |  |
| `yu18-fix-session` | FAIL (72s) | NEW. S3, FIX10, FIX12(b), effect end PASS; SUSPECTS 7, 8, 9, 10 (and 12 from its gateway log). |
| `yu18-market-order` | FAIL (40s) | NEW. SUSPECT 11 (PRICE_MISSING); fill/cancel-remainder/never-rests/tick PASS. |
| `yu18-cdm-instruments` | PASS (17s) |  |
| `yu18-operator-recovery` | PASS (7s) |  |
| `yu15-risk-extract` | PASS (47s) | PASS after the fixture sweep (first try halted=3 on my own minted tickers: run order). |
| `yu13-cancel-ingress` | PASS (18s) | PASS after a proof fix (registry images); regression half skips: no pre-cancel image. |
| `yu05-recon` | FAIL (22s) | SUSPECT 13 (bond blotter 3 dp, field_mismatch=6) + SUSPECT 4 (scoped control refuses); missing=2 is yu15-option-persistence's own demo pair, which shows SUSPECT 14. |
| `yu17-keyed-ack-correlation` | PASS (95s) | Suspect 6 did not reproduce. |
| `yu17-halt-survives-failover` | PASS (86s) |  |
| `yu17-closed-survives-restart` | PASS (245s) |  |
| `yu12-gke-recovery` | PASS (99s) |  |
| `yu12-gke-failover-transparency` | PASS (111s) |  |
| `yu12-gke-cross-epoch-idreuse` | PASS (58s) |  |
| `yu13-gke-replace-proof` | PASS (219s) | PASS after two proof fixes (retired tape gates, /replace bearer). |
| `yu18-member-runtime (DESTRUCTIVE=1)` | PASS (47s) | Suspect 1 CLOSED: exit 143 in 0 s, rejoined. |
| `yu18-jetstream-feed` | PASS (54s) | PASS; restore now keeps the render's jetstream mode (proof fix). |
| `yu18-extract-off-projection` | PASS (35s) |  |
| `yu08-algo-slicing` | PASS (55s) |  |
| `yu18-projection-effect-end (NEGATIVE=1)` | PASS (53s) | Production render: 6/6 replayed, +15/-15, missing 0 (FR-DRS-25). |
| `yu18-cdm-instruments (DESTRUCTIVE=1)` | PASS (62s) |  |
| `yu18-bridge-receipts` | PASS (25s) |  |
| `yu18-projection-effect-end (NEGATIVE=1), core feed` | PASS (45s) | Core feed: 4/6, missingTradeSides 2, counted. Suspect 3 CLOSED. |
| `yu18-minority-no-commit (DESTRUCTIVE=1)` | FAIL (511s) | NEW. FR-AC12 PASS; step 4 SUSPECT 15 (no commits after quorum returns until a member roll). |
| `yu18-bridge-receipts (BRIDGE_PARTITIONS=4 on members)` | PASS (23s) |  |
| `yu18-swap-capacity (PRIOR=8)` | PASS (44s) |  |

## Round 1 (2026-10-27 afternoon, tip `b28fd9f3`)

### Coverage before → after (round 1)


Coverage means a proof exists that asserts the row at the effect end; whether it passed on the tip is the
run table below — three covered rows are covered by proofs that FAIL, and those failures are suspects.

YU18 went from **4 of 106** live-provable requirements under a proof to **26**, and the whole carried
lineage from **172 of 304 (57%)** to **194 of 304 (64%)**. The 41 order-type requirements (FR-OT) are
live-tested by `scripts/ri07/order-types-live.py` through the console path and are not duplicated here.
The 317 CI rows are build, generation, admission, offline-tool and perf requirements; they belong to
`test-state-*`, unit suites, `test_prepare.py` and the bench, and a live proof would add nothing.

### Proofs added by this audit

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

### Existing proofs changed (proof bugs, not behaviour)

| Script | Change | Why |
|---|---|---|
| `yu03-risk-proof.sh` | sends an admin bearer; an `{"error":…}` body is read as ERROR, not NEW | since ADR-082 S1 every order answered 401 and the parser read "no reason" as NEW: 5 of 13 controls "failed" on orders the venue never saw |
| `yu13-readmodel-effect-end.sh`, `yu13-clordid-suppression.sh`, `yu13-cancel-ingress.sh`, `yu13-otel-trace-join.sh`, `yu13-otel-reject-trace-log-join.sh`, `yu12-gke-recovery.sh`, `yu12-gke-failover-transparency.sh`, `yu12-gke-cross-epoch-idreuse.sh`, `yu13-gke-replace-proof.sh`, `yu12-gke-restore-from-gcs.sh`, `seed-option-chain.sh` | gateway calls carry the `lib-gateway-auth.sh` bearer | 401 since ADR-082 S1 (`/seed`: "operator credentials required") |
| `lib-gateway-auth.sh` | reads `auth-secrets/dev-token-master-secret` before the kind literal | the CompSci/GKE profile generates the secret; the literal fails as a bare 401 |
| `yu05-common.sh`, `recon-proof-subject.py` | honour `NS` instead of a hard-coded `-n traderx` | on `traderx-demo` that is production; the recon helper pauses trade-processor and resets a recon checkpoint |
| `scripts/yu15/seed-proof-fixtures.sh` | `PA` added to the throwaway-ticker sweep | the yu18 proofs mint `PA<digits>`; their positions otherwise halt the EOD P&L (FR-EOD32) for later proofs |
| `yu18-networkpolicy-enforced.sh` | proof client limited to one probe order; whole-log grep | the client sequences a real resting order every 10 ms (3,285 resting AAL buys in 60 s on the audit rig) and the rollout doc pointed it at production; `logs \| grep -q` under pipefail read a long log as "never tried" |
| `lib-gke-replay-gates.sh` | explicit `REPLAY_RETIRED=1` waiver, printed on every gate it passes | the five GKE recovery/failover proofs refuse without the TAQ tape, retired 2026-10-27 |

### Findings from the live run

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

### Live run record (2026-10-27, 3:25–6:30 pm ET)

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

## Ranked gap list (live-provable, still uncovered after round 3)

| Rank | Risk class | State | Requirement | Why uncovered |
|---|---|---|---|---|
| 1 | money/positions | YU18 | `FR-APR01` | disabled by default; disposable profile only |
| 2 | money/positions | YU18 | `FR-APR02` | disabled by default; disposable profile only |
| 3 | money/positions | YU18 | `FR-APR03` | disabled by default; disposable profile only |
| 4 | money/positions | YU18 | `FR-APR04` | disabled by default; disposable profile only |
| 5 | money/positions | YU18 | `FR-APR05` | disabled by default; disposable profile only |
| 6 | money/positions | YU18 | `FR-APR06` | disabled by default; disposable profile only |
| 7 | money/positions | YU18 | `FR-APR07` | disabled by default; disposable profile only |
| 8 | money/positions | YU18 | `FR-APR08` | disabled by default; disposable profile only |
| 9 | money/positions | YU18 | `FR-APR09` | disabled by default; disposable profile only |
| 10 | money/positions | YU18 | `FR-APR10` | disabled by default; disposable profile only |
| 11 | money/positions | YU18 | `FR-APR11` | disabled by default; disposable profile only |
| 12 | money/positions | YU18 | `FR-DRS-15` | needs a forced ingest 429; not forced |
| 13 | money/positions | YU18 | `FR-DRS-16` | needs a forced ingest 429; not forced |
| 14 | money/positions | YU18 | `FR-DRS-18` | /recon/catchup-events needs a managed run (FR-APR) |
| 15 | money/positions | YU18 | `FR-DRS-19` | snapshot-mode only; the production render is rows (FR-DRS-25) |
| 16 | money/positions | YU18 | `FR-DRS-20` | snapshot-mode only; the production render is rows (FR-DRS-25) |
| 17 | money/positions | YU18 | `FR-DRS-21` | snapshot-mode only; the production render is rows (FR-DRS-25) |
| 18 | money/positions | YU18 | `FR-DRS-23` | snapshot-mode only; the production render is rows (FR-DRS-25) |
| 19 | money/positions | YU18 | `FR-ER01` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 20 | money/positions | YU18 | `FR-ER02` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 21 | money/positions | YU18 | `FR-ER03` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 22 | money/positions | YU18 | `FR-ER04` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 23 | money/positions | YU18 | `FR-ER05` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 24 | money/positions | YU18 | `FR-ER06` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 25 | money/positions | YU18 | `FR-ER07` | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| 26 | risk decisions | YU03 | `FR-IMRG07` | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| 27 | risk decisions | YU03 | `FR-IMRG09` | stale-price classification: no live arm |
| 28 | risk decisions | YU03 | `FR-IMRG19` | gateway/BLP disagreement counted: no live arm (needs a stale replica) |
| 29 | risk decisions | YU03 | `FR-IMRG26` | table exhaustion -> CAPACITY: no live arm (ERH-F7 same) |
| 30 | risk decisions | YU03 | `FR-IMRG44` | UI shows rejection reason: no UI proof |
| 31 | risk decisions | YU08 | `FR-AE03` | VWAP weighting / empty-profile fallback: no live arm |
| 32 | risk decisions | YU08 | `FR-AE09` | VWAP weighting / empty-profile fallback: no live arm |
| 33 | risk decisions | YU18 | `FR-ERH-F1` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 34 | risk decisions | YU18 | `FR-ERH-F10` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 35 | risk decisions | YU18 | `FR-ERH-F2` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 36 | risk decisions | YU18 | `FR-ERH-F3` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 37 | risk decisions | YU18 | `FR-ERH-F5` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 38 | risk decisions | YU18 | `FR-ERH-F6` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 39 | risk decisions | YU18 | `FR-ERH-F7` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 40 | risk decisions | YU18 | `FR-ERH-F9` | deterministic-core edge cases: unit only (SC-ERH-01) |
| 41 | risk decisions | YU18 | `FR-MAR03` | readout field set: partially read by the new proof |
| 42 | risk decisions | YU18 | `FR-RCM01` | diagnostic metric/readout shape: no live proof (unit) |
| 43 | risk decisions | YU18 | `FR-RCM02` | diagnostic metric/readout shape: no live proof (unit) |
| 44 | risk decisions | YU18 | `FR-RCM03` | diagnostic metric/readout shape: no live proof (unit) |
| 45 | recovery/failover | YU08 | `FR-AE08` | crash-resume replay from JetStream: no live arm (SC-AE06) |
| 46 | recovery/failover | YU18 | `FR-ERH-F11` | needs a purged archive / 65,536 FIX orders; not attempted |
| 47 | recovery/failover | YU18 | `FR-ERH-F8` | needs a purged archive / 65,536 FIX orders; not attempted |
| 48 | recovery/failover | YU18 | `FR-GMR-08` | ready gate / negative DNS TTL: no black-box observable driven |
| 49 | recovery/failover | YU18 | `FR-GMR-09` | ready gate / negative DNS TTL: no black-box observable driven |
| 50 | recovery/failover | YU18 | `FR-RD01` | diagnostic metric/readout shape: no live proof (unit) |
| 51 | recovery/failover | YU18 | `FR-RD02` | diagnostic metric/readout shape: no live proof (unit) |
| 52 | recovery/failover | YU18 | `FR-RD03` | diagnostic metric/readout shape: no live proof (unit) |
| 53 | recovery/failover | YU18 | `FR-RD04` | diagnostic metric/readout shape: no live proof (unit) |
| 54 | EOD/extract | YU15 | `FR-RXT06` | not injectable black-box (the cut travels member->extract over Aeron egress) |
| 55 | EOD/extract | YU15 | `FR-RXT09` | not injectable black-box (the cut travels member->extract over Aeron egress) |
| 56 | EOD/extract | YU18 | `FR-EB29` | console EOD status distinctions: no live proof |
| 57 | security/auth | YU18 | `FR-CS-02` | hosted Desk: not in the CompSci side-namespace render |
| 58 | security/auth | YU18 | `FR-CS-03` | hosted Desk: not in the CompSci side-namespace render |
| 59 | security/auth | YU18 | `FR-ERH-S5` | refuse-to-start on dev secret: no live arm |
| 60 | UI/ops | YU07 | `FR-TS12` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 61 | UI/ops | YU07 | `FR-TS13` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 62 | UI/ops | YU07 | `FR-TS14` | kdb capture tap off the running cluster: no live proof (tap not in CompSci render) |
| 63 | UI/ops | YU16 | `FR-CDM01` | /instruments and /stocks response shapes: no live proof |
| 64 | UI/ops | YU16 | `FR-CDM02` | /instruments and /stocks response shapes: no live proof |
| 65 | UI/ops | YU16 | `FR-CDM03` | /instruments and /stocks response shapes: no live proof |
| 66 | UI/ops | YU16 | `FR-CDM04` | /instruments and /stocks response shapes: no live proof |
| 67 | UI/ops | YU16 | `FR-CDM09` | /instruments and /stocks response shapes: no live proof |
| 68 | UI/ops | YU18 | `FR-AM01` | diagnostic metric/readout shape: no live proof (unit) |
| 69 | UI/ops | YU18 | `FR-AM02` | diagnostic metric/readout shape: no live proof (unit) |
| 70 | UI/ops | YU18 | `FR-AM03` | diagnostic metric/readout shape: no live proof (unit) |
| 71 | UI/ops | YU18 | `FR-AM04` | diagnostic metric/readout shape: no live proof (unit) |
| 72 | UI/ops | YU18 | `FR-AM05` | diagnostic metric/readout shape: no live proof (unit) |
| 73 | UI/ops | YU18 | `FR-AM06` | diagnostic metric/readout shape: no live proof (unit) |
| 74 | UI/ops | YU18 | `FR-CFR-03` | stream ahead of the outbox / broker down mid-audit: not driven |
| 75 | UI/ops | YU18 | `FR-CFR-05` | stream ahead of the outbox / broker down mid-audit: not driven |
| 76 | UI/ops | YU18 | `FR-CUI-03` | TIER GAP: needs a managed run (historical runs, descriptor-fenced commands) |
| 77 | UI/ops | YU18 | `FR-CUI-18` | TIER GAP: needs a managed run (historical runs, descriptor-fenced commands) |
| 78 | UI/ops | YU18 | `FR-FFR-06` | race / 65,536 cap: unit-level only |
| 79 | UI/ops | YU18 | `FR-FFR-10` | race / 65,536 cap: unit-level only |
| 80 | UI/ops | YU18 | `FR-GFS-02` | full store / fresh-epoch purge: not driven |
| 81 | UI/ops | YU18 | `FR-GFS-13` | full store / fresh-epoch purge: not driven |
| 82 | UI/ops | YU18 | `FR-MUI10` | Admin trade list: not driven (no admin-page proof yet) |

## Appendix: requirement matrix (round 3)

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
| YU03 | `FR-IMRG08` | LIVE | yu03-risk-proof yu18-gateway-auth-boundary |  | unknown security refused; S4 lookup-only |
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
| YU03 | `FR-IMRG30` | LIVE | yu03-risk-proof yu18-gateway-auth-boundary |  | operator pair on /risk/control, /seed |
| YU03 | `FR-IMRG40` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG41` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG42` | LIVE | yu13-clordid-suppression |  |  |
| YU03 | `FR-IMRG43` | CI |  |  | CI/static/offline |
| YU03 | `FR-IMRG44` | LIVE |  |  | UI shows rejection reason: no UI proof |
| YU03 | `FR-IMRG45` | CI |  |  | CI/static/offline |
| YU04 | `FR-IMRG-OUTBOX-ORDER` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG03` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG04` | LIVE | yu04-live-delta yu04-offline-catchup |  | PASS on 40d93f6b (61fafad4 wired the GKE control feed; suspect 2 closed) |
| YU04 | `FR-IMRG05` | LIVE | yu04-live-delta yu04-offline-catchup |  | PASS on 40d93f6b (61fafad4 wired the GKE control feed; suspect 2 closed) |
| YU04 | `FR-IMRG32` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU04 | `FR-IMRG33` | LIVE | yu04-live-delta yu04-offline-catchup |  | PASS on 40d93f6b (61fafad4 wired the GKE control feed; suspect 2 closed) |
| YU04 | `FR-IMRG34` | CI |  |  | epoch/version/gap quarantine: unit (ControlFeedBootstrapStateTest) |
| YU05 | `FR-IMRG41` | CI |  |  | CI/static/offline |
| YU05 | `FR-PTC01` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC02` | LIVE | yu05-settlement |  |  |
| YU05 | `FR-PTC03` | LIVE | yu05-recon |  |  |
| YU05 | `FR-PTC04` | LIVE | yu05-recon |  | yu05-recon FAILS: suspect 13 (bond blotter 3 dp) + suspect 4 |
| YU05 | `FR-PTC05` | LIVE | yu05-recon |  | yu05-recon FAILS: suspect 13 (bond blotter 3 dp) + suspect 4 |
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
| YU06 | `FR-EOD21` | LIVE | yu15-risk-extract yu18-extract-off-projection |  |  |
| YU06 | `FR-EOD22` | LIVE | yu15-risk-extract yu18-extract-off-projection |  |  |
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
| YU10 | `FR-FIX04` | LIVE | yu18-fix-session | yu18-fix-session yu18-fix-resume | PASS: suspects 7, 8, 9, 10 closed |
| YU10 | `FR-FIX05` | LIVE | yu10-fix-session yu18-fix-session | yu18-fix-session yu18-fix-resume | PASS: suspects 7, 8, 9, 10 closed |
| YU10 | `FR-FIX06` | LIVE | yu13-cancel-ingress |  | yu13-fix-cancel.mjs |
| YU10 | `FR-FIX07` | LIVE | yu10-fix-session |  | logon; superseded by ERH-S3 FIX_SESSION_AUTH |
| YU10 | `FR-FIX08` | LIVE | yu10-fix-session |  | logon; superseded by ERH-S3 FIX_SESSION_AUTH |
| YU10 | `FR-FIX09` | LIVE | yu10-fix-session |  |  |
| YU10 | `FR-FIX10` | LIVE | yu18-fix-session |  | resend answers the ORIGINAL order (spec says "rejected": spec gap 13) |
| YU10 | `FR-FIX11` | LIVE | yu18-fix-session | yu18-fix-session yu18-fix-resume | PASS: suspects 7, 8, 9, 10 closed |
| YU10 | `FR-FIX12` | LIVE | yu18-fix-session | yu18-fix-session yu18-fix-resume | PASS: suspects 7, 8, 9, 10 closed |
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
| YU12 | `FR-AC12` | LIVE | yu18-minority-no-commit |  | PASS with the SIGSTOP minority (41a77949); after SIGCONT the cluster commits again (suspect 15 closed) |
| YU12 | `FR-AC13` | LIVE | yu12-gke-failover-transparency failover-nodeclock |  |  |
| YU12 | `FR-AC14` | LIVE | yu13-readmodel-effect-end yu18-projection-effect-end |  |  |
| YU12 | `FR-AC15` | LIVE | yu16-ready-tracks-commit yu16-liveness-restarts-wedge |  |  |
| YU12 | `FR-AC16` | CI |  |  | CI/static/offline |
| YU12 | `FR-AC17` | LIVE | yu16-ready-tracks-commit yu16-liveness-restarts-wedge |  |  |
| YU13 | `FR-LOB01` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB02` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB03` | LIVE | yu13-readmodel-effect-end yu13-stp-and-replace |  |  |
| YU13 | `FR-LOB04` | LIVE | yu18-market-order |  | PRICE_MISSING arm still FAILS: suspect 11 |
| YU13 | `FR-LOB05` | LIVE | yu18-market-order |  |  |
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
| YU15 | `FR-RXT01` | LIVE | yu15-risk-extract yu18-extract-off-projection |  | trigger amended by ADR-079 §5 to eod.prices.ready |
| YU15 | `FR-RXT02` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT03` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT04` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT05` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT06` | LIVE |  |  | not injectable black-box (the cut travels member->extract over Aeron egress) |
| YU15 | `FR-RXT07` | LIVE | yu15-risk-extract yu16-accrued-interest |  |  |
| YU15 | `FR-RXT08` | LIVE | yu15-risk-extract |  |  |
| YU15 | `FR-RXT09` | LIVE |  |  | not injectable black-box (the cut travels member->extract over Aeron egress) |
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
| YU16 | `FR-CDM07` | LIVE | yu18-cdm-instruments |  |  |
| YU16 | `FR-CDM08` | LIVE | yu18-cdm-instruments |  |  |
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
| YU16 | `FR-CDM23` | LIVE | yu18-cdm-instruments |  | DESTRUCTIVE=1 arm (reference-data scaled to 0) |
| YU16 | `FR-CDM24` | LIVE | yu18-cdm-instruments |  | DESTRUCTIVE=1 arm (reference-data scaled to 0) |
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
| YU17 | `FR-OTC09` | LIVE | yu18-swap-capacity |  | PASS: prior 8 + 4088 booked = 4096, then CAPACITY x4; "no credit consumed" unobservable (spec gap 16) |
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
| YU18 | `ADR-079-S4` | LIVE | yu18-networkpolicy-enforced yu18-cluster-ingress-auth |  | networkpolicy proof is inert without Dataplane V2 |
| YU18 | `ADR-079-S5` | LIVE | yu15-risk-extract yu18-extract-off-projection |  |  |
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
| YU18 | `FR-CFR-01` | LIVE |  | yu18-control-republish | PASS: complete stream untouched; purged and deleted streams refilled from the outbox in version order |
| YU18 | `FR-CFR-02` | LIVE |  | yu18-control-republish | PASS: complete stream untouched; purged and deleted streams refilled from the outbox in version order |
| YU18 | `FR-CFR-03` | LIVE |  |  | stream ahead of the outbox / broker down mid-audit: not driven |
| YU18 | `FR-CFR-04` | LIVE |  | yu18-control-republish | PASS: complete stream untouched; purged and deleted streams refilled from the outbox in version order |
| YU18 | `FR-CFR-05` | LIVE |  |  | stream ahead of the outbox / broker down mid-audit: not driven |
| YU18 | `FR-CPU01` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU02` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU03` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU04` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CPU05` | CI |  |  | local coordinator/engine packaging: ri03/ri07 + test-state |
| YU18 | `FR-CRP-01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CRP-02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CRP-03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-CS-01` | LIVE | yu18-console-sessions |  |  |
| YU18 | `FR-CS-02` | LIVE |  |  | hosted Desk: not in the CompSci side-namespace render |
| YU18 | `FR-CS-03` | LIVE |  |  | hosted Desk: not in the CompSci side-namespace render |
| YU18 | `FR-CS-04` | LIVE | yu18-console-sessions |  |  |
| YU18 | `FR-CS-05` | LIVE | yu18-console-sessions |  |  |
| YU18 | `FR-CUI-01` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-02` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-03` | LIVE |  |  | TIER GAP: needs a managed run (historical runs, descriptor-fenced commands) |
| YU18 | `FR-CUI-04` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-05` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-06` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-07` | LIVE |  | yu18-desk | no fixture fallback + finite deadline: the CUI-20 hung read and the no-account refusal |
| YU18 | `FR-CUI-08` | LIVE |  | yu18-desk | no automatic retry of a refused submit (1 POST); typed-semantics refusal not driven |
| YU18 | `FR-CUI-09` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-10` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-11` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-12` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-13` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-14` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-15` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-16` | LIVE |  | yu18-desk | refused half PASS (draft kept, no retry); acceptance half = managed-run tier gap (spec gap 20) |
| YU18 | `FR-CUI-17` | LIVE |  | yu18-desk | unmanaged arm PASS: submit refused before sending, no cancel/replace; managed arm = tier gap |
| YU18 | `FR-CUI-18` | LIVE |  |  | TIER GAP: needs a managed run (historical runs, descriptor-fenced commands) |
| YU18 | `FR-CUI-19` | LIVE |  | yu18-desk | FAILS: suspect 16 (a pre-send refusal shown as "Outcome unknown"); typed-payload half = tier gap |
| YU18 | `FR-CUI-20` | LIVE |  | yu18-desk | PASS on the hosted Desk (CUI-20 in the DESTRUCTIVE=1 arm) |
| YU18 | `FR-CUI-21` | LIVE |  | yu18-desk | signed-out raw proxy order 403; membership rule is non-admin local-mode (spec gap 19) |
| YU18 | `FR-DA01` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA02` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA03` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA04` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA05` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA06` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DA07` | CI |  |  | tooling/offline/diagnostic pack with its own tests |
| YU18 | `FR-DRS-01` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-02` | LIVE | yu18-jetstream-feed yu18-projection-effect-end |  | NEGATIVE=1 PASS on both feeds (suspect 3 closed); a DB-refused side is not counted: suspect 14 |
| YU18 | `FR-DRS-03` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-04` | LIVE | yu18-projection-effect-end |  | indirect: tip bridge payload accepted end to end |
| YU18 | `FR-DRS-05` | LIVE | yu18-jetstream-feed |  |  |
| YU18 | `FR-DRS-06` | LIVE | yu18-bridge-receipts |  | PASS with BRIDGE_PARTITIONS=4 on the members, two partitions exercised |
| YU18 | `FR-DRS-07` | LIVE | yu18-jetstream-feed |  |  |
| YU18 | `FR-DRS-08` | LIVE | yu18-jetstream-feed |  |  |
| YU18 | `FR-DRS-09` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-10` | CI |  |  | queue bound/batching/flush: unit + bench (downstream-at-400k) |
| YU18 | `FR-DRS-11` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-12` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-13` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-14` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-15` | LIVE |  |  | needs a forced ingest 429; not forced |
| YU18 | `FR-DRS-16` | LIVE |  |  | needs a forced ingest 429; not forced |
| YU18 | `FR-DRS-17` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-18` | LIVE |  |  | /recon/catchup-events needs a managed run (FR-APR) |
| YU18 | `FR-DRS-19` | LIVE |  |  | snapshot-mode only; the production render is rows (FR-DRS-25) |
| YU18 | `FR-DRS-20` | LIVE |  |  | snapshot-mode only; the production render is rows (FR-DRS-25) |
| YU18 | `FR-DRS-21` | LIVE |  |  | snapshot-mode only; the production render is rows (FR-DRS-25) |
| YU18 | `FR-DRS-22` | LIVE | yu05-recon |  | recon grace |
| YU18 | `FR-DRS-23` | LIVE |  |  | snapshot-mode only; the production render is rows (FR-DRS-25) |
| YU18 | `FR-DRS-24` | LIVE | yu18-bridge-receipts |  | FR-DRS-17 only in its no-rejection form |
| YU18 | `FR-DRS-25` | LIVE | yu18-projection-effect-end |  | NEGATIVE=1 PASS on the production render (6/6, +15/-15) |
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
| YU18 | `FR-EB13` | LIVE | yu18-extract-off-projection |  |  |
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
| YU18 | `FR-ER01` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER02` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER03` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER04` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER05` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER06` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER07` | LIVE |  |  | TIER GAP: needs a managed-run rig (see "Operator recovery: the rig it needs"); yu18-operator-recovery skips with the reason |
| YU18 | `FR-ER08` | LIVE | yu18-operator-recovery |  | readers only; the catch-up route is closed on this tier (trade-processor has no RISK_CONTROL_TOKEN) |
| YU18 | `FR-ERH-C1` | LIVE | yu18-console-sessions |  |  |
| YU18 | `FR-ERH-C2` | LIVE | yu18-legacy-trade-bearer |  |  |
| YU18 | `FR-ERH-F1` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F10` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F11` | LIVE |  |  | needs a purged archive / 65,536 FIX orders; not attempted |
| YU18 | `FR-ERH-F2` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F3` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F4` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-ERH-F5` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F6` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F7` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-F8` | LIVE |  |  | needs a purged archive / 65,536 FIX orders; not attempted |
| YU18 | `FR-ERH-F9` | LIVE |  |  | deterministic-core edge cases: unit only (SC-ERH-01) |
| YU18 | `FR-ERH-S1` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-ERH-S2` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-ERH-S3` | LIVE | yu18-fix-session |  | no/wrong 554 and an unbound CompID refused; CLIENT1 trades |
| YU18 | `FR-ERH-S4` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-ERH-S5` | LIVE |  |  | refuse-to-start on dev secret: no live arm |
| YU18 | `FR-ERH-S6` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-FFR-01` | LIVE |  | yu18-fix-session yu18-fix-resume | PASS on 071cde6d (two replicas) |
| YU18 | `FR-FFR-02` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-03` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-04` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-05` | LIVE |  | yu18-fix-session yu18-fix-resume | PASS on 071cde6d (two replicas) |
| YU18 | `FR-FFR-06` | LIVE |  |  | race / 65,536 cap: unit-level only |
| YU18 | `FR-FFR-07` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-08` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-09` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d |
| YU18 | `FR-FFR-10` | LIVE |  |  | race / 65,536 cap: unit-level only |
| YU18 | `FR-FFR-11` | LIVE |  | yu18-fix-resume | the persistent-session half (stored, resent after Logon) |
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
| YU18 | `FR-GFS-01` | LIVE |  | yu18-fix-resume yu18-fix-session | PASS on 071cde6d |
| YU18 | `FR-GFS-02` | LIVE |  |  | full store / fresh-epoch purge: not driven |
| YU18 | `FR-GFS-03` | LIVE |  | yu18-fix-resume yu18-fix-session | PASS on 071cde6d |
| YU18 | `FR-GFS-04` | LIVE |  | yu18-fix-store-faults | PASS on 071cde6d: SIGSTOP takeover fenced; NATS outage refused by name, resumed |
| YU18 | `FR-GFS-05` | LIVE |  | yu18-fix-resume yu18-fix-session | PASS on 071cde6d |
| YU18 | `FR-GFS-06` | LIVE |  | yu18-fix-resume yu18-fix-session | PASS on 071cde6d |
| YU18 | `FR-GFS-07` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d (GFS-12 REST arm; FIX arm in yu18-fix-session) |
| YU18 | `FR-GFS-08` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d (GFS-12 REST arm; FIX arm in yu18-fix-session) |
| YU18 | `FR-GFS-09` | LIVE |  | yu18-fix-store-faults | PASS on 071cde6d: SIGSTOP takeover fenced; NATS outage refused by name, resumed |
| YU18 | `FR-GFS-10` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d (GFS-12 REST arm; FIX arm in yu18-fix-session) |
| YU18 | `FR-GFS-11` | LIVE |  | yu18-fix-resume | NEGATIVE=1 PASS (store unset: B restarts at 1) |
| YU18 | `FR-GFS-12` | LIVE |  | yu18-fix-fill-feed | PASS on 071cde6d (GFS-12 REST arm; FIX arm in yu18-fix-session) |
| YU18 | `FR-GFS-13` | LIVE |  |  | full store / fresh-epoch purge: not driven |
| YU18 | `FR-GMR-01` | LIVE | yu18-member-runtime | yu18-member-runtime yu18-member-disk-survives | step 01 rewritten for the retained claim (proof bug fixed); PASS |
| YU18 | `FR-GMR-02` | CI |  |  | render/admission counts: test_prepare |
| YU18 | `FR-GMR-03` | LIVE | yu18-member-runtime |  | PASS on 40d93f6b: exit 143 in 0 s (suspect 1 closed) |
| YU18 | `FR-GMR-04` | LIVE | yu18-member-runtime |  | PASS on 40d93f6b: exit 143 in 0 s (suspect 1 closed) |
| YU18 | `FR-GMR-05` | LIVE | yu18-member-runtime |  | PASS on 40d93f6b: exit 143 in 0 s (suspect 1 closed) |
| YU18 | `FR-GMR-06` | CI |  |  | render/admission counts: test_prepare |
| YU18 | `FR-GMR-07` | LIVE | yu04-live-delta yu04-offline-catchup yu17-book-retick yu15-option-persistence |  | the four control-feed proofs PASS on 40d93f6b |
| YU18 | `FR-GMR-08` | LIVE |  |  | ready gate / negative DNS TTL: no black-box observable driven |
| YU18 | `FR-GMR-09` | LIVE |  |  | ready gate / negative DNS TTL: no black-box observable driven |
| YU18 | `FR-GMR-10` | LIVE |  | yu18-member-disk-survives | PASS MODE=scale and MODE=delete (suspect 15 closed); MODE=reschedule not run (needs a spare engine node) |
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
| YU18 | `FR-MAR01` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-MAR02` | LIVE | yu18-gateway-auth-boundary |  |  |
| YU18 | `FR-MAR03` | LIVE |  |  | readout field set: partially read by the new proof |
| YU18 | `FR-MUI01` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI02` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI03` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI04` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI05` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI06` | LIVE |  | yu18-console-run-scope | DESTRUCTIVE=1 arm PASS: database scaled to 0; hung read = held table lock |
| YU18 | `FR-MUI07` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI08` | LIVE |  | yu18-console-run-scope | PASS on GKE: the spec SC-MUI04/06 browser proof on this tier; MUI07 = 7 path forms 403 signed in + operator token |
| YU18 | `FR-MUI09` | LIVE |  | yu18-console-run-scope | DESTRUCTIVE=1 arm PASS: database scaled to 0; hung read = held table lock |
| YU18 | `FR-MUI10` | LIVE |  |  | Admin trade list: not driven (no admin-page proof yet) |
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
| YU18 | `FR-OT36` | EXT | scripts/ri07/order-types-live.py |  | 31 console-path cases; outside scripts/proofs |
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
