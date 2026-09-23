# Dated market-input package v1

Implemented local observation envelope. This is not Alex's curve/pricer contract and is not
wired into EOD bundle v1/v2 or the frozen W0 profile. No pricing readiness is inferred.

## Files and identity

A package contains exactly `manifest.json` and `observations.json`. The manifest schema is
`traderx.market-input-package.v1`; its artifact names `observations.json`, schema
`traderx.market-observations.v1`, observation count and SHA-256 of the **original bytes**.
`packageId` hashes the manifest without packageId, using the existing bundle canonical encoding:
sorted keys, two-space indentation, ASCII escaping, finite JSON, UTF-8 and a final LF.
Whitespace changes to the observation artifact change package identity. Metadata and array order
also participate in identity; semantic equivalence does not imply identical bytes or IDs.
Hashes establish consistency, not authenticity. Publication refuses an existing destination and
uses private files and staging. Keep actual data outside Git; only synthetic fixtures are tracked.

Manifest metadata fields are exactly `businessDate` (YYYY-MM-DD), `valuationTime`,
`availabilityCutoff` (both offset-aware timestamps), and `selection`. Valuation's local date must
match businessDate and cutoff cannot be after valuation. The cutoff is a conservative availability
boundary: even a published observation retrieved after the cut is not admitted. Operators choose
this policy explicitly; this version does not reconstruct historical availability from later downloads.

## Observations

The artifact has exactly `schema` and an `observations` array. Every observation has:

| Field | Meaning |
| --- | --- |
| identity | source, dataset, series, instrument identifiers and observationDate |
| observationTime | Offset-aware time of observation, or null when source gives only a date |
| publicationTime | Offset-aware release time, or explicitly null for unknown |
| retrievalTime | Offset-aware time these bytes were retrieved; never used to invent publication time |
| value | Original decimal string, preserving precision/trailing zeros |
| units | percent or decimal-fraction |
| quoteType | par-yield or overnight-fixing |
| provenance | synthetic, assumed or observed |

This bounded first vocabulary admits yield observations and fixings, not curves, prices or volatility
surfaces. A Treasury par yield is not a zero rate; a SOFR fixing is not a discount curve. No unit
conversion, interpolation, calibration or economic plausibility assertion occurs. Negative finite
rates are representable. Observation identity is unique per source/dataset/series/instrument/date;
intraday revisions require separate versioned series identifiers or a future schema. Identifiers are
bounded tokens, not arbitrary URLs or metadata bags. Never insert secrets into identifiers.

Known observation time must agree with observationDate. Known observation/publication times cannot
follow retrieval, and known publication cannot precede a known observation time. Unknown times stay
null; a date-only observation makes no intraday timestamp claim. Every row is structurally checked,
even if it is not selected. Unselected observations do not determine selection suitability.

## Selection suitability is separate from structural validity

`selection` contains nonempty unique `selected` and `required` arrays of full observation identities,
`maxAgeCalendarDays` (nonnegative integer), and a nonempty unique `allowedProvenance` list. Required
identities must be selected. There is no universal tenor set. The caller selects the exact dated
observations; the validator never substitutes another date, series or provenance. This initial strict
policy also refuses absent optional selected observations; omit unused inputs from selected.

A structurally valid package can be unsuitable. The report gives per-identity reasons for absent
required/selected observations, disallowed provenance, future or stale observation dates, unknown
publication time, and known observation/publication/retrieval timestamps after cutoff. Calendar age
is businessDate minus observationDate; holidays and business-day calendars are not inferred.
Date-only observations are admissible on/before the cutoff date only with known publication and
retrieval at/before cutoff. `suitableForSelection=true` proves only these caller-supplied rules.
`pricingReadiness=NOT_ASSESSED` and `usableForRisk=false` always remain explicit.

CLI build/validate exit 0 for suitable selection, 2 for structurally valid but unsuitable input,
and 1 for malformed or corrupt input. Build retains a valid but unsuitable package for inspection;
it never replaces missing observed data with synthetic data.

## Extension boundary

A future normalized or calibrated artifact must have its own agreed schema, parent package/artifact
hashes, valuation context and explicit transformation/curve assumptions. Observation provenance must
remain distinct from assumptions about interpolation, compounding and model construction. Alex's
curve-input shape and supported financial calculations are still pending. The proposed exchange
request/result schemas can eventually reference this package; they are not activated by this delivery.
