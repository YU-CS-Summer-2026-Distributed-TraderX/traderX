# Order types plan

Status: implemented locally as one delivery, READY_FOR_REVIEW (2026-09-24). Owner: Claude
order-types lane. The whole scope was a single delivery (user direction, 2026-09-23). Not merged,
not deployed.

1. Done: audit and support matrix.
2. Done: expanded spec, revisions 2 and 3; revision 3 cleared (board 20260923T231403Z). OPEN-D5
   approved by the user (20260923T231432Z). The r3 review's implementation requirements are folded
   into the spec.
3. Done: implementation-path claim (20260923T231924Z).
4. Done: implementation in YU18 full-file overrides. Each was copied up unchanged from its highest
   carrying layer in 0ae2c53a, so `git diff 0ae2c53a` is the delta.
   - **Wire:**
     - `OrderInstructionMessage` (SBE template 9) for typed NEW/REPLACE; template 1 is unchanged;
     - `TYPE_BUSINESS_DAY` (SESSION_START / DAY_END) on the existing template.
   - **Engine** (`MatchingEngine`, `LimitBook`, `RestingOrder`, `InputEvent`, `OutputEvent`,
     `OutputPublisher`, `OrderTypes`, `RiskReason`):
     - typed admission and the trade reference;
     - latching, the drain and trailing stops;
     - iceberg transfer-only replenishment;
     - pegs with side-aware `riskPx`;
     - the FOK preflight and key-0 revalidation;
     - the business date;
     - the bounded cascade loop and its exhaustion path.
     `BlpRiskState` is reused unchanged.
   - **Service** (`MatchingEngineClusteredService`):
     - template 9 ingress;
     - the business-date command in every phase, with queued DAY expiry;
     - 17-column queue rows carrying the original submission sequence;
     - snapshot format 11 with consensus-limit checks.
   - **Gateways:**
     - REST: typed orders and replace, legacy refusals, `/session/business-day` and
       `/session/day-end`;
     - FIX: all seven types and the F1 fix.
   - **Read model:** typed columns, open statuses, the DDL and an idempotent 900-migrations block.
   - **Console:** the ticket's type and TIF selectors with shared validation, and the blotter's
     typed view.
   - **Renderer:** overlays every YU18 module directory (a state-level change, proposed on the
     board).
5. Done: implementation-review corrections I1–I4 (board 20260924T010239Z; c41e508b), with
   failing-before evidence and a controlled latency re-run. See the README's Review corrections.
6. Done: verification. See the README's Verification section. It covers:
   - SC-OT01–48 as source tests, run in the working tree and in a freshly generated root;
   - the typed allocation gate;
   - the legacy latency before/after;
   - the migration on mariadb:11.4;
   - the console specs.

Constraints:
- NFR-OT04: this changes the deterministic core, the wire and the snapshot format. It is never
  rolled gradually; any live proof needs separate authorization.
- Ancestor states are unchanged. There is no new state branch. Nothing is pushed.

2026-09-24 Codex takeover: original-decimal REST correction implemented and generated-tested (56 focused tests, five allocation gates). Raw HTTP test failed before and passed after. SC-OT35 remains open; no integration or live proof claimed. See component README and shared ri01-codex-precision-20260924 evidence.


RI-12 implementation: YU18 overrides BlpRiskState to add a preallocated group array indexed by its existing account table. InputEvent type19 carries operator assignments through the existing SBE input envelope. The shared matching predicate and FOK feasibility predicate consult this authoritative state. Snapshot format13 extends account records; old formats load explicit group zero. The console demo persists a workspace group and configures it before admission. The current local rig upgrade must stop all engine members before switching binaries, preserve PVCs, and upgrade gateway/feed/extract consumers together. No GKE rollout is included.

RI-12 local acceptance 2026-09-25: 107 focused generated tests, five allocation gates, seven Node tests and sixteen full-rig readiness checks pass. Live proof verifies committed group setup, different-owner fills, snapshot barrier on all members and leader2 restart/promotion to member1; all three members agree afterward. Existing trade/order rows preserved across upgrade. Full suite initially ran592 tests with a corrected historical-fixture failure and the snapshot50ms timing failure; the latter also fails on unchanged cf08a57d (127.211ms maximum), so no full-suite green or latency claim is made. Local demo ownership is not production identity verification.
