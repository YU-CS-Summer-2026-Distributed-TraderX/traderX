# OTC refusal audit specification

Status: implemented and verified locally; coordinator review/integration pending.
Owner: Codex RI22 OTC chat `01a11835-d552-73d1-a720-e7a15fa66cb3`.

## Requirements

- **FR-ORA01:** Replay the actual `onSwapBook` refusal, including `UNKNOWN_ACCOUNT`,
  `ACCOUNT_DISABLED`, `PRICE_MISSING`, `ORDER_NOTIONAL` and `CAPACITY`, without inferring refusal
  from absent contract growth or independently recomputing policy.
- **FR-ORA02:** Each row carries product in its kind, the service's applied input sequence, decoded
  account/convention/direction, cluster message time, and actual risk reason. Nonzero decoded
  `clientOrderKey` and original ingress `requestId` are optional correlation fields. Zero denotes
  unavailable correlation and is omitted. Refusals have null orderId, tradeId, quantity and price.
- **FR-ORA03:** Preserve accepted contract IDs/terms, legacy accepted/order JSON keys and values,
  inclusive sequence bounds, committed-input order, and deterministic closed-log reports. Refused
  retries follow actual repeated decisions; accepted retries continue producing one contract row.
- **FR-ORA04:** Accepted and refused rows share `REGULATORY_MAX_RECORDS`. Filter before capacity;
  allow exact capacity and refuse past it. Require existing strict genesis/continuity checks for
  regulatory replay; missing archive history cannot certify an empty complete report.
- **NFR-ORA01:** Only regulatory shadow replay installs the refusal observer. With no observer,
  the apply path allocates no observation object and preserves snapshots, contracts, risk state,
  existing output ring and direct egress bytes/counts. No replicated state, output event, SBE/wire,
  snapshot schema, risk/venue convention or financial policy changes.

## Interfaces and coverage

The HTTP route and authorization remain the existing member `GET /regulatory/report`; NodeMain is
unchanged. `AuditRow` adds nullable, JSON-omitted correlation columns and nullable quantity solely to
represent refusal without fabricated economics. Existing accepted/order constructors retain their
11-column JSON. Console order-price consumers filter `ORDER_ACCEPTED`/`ORDER_REJECTED` and ignore
OTC kinds. No UI, Gateway, projection or recovery-envelope change.

This covers only decoded booking inputs that enter `onSwapBook` and advance `appliedSeq`. Parser or
transport refusal and the managed admission barrier (which advances no service sequence) are not
reported as sequenced OTC decisions. The report depends on retained log genesis, the historical
reference/policy in that log, and the existing replay framing/retention implementation. It does not
recover refusal history from a snapshot alone or reconstruct purged history. A local synthetic
archive fixture proves the tested closed prefix; it is not a claim about deployed archives.

## Executable acceptance

`ClusterReconOtcRefusalTest` exercises actual report archive replay, reasons/identity/correlation,
capacity, inclusive ranges, stable JSON, retries, missing-genesis refusal and parser/admission
exclusions. It compares installed/unset tap snapshots/risk/outputs and actual egress offers.
`ClusterReconOtcTest` preserves all inherited booked-contract assertions and replaces the explicitly
pinned missing-row test with a refusal attribution assertion. Existing risk/snapshot/replay and
allocation gates remain required within their documented profiles.
