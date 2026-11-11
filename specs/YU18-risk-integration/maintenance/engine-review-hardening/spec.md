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
- FR-ERH-F13 (ADR-100): The order-reference generator SHALL issue references in `[1, 2^31-1]` only. An ORDER_NEW
  arriving once the epoch has issued 2^31-1 SHALL be refused with `ORDER_REF_EXHAUSTED` before it consumes a
  reference or an applied sequence, unless its key is already committed (decided in the idempotency table,
  waiting in the PRE_OPEN queue, or a sequenced resend the ring answers): a committed request SHALL recover
  its original answer with no reference consumed, and a refused sequenced request SHALL recover the same
  refusal on resend; cancels and replaces SHALL still apply. A snapshot header whose `nextOrderRef`
  is outside `[1, 2^31]` or whose `highestIssuedRef` exceeds 2^31-1 SHALL be refused at load. The member SHALL
  expose `traderx_cluster_order_ref_headroom`. A `/trades` ticket booking SHALL publish `bookingSource: TICKET`
  and no `sourceOrderId`; a matched fill SHALL publish `bookingSource: MATCH` and `<epoch>-<ref>`.

## Requirements: recovery and FIX state

- FR-ERH-F8: Full-history replay SHALL be strict. A purged archive SHALL refuse with
  `RECOVERY_ARCHIVE_GENESIS_MISSING` / `RECOVERY_ARCHIVE_DISCONTINUITY` instead of replaying from mid-log.
- FR-ERH-F11: The FIX ClOrdID ledger SHALL hold at most 65,536 entries, evicting the eldest, and SHALL
  rehydrate only the file's last 65,536 records. An ambiguous order SHALL be recorded as
  `ORDER_REF_UNKNOWN` and resolved by its retry.
- FR-ERH-F12: Restore and replay SHALL fail closed on the real Aeron path, not only in the codec.
  - `Image.poll` catches a fragment handler's exception, records it in the client's error log and keeps polling.
  - `loadSnapshot` SHALL keep the first refused record and throw it, so a member never starts on a partial
    restore. (Before this, a refused record was skipped and the load reached END without it; a refused header
    left the load waiting forever.) A record larger than one snapshot message SHALL be reassembled before it is read.
  - `ClusterRecon`'s archive replay SHALL do the same, so an incomplete replay never becomes a witness. This has
    held since 3f8db417.
- FR-ERH-F16: A restored member's state SHALL be comparable with a full-log replay, in the same terms on both sides.
  - `GET /health?digest=1` SHALL add the member's `bookHash` and `positionHash` (`MatchingEngine.recoveryDigest`:
    open orders and net positions), plus `openOrders`, `positions` and `digestTrades`. Plain `/health` is
    unchanged, because the readiness sampler polls it every 250 ms.
  - `GET /recon/shadow-state` SHALL replay the whole log strictly from position 0 into a fresh shadow (refusing a
    purged or discontinuous archive, FR-ERH-F8) and return the same fields, plus `applied`, `replayedMessages`,
    `memberAppliedBefore`/`memberAppliedAfter` and `complete`. `complete` means the member applied nothing during
    the replay, and the replay reached the member's applied sequence.
  - Hashes SHALL be decimal strings: a 64-bit value read as a JSON number loses precision in jq and JavaScript.

## Success Criteria

- FR-ERH-F14: The gateway's catalog feed (reference-data's control outbox, `applyControlDelta`) SHALL register a
  security and enable it only when that registration created it; a security the engine already knows keeps its
  control. Every gateway replays the whole feed at start and the engine applies controls last-write-wins, so
  enabling a known security re-enabled whatever an operator had disabled on every gateway restart (production epoch
  e1101, 2026-10-31: the matured `UST-BILL-20260910` read enabled after the initializer disabled it). The engine's
  symbol-register ack carries the fact in byte 21 (1 = created), an ack-only value identical on every member; the
  gateway and the members ship as one image. `GatewayCatalogFeedTest` (three members: register, operator disable,
  replay by the same and by a restarted gateway, still disabled everywhere) fails against the previous gateway.
  The test reads the registration's id only once every member has applied it (2026-11-10): the gateway's ack comes
  from the leader, and reading member 0 at once gave -1 whenever it was a follower that had not yet applied it, after
  which nothing could ever be enabled at that id (the 60 s timeout, 1 run in 10 locally and 2 in 3 on a loaded box).
  A race in the test, not the product.
- FR-ERH-F15: Each member SHALL serve `GET /risk/control/securities` (admin JWT; 401/404/405/503 as
  `/risk/control/accounts`): every registered ticker with its security control as applied. The reference-data
  initializer SHALL verify the catalog's enabled states there on all three members, not on a gateway's control
  replica, which holds only what passed through that gateway. `MemberSecurityReadoutTest`.
- SC-ERH-01: `GatewayAccessTest`, `BoundedBodyTest`, `ClusterIngressAuthTest`, `AccountTableOverflowTest`,
  `OutOfRangeSecurityEventsTest`, `BookCapacityTest`, `OrderRefExhaustionTest`, `ClOrdIdLedgerTest`, `GatewayCatalogFeedTest` and
  `MemberSecurityReadoutTest` pass in an empty-root render,
  inside the full order-matcher suite.
- SC-ERH-02: The trade-service suite, including the four `GatewayBearer` and propagation tests, and the
  console's `risk-control-proxy.test.mjs`, pass.
