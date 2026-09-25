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

## Current rig limitations

The retained local rig tested on 2026-09-25 predates the run registry. Typed-order capability is not verified: the new ticket enables only Limit/GTC using the legacy wire contract. Existing-order changes are disabled without a confirmed run identity, to avoid acting on retained rows from another run. Cancelled fill quantity is shown as unknown when the legacy projection cannot establish it.

A one-share IBM test on account 22214 returned UNKNOWN_ACCOUNT and persisted as REJECTED; it was not a fill. The account directory remains present while engine admission must be initialized separately. The isolated startup proof succeeded with four probe trade legs, flat probe accounts and unchanged non-probe rows. No account control or existing book was reset here.

The profile picker is a local workspace selector, not authentication or account authorization. Existing operator sign-in and server restrictions remain. Production trader authentication, full feature migration, current-engine deployment and cloud rollout remain separate work.

## Verification

- Angular build passes (initial bundle exceeds its existing 500 kB warning budget; no threshold changed).
- 29 Angular tests cover existing workspace behavior and connected run/account boundaries.
- `node --test test-server.mjs` passes the real WebSocket-reset survival and local cloud-read refusal check.
- Existing isolated startup probe: PASS, four persisted legs, no foreign legs, flat, non-probe rows unchanged.
- Browser: streamed prices, retained holdings, account selector, gateway refusal/order history, Desk lockup and last More item inspected; original Demo console loaded.

The 20-test fixture-only baseline and screenshots in evidence/ describe the earlier design candidate. Current runtime evidence is in `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/combined-desk-rig/` and remains outside tracked UI sources.
