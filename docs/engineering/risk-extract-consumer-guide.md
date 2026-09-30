---
title: Consuming an EOD risk extract
---

# Consuming an EOD risk extract

The exporter freezes a portfolio at a consensus sequence and writes un-netted positions with counterparty identity. A completion receipt identifies the exact exported bytes. Consumers must validate the cut, schemas, row counts and hashes before constructing a bundle.

## Preserve identity and economics

Keep account and instrument identity, signed position size, currency, contract multiplier and instrument terms together. A symbol is not a complete OTC contract. A Treasury bill, fixed-coupon note and swap have different accrual and schedule requirements; missing conventions should produce a refusal or explicit partial coverage.

Keep the original export bytes. Reformatting CSV or JSON can change the bundle hash even when values appear unchanged. Schema version, terms version and bundle version are distinct and must be checked against the receiving profile.

## Market inputs and returned results

Closing marks need observation provenance and a suitability decision. Arrival time alone cannot establish market freshness. Assumed curves and synthetic fixtures must remain labelled at input, intake and display.

The consumer binds each calculation to the submitted bundle and item identity, checks units and coverage, and retains the original response. A success-shaped HTTP response is insufficient. A partial result must not become whole-portfolio risk simply because its available rows were priced.

## Contracts and local reproduction

- [YU15 extract specification](/specs/YU15-eod-risk-extract/spec)
- [YU18 specification](/specs/YU18-risk-integration/spec)
- [EOD integration boundaries](integration-and-recovery.md)
- [Verification commands](testing-strategy.md)

This guide contains no private bucket locations, portfolio rows or market-data exports. Obtain authorized artifacts from the producer for the intended environment; the public repository's synthetic fixtures exercise contract behavior without redistributing private data.
