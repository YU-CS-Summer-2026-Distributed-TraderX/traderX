# RI33 — An account admission answered 200 and reached no member

Opened: 2026-11-07 (trade-history-tiers GKE proof, namespace `pa-proof-tht` on traderx-demo, torn down). Status: open,
not diagnosed. Severity: an operator who trusts the 200 believes an account can trade when the engine refuses it.

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
