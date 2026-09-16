# Risk tab: Treasury demo run (UI)

The Risk page opens with **Run a Treasury demo**. One explicit click by a signed-in operator starts
the server-defined scenario: a small demo Treasury bill trade on the demo venue. The resulting
position is priced with the assumed flat 3% curve and checked independently. The browser never
chooses the instrument, size or price. The existing fixed 2025-06-02 bill/note sections stay below,
under **Reference examples**. They are labelled as not being the trade from the run.

## Contract consumed

`traderx.treasury-trade-demo.v1`, owned by the console server (backend lane):

- `GET /risk/treasury-demo` returns `{schema, workerAvailable, scenario|null, run|null}`. It is read-only
  and never starts a run. An unconfigured deployment returns `scenario: null`.
- `POST /risk/treasury-demo/start` with body `{idempotencyKey}` requires the operator session and a same-origin request.
  The response is the same view: 202 while active, 200 when terminal. Errors return `{code}`: `admin_auth_required` (401),
  `ORIGIN_REJECTED` (403), `WORKER_UNAVAILABLE` or `NOT_CONFIGURED` (503).
- `run` = `{id, status: QUEUED|RUNNING|SUCCEEDED|FAILED|NEEDS_REVIEW, stage, message, result|null}`.
  `stage` is the last **confirmed** event: `TRADE_SUBMITTED`, `TRADE_BOOKED`, `POSITION_EXPORTED`, `PRICING`
  (started) or `INDEPENDENTLY_CHECKED`. A result exists only for `SUCCEEDED` and carries `usableForRisk: false`.

The UI rejects any body that does not match this shape (including a result without `usableForRisk: false`,
a non-passing check, or non-decimal amounts). It shows such a body as unavailable and clears whatever was shown before.

## Behaviour

- **Starting.** The Run button appears only when signed in, a scenario exists, the worker is available and no run
  exists. The server keeps a single run for the demo, so the page says it runs once. Clicks are single-flight.
  The idempotency key is written to `localStorage` before the POST and reused on retry. It is cleared when the run
  is terminal or the start was definitively refused (401/403/503). A lost reply keeps the key and offers
  "Try again", which resumes the same run. Page load and reload only GET.
- **Progress.** There are five steps: Trade submitted → Trade booked → Position exported → Pricing →
  Independently checked. Only confirmed steps show done. Pricing shows in progress until the check completes.
  Engine acceptance ("submitted") is never shown as booked. A failed or needs-review run marks the next step
  failed or needs review, shows the server's plain message and shows no result. If the worker becomes
  unavailable mid-run, the page says so and does not claim completion.
- **Polling.** An active run is polled every 1 s, backing off ×1.5 to 5 s. Polling stops at a terminal
  state or when the page is left. After 10 minutes it stops and asks the operator to use Check status.
- **Result (this run only).** The booked trade (instrument, quantity, signed face, booked price as % of par)
  is shown separately from the valuation of the resulting position: valuation date, pricing result vs independent check,
  difference, tolerance and pass. Rate sensitivity is "not calculated for a bill (unsupported)", not zero.
  Labels: demo only, assumed curve, calculated locally, not production risk.
- **Privacy.** No run ids, accounts, hashes, commits, personal names or operational timestamps are rendered.

## Verification

```sh
cd web-front-end-console
CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" npx ng test --watch=false --browsers=ChromeHeadless
npx ng build
```

`treasury-demo.spec.ts` covers:

- sign-in gating and no auto-start
- double click → one POST with only the key
- a lost reply retried with the same key
- reload → read-only resume that clears the key at the terminal state
- confirmed-stage rendering, including submitted ≠ booked and failed pricing
- failure with no result
- units and values, with no internal ids
- stale green results cleared on a failed or invalid read
- the unconfigured shape and the worker unavailable before or during a run
- refused starts mapped to fixed text
- bounded polling, stopping on navigation and the give-up cap

This was verified against scripted contract stubs. The live backend path is verified by the backend lane.
