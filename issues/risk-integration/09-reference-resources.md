# RI-09 — Mentor resources and instrument reference research

Updated: 2026-10-07. Status: in progress (field mapping and synthetic research examples reviewed locally; contract decisions remain). Maintainer: coordinator; original delivery notes below retain their dates.

The Citadel mentor recommended TreasuryDirect and OCC. The applications below are proposed project uses, not claims that either source is already integrated.

## TreasuryDirect

- [TreasuryDirect](https://www.treasurydirect.gov/): official Treasury securities and auction information.
- [Treasury bills](https://www.treasurydirect.gov/marketable-securities/treasury-bills/): bill terms, discount/par and maturity explanation.
- [Auction announcements, data and results](https://www.treasurydirect.gov/auctions/announcements-data-results/): research candidate security and auction reference data. The page currently announces XML/API specification changes; verify the current format before building a reader.

Proposed use: validate bill/note instrument fields, identifiers, issue/maturity terms and auction-related fixtures. Do not treat an auction result as a live secondary-market quote or a complete calibrated curve.

## OCC

- [OCC](https://www.theocc.com/): clearing/settlement reference, series search and information-memo entry points.
- [Investor education](https://www.theocc.com/company-information/investor-education): starting point for option product and risk education.
- [Market-data reports](https://www.theocc.com/market-data/market-data-reports/volume-and-open-interest/daily-volume): volume/open-interest research entry point, not a live bid/ask feed.

Proposed use: verify listed-option contract identity, deliverables, exercise/expiry/settlement semantics and adjustment handling using applicable primary documents. Inspect relevant information memos for adjusted contracts. These links alone do not establish a usable quote feed, NBBO service or redistribution permission.

## Tasks and acceptance

- [x] Select concrete Treasury and option examples and link exact source pages/documents with retrieval dates.
- [x] Map supported reference fields to TraderX terms and Alex's inputs; identify missing semantics.
- [ ] Record document version, source, units, effective dates and transformations in fixtures.
- [ ] Verify access, format and permitted use for any automated ingestion; keep vendor/raw data out of the public repository.
- [ ] Turn agreed examples into reproducible synthetic or otherwise permitted reference tests.

Next: Agree the listed contract decisions before ingestion or financial acceptance.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.

## RI09 field-mapping milestone (October 7, 2026)

Owner: codex-reference-mapping, chat `01a118e5-5c53-7bc3-8930-fed27c3d1025`. Base: `22d4c9067ce6a01f33678e7cb061c8d0e677e9c3`. Status: research and synthetic illustrations implemented and structurally checked in an isolated checkout; coordinator reviewed locally; integration outcome recorded below.

[Maintained mapping](../../docs/risk-integration/instrument-reference-mapping/README.md) records TreasuryDirect/OCC sources, field-to-owner support, exact accepted engine/profile pins and four synthetic bill/note/standard/adjusted examples. Listed-option terms and adjusted deliverables remain unsupported; real issuer identity, calendars/settlement/quote basis and financial validation remain open. [Decisions D1–D8](../../docs/risk-integration/instrument-reference-mapping/decisions.md) require human/Alex agreement. The broader checklist above is not closed by research alone.

Next: human/Alex decide any subsequent reference contract or ingestion work. No runtime/schema change, market-data acquisition, publication or pricing execution accompanies the delivery.

Local evidence: two original bundle/terms checks and input-profile distinctions, two option CSV acceptances with terms refusals, actual Java/Node symbol parsing, all documentation gates and 27 links passed. All 18 Treasury term names are covered. Source/schema hashes are recorded in examples.json; private command logs are under the parent coordination review-evidence/ri09-reference-mapping-20261008 directory. No engine or pricing function was run.
