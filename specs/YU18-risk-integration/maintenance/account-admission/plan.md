# Account admission plan

1. Reproduce the evening's command against a stub server, to confirm the body split and the account-0 admission.
2. Gateway: validate the body (400), route egress kind 108, and add `admitAccount` (offer, then await the member
   decision by request id), shared by the route and `/seed`.
3. Member: after the unchanged apply, ack every ACCOUNT_CONTROL with whether the risk table holds the account.
4. `AccountAdmissionAckTest`, run first on the pre-fix code (it must fail) and then with the fix; full order-matcher
   suite; render coverage.
