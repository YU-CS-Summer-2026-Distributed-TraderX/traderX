# Combined desk requirements

- FR-CUI-01: Use Codex-style login and Claude’s TraderX Desk brand. Fixture identity selection must not claim authentication.
- FR-CUI-02: Keep five primary trader tabs and secondary tools in More, with a separate admin workspace.
- FR-CUI-03: Bind desk views to the selected authorized fixture account and run. Historical views must not submit, replace or cancel orders.
- FR-CUI-04: Clear ticket values on account, product or instrument changes. Prices must not cross unit boundaries.
- FR-CUI-05: Show unavailable portfolio risk when no matching account/run result exists. Keep reference examples separate and synthetic.
- FR-CUI-06: Preserve the existing console and both independent candidates.

Acceptance: build and 20 Angular tests; browser inspection of login, overview, orders, Risk and admin; 390px order-page inspection with no horizontal page overflow. Tests in workspace.spec.ts cover FR-CUI-03/04 plus inherited session/account behavior. Screenshots and verification.md provide UI evidence for FR-CUI-01/02/05. Git changed-path review covers FR-CUI-06.

Future production requirements: server authentication and authorization, two-user isolation against real APIs, freshness/reconnect handling, feature parity and migration acceptance. Current fixture guards do not satisfy those requirements.

## Connected acceptance — 2026-09-25

- FR-CUI-07: Runtime uses actual local service reads with no fixture fallback; scope/account validation, finite read deadlines and context fencing protect the selected view.
- FR-CUI-08: Submit through existing gateway contracts; refuse unverified typed-order semantics, fence mutations against context changes and do not retry unknown outcomes.
- FR-CUI-09: Place Demo console last in More and preserve its existing entrypoint. Use matching light Desk typography.
- FR-CUI-10: Serve local processes on loopback, maintain the edge forward, preserve operator authorization and block cloud archive reads in the local profile. A WebSocket reset must not terminate the shared server.

Evidence: eight additional run-boundary tests in rig.spec.ts (29 Angular tests total), test-server.mjs real socket regression, local startup probe and browser observations. Run-registry 404 is the only legacy fallback. The current older rig lacks confirmed run identity; changes to existing orders and unverified advanced order types remain disabled. Local profile roles are presentation controls, not authentication.


## Local workspace accounts — 2026-09-25

- FR-CUI-11: Username entry opens or creates a persistent local workspace. A new workspace starts with no trading accounts. Admin password is optional, verified on the server, and grants only the current session's admin role. Never bundle the password or its hash in client code. Existing operator mutation guards remain enforced.
- FR-CUI-12: Add account creates a SQL account using its sequence allocator, then admits that account through the gateway account-control API. Persist membership outside the checkout. Report partial admission and offer a retry for that owned account. Do not automatically repeat an ambiguous SQL create.
- FR-CUI-13: Price at market copies the current fresh mark into the limit-price field without changing order type. Stale/missing prices cannot autofill. This button is not a market order or a fill guarantee.
- FR-CUI-14: Every Markets table column sorts on click and reverses on a second click. Numeric values sort numerically, missing values remain last, and aria-sort reports direction.

Username-only selection remains a local demo, not production trader authentication. Membership controls new account creation and admission; the original demo APIs are not tenant-isolated. Non-limit order acceptance requires upgrading the currently running YU17 engine. Existing-account admission repair and any destructive local reset await explicit approval.

- FR-CUI-15: The desk reads gateway `GET /capabilities` schema1 and enables typed orders only when the gateway advertises all seven required types. An unavailable, malformed or older capability response leaves typed orders disabled. This metadata describes the current gateway binary; deployment verification must separately prove all members use the same compatible build.

FR-CUI-16: After confirmed order or algo acceptance, clear quantity and price/trigger/display/trailing inputs while retaining the acceptance receipt and instrument context. Block repeat submission while pending and after clearing. Refused or uncertain outcomes retain the draft and are never automatically retried.

## Managed order management — 2026-09-30

This section supersedes the earlier connected-delivery limitation for **managed** local rigs.
Unmanaged retained rigs remain read-only for order mutations through the Desk. Username selection
is still a demo identity and does not establish production authentication or beneficial ownership.

- FR-CUI-17: Route direct Desk submit/cancel/replace through the server workspace session. Require
  owned account, explicit selected projection scope, registered ACTIVE descriptor, and matching
  active gateway descriptor/scope/phase. Before existing-order mutation, read the selected account's
  scoped order and match its full epoch-qualified ID, scope and live status. Never authorize by the
  numeric order reference alone. Repeat the selected-pointer read before dispatch.
- FR-CUI-18: Carry expected descriptor hash and scope on gateway order commands. The receiving
  gateway checks both against its immutable configured descriptor before dispatch. A mismatched
  route or newly selected run cannot reuse an old reference. Existing consensus admission remains
  authoritative: a frozen run cannot accept a command after transition freeze. No new core wire or
  snapshot format is introduced.
- FR-CUI-19: Preserve all seven typed submission payloads and client idempotency keys. The inline
  replacement editor changes LIMIT total quantity and limit price only; total quantity must exceed
  filled quantity. Other live types retain cancellation. No automatic retry follows an uncertain
  mutation result. Server reads can refuse safely before sending; transport failure after sending
  remains an unknown outcome.
- FR-CUI-20: Clear account rows and disable actions when a finite read deadline expires, including
  transports that ignore cancellation. Account/run changes fence late reads and preflight replies;
  a newly observed run clears the ticket/editor. Historical views are read-only even if their scope
  happens to equal the current pointer. Polling reconciles orders, trades and positions after restart
  and accepted archive catch-up, without implementing another recovery algorithm.
- FR-CUI-21: In local mode, non-admin raw proxy order mutations (including legacy and per-gateway
  aliases) cannot bypass the workspace route. Account projection reads require owned membership.
  Existing password-checked Demo console operator access is retained. Direct backend/internal Aeron
  access, production authentication, secondary tools and arbitrary NATS subscription authorization
  remain outside this local Desk order-management contract.

Managed configuration uses the existing RI-06 offline provisioning, durable transition and recovery
contracts. Merely attaching a descriptor to a retained unmanaged rig is not supported. No retained
rig activation or data migration is part of this implementation.
