# RI33 — An account admission answered 200 and reached no member

Opened: 2026-11-07 (trade-history-tiers GKE proof, namespace `pa-proof-tht` on traderx-demo, torn down). Status:
diagnosed and fixed on branch `admission-200` (2026-11-08), not merged, not deployed. Severity: an operator who trusts
the 200 believes an account can trade when the engine refuses it. Spec: `specs/YU18-risk-integration/maintenance/account-admission/`
(FR-AAD-01..03).

## Root cause (2026-11-08)

The 200 was real, and it was the answer to a different request. Two faults combined.

1. **The command split the body.** Under macOS `/bin/bash` 3.2, the form
   `echo "acct $a $(code ... -d "{\"accountId\":${a},\"enabled\":true}")"` brace-expands the JSON: curl sent two
   POSTs, with bodies `"accountId":42422` and `"enabled":true`. An assignment (`c="$(code ...)"`, which every committed
   proof uses) does not expand. Reproduced against a stub server: `acct 22214 200 acct 22214 200`, the same doubled line
   as the evening's output, plus two POSTs per account. A body with three fields printed three times, which explains the
   repeated lines. A body with no comma (the business day) went through intact.
2. **The gateway admitted account 0 and answered `{"applied":true}`.** Jackson reads `"accountId":42422` as the bare
   string `"accountId"`, and `path("accountId").asInt()` on it is 0. The handler sequenced an ACCOUNT_CONTROL for account
   0, enabled. That is the `0` in every member's `[0, 22214]`; nothing else admits account 0. Independently, the handler
   answered 200 once the ingress offer was accepted, without waiting for a committed decision. A message lost after the
   offer, or one the member refused (F7: account table full, silently ignored), also answered 200.

On GKE, 22214 was present because `yu18-projection-effect-end.sh` `/seed`s ACCT_A itself, from a script file. The
resend that worked was a single `code` call with no `echo "...$(...)"` around it.

The kind symptom has the same cause. The trade-history-tiers transcript for 7:19–7:21 pm ET shows the bad form at
7:19 pm. A one-off `/seed` also failed, at 7:20 pm. The first well-formed admission was a bare `code` call at
7:20:42 pm; all three members held 42422 at `appliedSeq` 75 four seconds later. The gateway restart came after that
read, with no cross in between. So "refused until the gateway restarted" was never observed: the restart was
incidental. The gateway keeps no account state. `UNKNOWN_ACCOUNT` is the member's verdict, and the orders were sequenced
(orderRefs 1..10).

Paths that answered 200 before the fix (`handleRiskControl`, action `account`): only "ingress offer accepted". A
timeout raised out of the handler as a 500; there was no `applied:false` body; 422 was only for an unknown instrument.
`/seed` had the same offer-only account step.

## Fix (branch admission-200)

- FR-AAD-01: the route requires a JSON object with a positive integer `accountId` and an optional boolean
  `enabled`, otherwise 400, and nothing is sequenced.
- FR-AAD-02: each member acks every ACCOUNT_CONTROL it applies (egress kind 108, correlated by the issuer's request
  id in the ingress inputSeq slot, outcome 0 = held, 1 = refused). The gateway answers 200 `{"applied":true,"version",
  "sequence"}` only on outcome 0; outcome 1 is 422 `{"applied":false,"reason":"ACCOUNT_TABLE_FULL"}`; no decision
  within the ack timeout is 504 `{"applied":false,...}`. The apply itself is unchanged, so replay and snapshots are not
  affected.
- FR-AAD-03: `/seed`'s account step uses the same path: 504 or 422, never a 200 over an unapplied admission.
- `initialize-accounts.mjs` (FR-PRG-30) still reads `applied === true` and re-reads every member. A 422 or 504 now
  fails its assert instead of leaving the member check as the only defence.
- Test: `AccountAdmissionAckTest` (real handlers over HTTP, three in-process members). Against the pre-fix code, the
  malformed-body, refusal and no-decision cases fail.
- Roll constraint: a new gateway paired with old members never receives kind 108, so every admission answers 504. That
  fails closed. The gateway and the members share the `cluster-node` image and roll together.

## What was sent

Through `kubectl port-forward svc/order-matcher 18110` to the namespace's one gateway, about 8:37 pm ET, within one
`bash -c` using `scripts/proofs/lib-yu18.sh` (`code` writes the body to `BODY_FILE` and prints the HTTP status), with
headers `X-Risk-Control-Token: <operator_token>` and `X-Risk-Operator: tht-proof`:

1. `POST /session/business-day {"businessDate":"2026-11-07"}` → 200
2. `POST /risk/control/account {"accountId":22214,"enabled":true}` → 200, then `POST /seed` for 22214 → 200
3. `POST /risk/control/account {"accountId":42422,"enabled":true}` → 200, then `POST /seed` for 42422 → 200

The bodies of the first run were not printed. The output repeated each line six times. The harness is part of what
must be ruled out, because a 200 printed here may not be the status of that request.

## What the members showed

The gateway was restarted afterwards (a kind run the same evening needed that before new accounts traded). Two crosses
by `yu18-projection-effect-end.sh` then failed with `422 {"kind":2,"reason":"UNKNOWN_ACCOUNT"}` for 42422 (orderRefs 1
and 2), and the gateway logged `ORDER-REJECT ... account=42422 ... reason=UNKNOWN_ACCOUNT`. Every member's
`GET /risk/control/accounts` (admin bearer) listed `[0, 22214]` at `appliedSeqAfter` 60: 22214 applied, 42422 not.
The gateway also logged `CONTROL-FEED connect/subscribe failed, retrying every 2s: ... [SUB-90007] No matching
streams for subject`: the disposable rig has no reference-data control stream. That is probably unrelated, because
admission is sequenced through the cluster, not the control feed.

## The resend

The same `POST /risk/control/account {"accountId":42422,"enabled":true}`, a minute later, answered
`200 {"applied":true,"version":1794101899729}`. All three members then listed `[0, 22214, 42422]`, and both crosses
passed (`YU18-PROJECTION-EFFECT-END PASS`).

## To find out

- Is the 200 real? Replay the first sequence with each body printed, or read the gateway log for an
  `applied:false` or timeout path that still answers 200.
- If it is real: what does the gateway answer when the sequenced control message is lost or rejected? FR-PRG-30's
  `initialize-accounts.mjs` asserts `applied === true` and re-reads every member, so the production bring-up would
  refuse rather than pass. Ad-hoc admission (proofs, operators) has no such check.
- Related, kind the same evening: the members held 42422, but the gateway refused it until the gateway restarted.
