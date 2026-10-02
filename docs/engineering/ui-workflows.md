---
title: Demo console and Trader Desk
---

# Demo console and Trader Desk

The Demo console and Trader Desk use the same TraderX services. Available actions depend on the services and permissions configured for the workspace.

## Demo console

The original Angular console is maintained in `web-front-end-console/`. Its router exposes Trading at `/`, plus `/system`, `/eod`, `/risk`, `/replay`, `/sandbox`, `/corpus`, `/admin`, `/accounts`, `/fix`, `/kdb`, `/grafana` and `/legacy`.

| Workflow | What it exposes | What to check |
|---|---|---|
| Trading | Instrument/account ticket, order lifecycle, fills, positions, options and OTC contracts | Gateway capabilities, account admission, order type and time in force; acceptance is not a fill |
| System | Member role, sequence and book comparison, gateway and service status | Compare members at the same sequence; a ready gateway alone is not full-rig readiness |
| EOD | Session close, quality flags, overrides, publish, cut artifacts and overnight jobs | The exact date/version/cut, mark provenance and explicit override approval |
| Risk | Validated calculations, missing/unsupported coverage and stored job results | Result integrity, bundle identity, assumed inputs and `usableForRisk` |
| Accounts and Admin | Account controls, session driver, reconciliation and privileged operations | SQL directory membership, enabled admission and self-match group are distinct |
| FIX | Session/status and ingress tools | Session connectivity and effect-end execution reports |
| kdb, Replay, Sandbox and Corpus | Queries, captured history, historical playback and data browsing | Dataset rights, time window, replay identity and separation from live orders |
| Grafana and Legacy | Observability and the older application | Availability of the configured upstream services |

Console administrative sign-in protects configured control routes. Post-trade JWT authorization belongs to its own service path; it does not make every console or Desk route tenant-isolated.

## Trader Desk

The selected Desk is in `web-front-end-console-combined-prototype/`, reusing Angular components and the console server. The standalone `web-front-end-console-prototype/` and original `web-front-end/` are separate implementations, not the selected Desk entrypoint.

**Overview** summarizes the selected workspace; **Markets** supplies sortable instrument/price views and the inline ticket; **Orders** exposes order states; **Positions** shows holdings; **Risk** embeds existing result panels. Secondary tools and OTC tickets remain accessible through **More → Demo console**. Admin users switch to operational panels explicitly.

### Account and order workflow

1. Enter a local demo username and select an account. Optional administrator access is checked by the server against its configured password hash.
2. Inspect price source and receipt age. **Price at market** copies a fresh mark into the limit field without changing the order type.
3. Submit through the gateway. An accepted response keeps a receipt visible and clears the next ticket. A refusal explains a rejected request; an unknown outcome is not retried automatically.
4. Check order state and fills. A market/IOC order on an empty book can be accepted and then canceled without a fill.
5. Use managed scope for existing-order actions. On the legacy unmanaged profile the Desk disables unsafe mutations. Historical views stay read-only.

Adding an account creates its SQL definition, records local workspace membership, configures its self-match group and requests engine admission. A failed admission can be retried for that account; ambiguous SQL creation is not repeated automatically. Login waits for committed group configuration and does not re-enable an operator-disabled account.

### Identity and availability

Selections and workspace preferences belong to the local user context. Managed HTTP reads and subscriptions carry run/account scope; stale replies and mixed scope data are rejected. A registry 404 selects the explicitly labelled legacy profile, while other errors do not silently fall back.

The local server persists memberships outside the repository and signs sessions out on restart. Public usernames are not verified identity, and original APIs are not production tenant-isolated. Stateless replicas, an identity provider and production authorization remain separate work.

Risk panels retain their own portfolio/cut context. Unavailable selected-account valuation stays unavailable; the UI does not reinterpret an unrelated synthetic result as that account's P&L or portfolio risk.

## Local checks

The [verification guide](testing-strategy.md) lists the two Angular suites and Node server tests. `start-rig.sh` starts UI/proxy processes against an already running local rig; it does not start the trading cluster. Select the intended kubeconfig and inspect its parameters before using it. Ports 4320 (Desk), 4321 (console/API) and 30080 (forward) are launcher defaults, not reserved ports or evidence that a rig is currently running.
