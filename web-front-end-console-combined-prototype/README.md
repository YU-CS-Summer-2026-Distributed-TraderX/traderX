# TraderX combined desk candidate

Local Angular design candidate combining the Codex login, hierarchy and account/run context with Claude’s TraderX Desk branding and inline order ticket. Original console and both original candidates remain unchanged.

## Run

```sh
cd /Users/yaakov/dev/lmax/traderX-ui-combined/web-front-end-console-combined-prototype
npm ci
npm start -- --host 127.0.0.1
```

Open http://127.0.0.1:4320. Choose a fixture profile; Morgan Lee can switch to Admin from the user menu.

## Included

Five primary trader pages: Overview, Markets, Orders, Positions and Risk. Secondary tools live in More. The account and run selection applies across the desk. Historical context disables order mutations. Changing account, product or instrument clears the order draft so a price cannot cross instruments with different units. The separate admin workspace groups Operations, Accounts, End of day, Observability and Controls.

Risk shows selected-account holdings and unavailable results honestly. Synthetic bill/note calculations are separate collapsed reference examples, not portfolio valuations.

## Scope

This is a fixture prototype, not authentication or a connected trading system. Profile selection and private browser preferences do not enforce server authorization. Order actions change only in-memory fixtures. Some secondary features are placement descriptions rather than rebuilt functionality. Production authentication, API integration, full feature parity and rollout remain open. No cloud services are used.

Based on Claude candidate b82a6322 and Codex design b91761ef; isolated integration base 785823af. Original console components/styles are imported read-only.

## Verification

`npm run build` passed. `npm test -- --watch=false --browsers=ChromeHeadless` passed 20 tests, including draft reset and historical mutation guards. See evidence/verification.md and screenshots. These are local UI checks, not live trading or financial validation.
