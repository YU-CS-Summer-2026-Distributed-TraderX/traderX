# Engine-review hardening specification

## Requirements: boundary (gateway-only; roll with a gateway restart)

- FR-ERH-S1: The order routes (`/orders`, `/orders/batch`, `/cancel`, `/replace`, `/trades`, `/swaps`,
  `/swaptions`) SHALL require an HS256 bearer JWT and SHALL act only on an account the principal is
  entitled to. Every element of a batch SHALL be checked.
- FR-ERH-S2: `/session`, the business-day roll and `/seed` SHALL require `X-Risk-Control-Token`
  (constant-time compare) with a non-blank `X-Risk-Operator`, or an admin JWT.
- FR-ERH-S3: The FIX acceptor SHALL bind each CompID to a `Password(554)` and an account list (`*` for
  every account) from `FIX_SESSION_AUTH`, and SHALL reject requests outside the binding. `FIX_SSL_KEYSTORE`
  SHALL enable TLS. A gateway with `FIX_ACCEPTOR_PORT` set SHALL refuse to start without `FIX_SESSION_AUTH`
  unless `ALLOW_DEV_SECRETS=true`. GKE SHALL source the password from `auth-secrets/fix-session-password`.
  The console SHALL spend it only for a signed-in operator and SHALL NOT return it to the browser.
  No log line SHALL carry a `Password(554)` or `NewPassword(925)` value, including QuickFIX/J's session
  events and its own SLF4J lines for a logon from an unconfigured CompID; both SHALL mask the value.
- FR-ERH-S4: Order entry, `/trades` and `/resolve` SHALL only look tickers up. `/seed`, the
  reference-data control feed and operator `/risk/control action=security` SHALL register. The member
  SHALL serve its registered table read-only on `/symbols`.
- FR-ERH-S5: Gateway and member SHALL refuse to start on an unset or known development secret unless
  `ALLOW_DEV_SECRETS=true`. GKE secrets SHALL be generated from git-ignored env files, never committed.
- FR-ERH-S6: Request bodies SHALL be capped at `GATEWAY_MAX_BODY_BYTES` (8 MiB) and batches at
  `GATEWAY_MAX_BATCH_ORDERS` (100,000); either limit SHALL answer 413.
- FR-ERH-C1: The console SHALL attach its JWT to the S1 routes only for a signed-in operator, and SHALL
  answer `401 admin_auth_required` otherwise.
- FR-ERH-C2: trade-service SHALL forward legacy `/trades` with a 60 s HS256 bearer (`sub=trade-service`,
  one account, `admin=false`) when `AUTH_JWT_SECRET` is set, and SHALL return a gateway 4xx with its
  status and reason instead of a 500.

## Requirements: deterministic core (fresh epoch, same image on all three members)

- FR-ERH-F1: An evicted idempotent retry SHALL be answered DUPLICATE, never admitted again.
- FR-ERH-F2: The exposure table SHALL be sized so that it cannot fill on the cluster tier.
- FR-ERH-F3: A book SHALL keep its grid while stops pend; a STOP_LIMIT limit SHALL never be floored at its trigger.
- FR-ERH-F4: An unknown security SHALL be refused before a book is allocated for it.
- FR-ERH-F5: The price band SHALL be re-anchored only for an order the risk gate accepts.
- FR-ERH-F6: An untyped replace of a stop, iceberg, peg or trailing order SHALL be refused.
- FR-ERH-F7: Account-table overflow SHALL be refused deterministically, never thrown inside apply.
- FR-ERH-F9: A PRE_OPEN retry of a key the engine already decided SHALL NOT be queued.
- FR-ERH-F10: An out-of-range `securityId` on a price tick SHALL be ignored, and on a market trade SHALL be
  answered UNKNOWN_SECURITY.

## Requirements: recovery and FIX state

- FR-ERH-F8: Full-history replay SHALL be strict. A purged archive SHALL refuse with
  `RECOVERY_ARCHIVE_GENESIS_MISSING` / `RECOVERY_ARCHIVE_DISCONTINUITY` instead of replaying from mid-log.
- FR-ERH-F11: The FIX ClOrdID ledger SHALL hold at most 65,536 entries, evicting the eldest, and SHALL
  rehydrate only the file's last 65,536 records. An ambiguous order SHALL be recorded as
  `ORDER_REF_UNKNOWN` and resolved by its retry.

## Success Criteria

- SC-ERH-01: `GatewayAccessTest`, `BoundedBodyTest`, `ClusterIngressAuthTest`, `AccountTableOverflowTest`,
  `OutOfRangeSecurityEventsTest`, `BookCapacityTest` and `ClOrdIdLedgerTest` pass in an empty-root render,
  inside the full order-matcher suite.
- SC-ERH-02: The trade-service suite, including the four `GatewayBearer` and propagation tests, and the
  console's `risk-control-proxy.test.mjs`, pass.
