# Decisions requiring human and Alex agreement

Status: open, October 7, 2026. These are questions for review, not newly adopted conventions. The [mapping](field-mapping.md) and [examples](examples.json) describe existing support.

| ID | Gap / decision | Owner and evidence needed | Current handling |
| --- | --- | --- | --- |
| D1 | Bind issuer security identity and distinguish original/reopening issue dates | Human reference owner + Alex: verified identifier mapping, exact announcement/effective date, meaning of schedule start | Synthetic UST keys retained; CUSIP unknown. No guessed identifier or shortened stub. |
| D2 | Establish instrument currency independently of account currency; define any conversion | Human + Alex: instrument reference, unit/FX policy and accepted contract | Existing examples USD only; exporter account currency is described honestly. |
| D3 | Decide bill quote basis and rate input contract | Human + Alex: discount yield vs investment yield vs zero-curve input, transformation and acceptance evidence | TreasuryDirect days/360 explanation remains separate from engine assumed discount time. Coupon zero is not the discount rate. No new rate or pricing result. |
| D4 | Agree actual schedule, day count, stubs, calendars, payment/settlement lag and accrued valuation basis | Human + Alex: security-specific dates and executable bilateral semantics | Existing regular semiannual/no-stub/session-date fixture assumptions retained. Terms-v2 structural support does not approve real settlement semantics. |
| D5 | Represent standard/adjusted series identity, premium/exercise factors and deliverable components separately | Human product/reference owner + Alex: listing specification and applicable OCC memo/effective version, identity incl. share class, cash/cash-in-lieu and predecessor | Standard parser support is limited; adjusted illustration cannot be faithfully used. No root stripping, alias substitution or mapping deliverable shares into multiplier. |
| D6 | Choose listed-option expiry cutoff/time zone, exercise style, settlement method/calendar and lifecycle | Human + Alex: series terms plus explicit input/result/lifecycle agreement | Parser date and synthetic 21:00 UTC assumption documented; OTC swaption fields not borrowed. No exercise/settlement claim. |
| D7 | Agree option financial capability, market inputs and result units before expanding intake | Alex + human acceptance owner: versioned contract/capability review and independent validation | No listed-option terms/pricer admitted; illustrative premium is invented. Upstream newer HEAD is not accepted by inference. |
| D8 | Approve any future automated reference acquisition and permissible use | Human: source terms/licensing/access/format review before reader/feed work | Only explanatory pages read. No rights inferred from public access/private storage; no datasets or redistribution permission claimed. |

The fictional adjusted counterpart is an explicit hypothetical input, not an OCC adjustment decision. Its effective date is an invented illustration date; its memo reference remains null. General ODD explanations cannot certify a real series. Expanding an input schema or consumer allowlist requires separate implementation review, not a documentation edit.

Shared queue/index updates, integration and any later implementation belong to the coordinator. This research milestone can be reviewed independently while these decisions remain open.
