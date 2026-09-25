# TraderX desk — local rig connection

The selected combined UI uses the Codex login/layout and Claude TraderX Desk branding. The Desk suffix matches the light text in the user-selected lockup. The original UI is available as **More → Demo console**, last in the menu, at port 4321.

## Start locally

Use an already running local kind rig. This launcher does not deploy, reset or start cluster nodes.

```sh
cd /Users/yaakov/dev/lmax/traderX-ui-combined
npm ci --prefix web-front-end-console-combined-prototype
npm ci --prefix web-front-end-console
npm run build --prefix web-front-end-console
export KUBECONFIG="$(mktemp -t traderx-desk)"
kind export kubeconfig --name traderx-yu12-cluster
bash web-front-end-console-combined-prototype/start-rig.sh
```

In another terminal:

```sh
cd /Users/yaakov/dev/lmax/traderX-ui-combined/web-front-end-console-combined-prototype
npm start
```

Open http://127.0.0.1:4320. The API server and original Demo console listen on loopback port 4321. The launcher maintains an owned edge port-forward on 30080. Stop those two terminal commands to stop the UI processes; the rig stays running. Cloud archive reads are disabled in this launcher.

## Connected behavior

Accounts and instruments come from their services, prices from NATS through the console WebSocket bridge, and holdings/executions/order states from the SQL projections. The desk never substitutes fixture data after a failed read. Managed reads use explicit scope URLs and recheck the active pointer; stale-context replies and mixed scopes/accounts are refused. A registry 404 selects an explicitly labelled legacy run. Other errors do not.

The inline ticket submits to the existing gateway and algo endpoints. Responses distinguish accepted, refused and unknown outcomes. No failed or ambiguous write is automatically retried. Price source and receipt age are shown independently. Explicit limit orders may be sent with a stale displayed price; the UI warns and the venue validates the limit.

Risk uses the existing integration/job panels under their own portfolio/cut identity, separate from the selected account's unavailable portfolio valuation. Admin pages reuse existing live console panels. Secondary tools and swap/swaption tickets link to their existing Demo console flows while their dedicated migration remains open.

## Current local rig

On 2026-09-25 the user authorized replacing the old YU17 demo state and enabling eight existing demo accounts. The local namespace and persistence were recreated, saved account definitions restored, and current generated YU18 services built with image tag `desk-yu18-20260925`. All three cluster members use the same build. The inherited startup scripts were run with explicit current-image overrides; do not reapply unmodified historical YU17 manifests over this rig.

The gateway advertises all seven order types through `/capabilities`. The Desk enables typed submissions only after validating that response. All 31 live order-type cases passed, including SQL effects and book observations. The eight approved accounts accepted Market/IOC checks with no fills; synthetic test trades remain on dedicated verification accounts. Business date 2026-09-25 is open. Prices run in offline synthetic mode.

Run identity remains legacy/unmanaged. Existing-order mutations in the Desk remain disabled without confirmed managed scope. Production authentication, tenant isolation, complete feature migration and cloud rollout remain separate work.

## Verification

- 32 Angular tests and 6 Node session/server tests pass.
- Angular production build passes with the existing 500 kB bundle-budget warning.
- Full local rig readiness: 16 checks pass, including persisted startup probe and tracing.
- Seven order types: 31 live cases pass on isolated verification accounts.
- Browser: Market/IOC accepted as reference 36; SQL records it as CANCELED without a fill, as expected for an empty IBM book.

Current runtime evidence is in `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/desk-yu18-rig/`, outside tracked UI sources. The fixture-only screenshots in evidence/ describe the earlier design candidate.

## Username workspaces and accounts

Enter a username on the login page to open/create a local demo workspace. Supply the optional admin password for administrator features in this session. Password verification uses the existing server scrypt hash from `~/.config/traderx/desk-local.env` (`ADMIN_PASSWORD_HASH`), never client code. This machine's configured password was supplied by the user; it is not tracked. The launcher reads that config and stores workspace membership in `~/.local/share/traderx/desk-users.json`, outside Git. Server restart signs sessions out but preserves memberships. This local single-process profile must not be used as production authentication or replicated account storage.

Use **+ Add account** beside the account selector. The server creates a SQL account, records ownership, then submits engine admission. A failed admission can be retried for that account; an ambiguous SQL creation is not repeated automatically. Existing accounts are not silently enabled by the launcher. Username-only selection is public within this local demo; original APIs are not tenant-isolated.

**Price at market** fills the limit field from a fresh mark. It keeps the chosen order type; it does not guarantee execution at that price. Click Markets column headings to sort; click again to reverse.

Run `node --test test-desk-session.mjs test-server.mjs` for server/profile checks. The current local YU18 rig advertises typed orders and has the approved existing accounts enabled. Account definitions and engine admission remain separate; a future reset must restore both.
