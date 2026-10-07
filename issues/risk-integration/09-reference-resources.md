# RI-09 — Mentor resources and instrument reference research

Updated: 2026-10-07. Status: queued field-level reference research; original resource links retained. Maintainer: coordinator; original delivery notes below retain their dates.

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

- [ ] Select concrete Treasury and option examples and link exact source pages/documents with retrieval dates.
- [ ] Map supported reference fields to TraderX terms and Alex's inputs; identify missing semantics.
- [ ] Record document version, source, units, effective dates and transformations in fixtures.
- [ ] Verify access, format and permitted use for any automated ingestion; keep vendor/raw data out of the public repository.
- [ ] Turn agreed examples into reproducible synthetic or otherwise permitted reference tests.

Next: Map concrete Treasury/option reference fields and permitted test examples.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
