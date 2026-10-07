# RI-14 requirements

Status: implemented local tools; service integration review pending. Owner: codex-portfolio-generator.

- FR-PG-01: Generate deterministic seeded USD bills and fixed-rate notes, signed faces, unique IDs, valid explicit regular semiannual schedules and synthetic flat market inputs. Same settings reproduce the same bytes and identities; a new seed changes corpus identity.
- FR-PG-02: Keep economics pinned to `MarketPortfolioRequestSchema` at engine `2df78cb`; provenance, corpus/portfolio/submission identities and aggregate face/counts remain in a sidecar manifest.
- FR-PG-03: Bound total generation to 100000 trades, 1000 portfolios and 128 MiB including manifest. Stream one trade at a time. Refuse existing destinations and Git checkout output. Clean unpublished staging on failure.
- FR-PG-04: Support one large portfolio and many independent portfolios; repeated and heterogeneous bill/note shapes. Do not partition and sum nonlinear portfolio risk.
- FR-PG-05: Verify request byte identity, counts, economic aggregates and unique IDs. Native engine schema verification is optional and revision pinned; file-only tools use the standard library.
- FR-PG-06: Preserve exact HTTP request/reply bytes and receipts. Submit at most once; explicit GET recovery/polling. Retain known job IDs across timeout, failed, interrupted and unknown states. HTTP202 alone is not completion.
- FR-PG-07: Validate completed result IDs, count, finite signed NPVs and sum reconciliation. For the four-position golden fixture compare dirty NPV and accrued values to independent explicit cashflows and elapsed coupon days. General financial validation is outside this reference.
- NFR-PG-01: Client request/validation 8 MiB, reply16 MiB, accumulated raw replies128 MiB, maximum1000 polls; configurable finite HTTP and total deadlines. Loopback HTTP only. Base-NPV default; optional <=128 scenario samples and three future dates with raw float64 cube lower bound<=64 MiB. Intermediates and JSON cost more; this is not an engine memory guarantee.

Acceptance: `bash scripts/test-portfolio-generator.sh`; native `verify_engine.py`; 100/1000 local file smoke; YU18 generated source parity. Real RI16 service proof is separate from HTTP doubles.
