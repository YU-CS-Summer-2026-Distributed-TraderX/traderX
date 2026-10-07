# RI-14 implementation plan

Owner: codex-portfolio-generator. Local delivery complete pending review; large campaigns deferred.

Keep source in state-parent `generation/runtime-overrides/risk-portfolio-tools`, with a root convenience CLI. Add only that module to YU18 renderer; no runtime deployment or startup wiring. No ancestor provides this new module.

Use Python standard-library streaming serialization, deterministic seed/settings identity and immutable output directory publication. Schema imports and engine pricing are opt-in verification in the engine dependency environment. Reuse engine schema and validation rather than copying general pricing. The independent reference is restricted to four explicit regular synthetic cashflows and accrual elapsed-day math.

Use sequential Python HTTP transport for bounded local smoke and detailed custody; no added dependency. k6 is a candidate for a future measured arrival-rate campaign; its SharedArray/copying behavior and payload memory need evaluation before adopting it. This delivery makes no k6/Locust throughput comparison or cloud-scale claim.

RI16 controls the additive header/recovery contract. The driver uses `Idempotency-Key` and GET lookup, preserves the base body, and never automatically retries POST even if a service advertises idempotency. Unknown means no reliable completion verdict. Interrupted is terminal with unknown completion; explicit reruns require a separately chosen new identity.

Size ladder: 100,1000 smoke now;10000,100000 projected, not run. Validate client payload and cube limits before proposing service work. General market-risk mixed-currency aggregation remains held by the October7 audit. Pricing kernels remain external.
