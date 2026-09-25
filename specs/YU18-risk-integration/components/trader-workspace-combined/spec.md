# Combined desk requirements

- FR-CUI-01: Use Codex-style login and Claude’s TraderX Desk brand. Fixture identity selection must not claim authentication.
- FR-CUI-02: Keep five primary trader tabs and secondary tools in More, with a separate admin workspace.
- FR-CUI-03: Bind desk views to the selected authorized fixture account and run. Historical views must not submit, replace or cancel orders.
- FR-CUI-04: Clear ticket values on account, product or instrument changes. Prices must not cross unit boundaries.
- FR-CUI-05: Show unavailable portfolio risk when no matching account/run result exists. Keep reference examples separate and synthetic.
- FR-CUI-06: Preserve the existing console and both independent candidates.

Acceptance: build and 20 Angular tests; browser inspection of login, overview, orders, Risk and admin; 390px order-page inspection with no horizontal page overflow. Tests in workspace.spec.ts cover FR-CUI-03/04 plus inherited session/account behavior. Screenshots and verification.md provide UI evidence for FR-CUI-01/02/05. Git changed-path review covers FR-CUI-06.

Future production requirements: server authentication and authorization, two-user isolation against real APIs, freshness/reconnect handling, feature parity and migration acceptance. Current fixture guards do not satisfy those requirements.
