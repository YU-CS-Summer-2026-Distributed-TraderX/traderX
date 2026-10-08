# Instrument reference mapping tasks

Date: 2026-10-07. Owner: codex-reference-mapping. Status: research implemented; local structural validation passed; coordinator review pending.

- [x] Verify accepted base, read current guidance and post exact isolated CLAIM.
- [x] Read primary explanatory sources, record version/retrieval/section metadata (FR-IRM-01).
- [x] Trace operative owners and accepted engine schema/profile pins (FR-IRM-02).
- [x] Deliver Treasury/option mappings and four explicitly synthetic illustrations (FR-IRM-03).
- [x] Record unsupported/unknown fields and human/Alex decisions D1–D8 (FR-IRM-04).
- [x] Run local existing validators, profile allowlists and pure parsers (SC-IRM-01/02).
- [x] Verify links, documentation gates, diff and owner/schema hashes (SC-IRM-03).
- [x] Prepare scoped delivery and READY_FOR_REVIEW evidence for coordinator; commit/board delivery recorded outside these docs.
- [ ] Coordinator review and integration; shared index updates remain coordinator-owned.

No tests reproducing prose or new schemas are added. Validation uses the existing contracts directly. No financial approval, source-data acquisition or live execution is part of these completion boxes.

Measured: both original synthetic bundles and copied row/terms fields pass existing validators, covering all 18 Treasury terms. Container input allowlist accepts bill and refuses note; historical provisional input allowlist accepts both. Both option rows pass the CSV structural parser with invented parser-only metadata; both OPTION terms entries are refused. Actual Java/Node parsers recognize the letter-only standard root and reject the numeric counterpart; Java fallback multiplier is 1. No option exporter or engine execution was performed.

Component layout (25 packs), frontmatter (33 files), root gates, readiness, spec coverage (33 states), 27 local links and diff checks pass. Pre-existing PowerShell parity declines remain reported by the root gate. No generated or live validation is claimed. Evidence: coordination/eod-integration/review-evidence/ri09-reference-mapping-20261008 under the parent workspace, with commands/logs/owner-schema hashes.
