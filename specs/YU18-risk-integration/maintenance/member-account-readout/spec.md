# Member account readout specification

Status: implemented and locally verified; controlled integration and live acceptance pending. Owner: Codex RI22 member-account-readout lane. Dependencies: existing YU18 accountTuples, member HTTP server, YU05 admin JWT policy.

## Requirements

- FR-MAR01: GET /risk/control/accounts on member HTTP requires the existing AUTH_JWT_SECRET-backed JwtAuthenticator admin JWT before state access. Missing, invalid, expired and nonadmin tokens receive 401. Authorized other methods receive 405 with Allow: GET; child paths receive 404. This route works independently of RECON_BLOTTER_CAPACITY. No new authentication scheme or secret.
- FR-MAR02: Available state returns 200 with available:true, count and accounts containing only observed accountId and enabled. Disabled known accounts remain present. Uninitialized engine or absent risk returns 503 with available:false and no accounts/count. Available empty table returns 200 and explicit zero. Failure returns 500 without invented emptiness. Read the current engine instance per request; no directory, offered-control seed, or account cache.
- FR-MAR03: Include memberId, source member-engine-risk-table, sampling sequential-non-atomic, freshness not-established, and existing appliedSeqBefore/appliedSeqAfter observations. Rows and sequences are separate racy reads. Equal sequences certify neither coherent consensus cut nor global quorum, freshness, completed recovery, end-user ownership or a successful future admission. Account enabled state alone does not imply other risk gates pass. No invented epoch, policy version, wall-clock generation or readiness flag.
- NFR-MAR01: Existing accountTuples scans finite occupied backing slots (power-of-two rounded 2×configured maxAccounts); the backing bound is not a separately enforced logical maxAccounts ceiling. No new pagination/retention defaults, mutations, hot-path work, notional or ownership/group disclosures.

## Acceptance

MemberAccountReadoutTest drives the production HTTP handler and full member health factory on owned loopback fixtures. Real synthetic HS256 JWT tests prove authentication-before-read, method/path-before-read, cold versus empty, sequenced known enabled/disabled versus deliberately unsubmitted ID, actual existing snapshot-record restoration, replacement, table bound, unchanged snapshot bytes, differing sequence observations and failure refusal. Same-sequence responses retain explicit racy scope. Source and generated parity required; no retained rig or live cluster acceptance claimed.
