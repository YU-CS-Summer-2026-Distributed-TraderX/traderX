# Account admission specification

Status: implemented and locally tested, 2026-11-08 (RI33). Interface: gateway `POST /risk/control/account`,
`POST /seed`; member egress kind 108. No replicated-state, snapshot or log-format change.

- FR-AAD-01: `POST /risk/control/account` SHALL require a JSON object whose `accountId` is a positive integer that fits
  an int, and whose `enabled`, if present, is a boolean. Anything else is 400 and nothing is sequenced. (A bare JSON
  string such as `"accountId":42422` used to read as account 0 and admit it.)
- FR-AAD-02: Every member SHALL emit, after applying an ACCOUNT_CONTROL, an egress ack of kind 108 with the applied
  sequence at 0, the outcome at 8 (0 = the member's risk table holds the account, 1 = refused: the table is full,
  ENGINE-REVIEW F7) and, at 13, the issuer's request id taken from the ingress inputSeq slot. The apply itself is
  unchanged. The gateway SHALL answer 200 `{"applied":true,"version","sequence"}` only on outcome 0, 422
  `{"applied":false,"reason":"ACCOUNT_TABLE_FULL","sequence"}` on outcome 1, and 504 `{"applied":false,"error"}` when no
  decision for its request id arrives within the ack timeout, including an owner-queue timeout. 422 and 504 are not
  interchangeable: 422 is a committed refusal, and after a 504 the control may still commit.
- FR-AAD-03: `/seed`'s account step SHALL use the FR-AAD-02 path: 504 or 422 before any ticker work, never 200 over an
  admission without a committed hold.
- NFR-AAD-01: FR-PRG-30's `initialize-accounts.mjs` contract is preserved (`applied === true` on success, then a
  re-read of every member). The gateway and the members roll together: a new gateway paired with old members answers
  504 to every admission. That fails closed.

Acceptance: SC-AAD-01. `AccountAdmissionAckTest` runs the real handlers over HTTP against three in-process members.
The RI33 bodies are 400 and admit nobody. A valid admission is 200 once the leader holds the account. A 129th account
is 422, and `/seed` for a 130th is 422. With acks withheld from the gateway, the answer is 504. Against the pre-fix
code, the 400, 422 and 504 cases fail.
