# Acceptance and evidence

A checklist is not a passing test. Implemented starter cases are identified below; all other cases remain required planned work.

| Case | Requirement | Discriminating check | Status |
|---|---|---|---|
| A-01 | FR-03/05 | Known synthetic bill succeeds and retains assumed provenance | Executable starter |
| A-02 | FR-07 | Cache both note and bill, then reuse note submission ID for bill; must conflict | Executable starter |
| A-03 | FR-08 | Hold first pricing call open; overlapping retry must not enter pricing; first call succeeds | Executable starter |
| A-04 | FR-06 | Unsupported EUR request must be rejected rather than silently return USD | Executable starter, proposed USD-only profile |
| A-05 | FR-06 | Unknown calculation must be rejected rather than silently price defaults | Executable starter |
| A-06 | FR-01 | Tampered bytes, forged content identity, duplicate/missing/wrong-epoch joins | Planned |
| A-07 | FR-02/NFR-01 | Trace both entry points to canonical service; compare same economics and prove delegation | Planned after reuse audit |
| A-08 | FR-04/05 | Enumerate every item/calculation, including mixed good/refused/missing-input cases | Planned |
| A-09 | FR-09/10 | New process reads completed and failed attempts; injected publication crashes; recovery of interrupted work | Planned |
| A-10 | FR-11 | Change each result-affecting input/build and prove cache separation; canonical ordering unchanged | Planned |
| A-11 | FR-12 | Terms-v1/v2 and result/capability schema pins; explicit rejection of incompatible changes | Planned |
| A-12 | NFR-02 | Independent bill/note references, long/short signs, accrued reconciliation, bump/sign/units | Existing TraderX reference basis; new interface binding planned |

The starter's e4ca50b binding instruments the actual pricer only to observe execution entry; it does not replace financial results with a stub. The concurrency control includes a bounded observation interval, so record that limit; passing it alone does not prove all possible schedules or cross-process exclusion.

Every result records commit and test command. Missing dependencies/fixtures fail setup. Do not turn failing criteria into skipped tests or relax expected values to match current bugs. Proposed criteria can change through an explicit decision, not silent test edits.

Engine parity and an independent financial reference answer different questions. Both are required before claiming pricing acceptance; a TestClient test does not demonstrate a deployed service or remote bundle delivery.
