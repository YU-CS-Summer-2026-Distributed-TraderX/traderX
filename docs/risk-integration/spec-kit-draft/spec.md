# Specification — proposed revision 0.1

## Purpose

Allow external producers to submit immutable portfolio inputs and receive attributable, complete pricing/risk outcomes from one canonical engine. Integration must not create a second financial engine.

## Requirements

| ID | Proposed requirement |
|---|---|
| FR-01 | Verify input bytes, schema versions, identities and joins before execution or result reuse. Reject conflicting identities and malformed artifacts. |
| FR-02 | Translate supported inputs into canonical engine requests. Production valuation, schedules, curve construction and Greeks have one owning engine implementation per supported model/convention. |
| FR-03 | Discover supported product/convention/calculation combinations through versioned capabilities. An unsupported combination receives an explicit refusal. |
| FR-04 | Every requested item/calculation has exactly one attributable outcome; refusal, missing input and failure cannot disappear into an aggregate. |
| FR-05 | Market inputs are explicit and attributable. Assumed inputs require deliberate selection and remain labeled assumed in results. Missing observations never trigger silent substitution. |
| FR-06 | Request options are applied as documented or rejected. Reporting currency is never silently ignored. Calculation selection is explicit; additional reconciliation fields must be documented. |
| FR-07 | A submission ID binds to one workload before any cache return. Same-ID retries recover its attempt; different-workload reuse is rejected, including when either workload is cached. |
| FR-08 | One execution owner prices an attempt. Overlapping retries do not execute that same attempt again. This is not a promise of exactly-once execution across arbitrary crashes. |
| FR-09 | Completed results and recorded failures survive restart. Running work has an explicit recovery policy; a lost process must not become an unexplained perpetual running job. |
| FR-10 | Publish immutable terminal results atomically with verified hashes. A missing/stale convenience pointer must not hide a committed result. |
| FR-11 | Result identity includes source workload, attempt, engine build, mapping/schema versions and market provenance. Cache identity includes every input or implementation version that changes results. |
| FR-12 | Schema evolution and consumer migration are explicit; existing accepted profiles remain pinned until a new profile passes acceptance. |

## Initial delivery scope

Proposed first profile: local, single-worker, synthetic USD Treasury bills and fixed-rate notes using an explicitly named assumed curve. This bounds the first acceptance target; it does not claim real Treasury settlement or production risk readiness.

Equity spot/FX, faithful SOFR conventions, broader risk calculations and distributed execution require separate capability decisions. They must remain visible as unsupported or unavailable where applicable.

## Nonfunctional requirements

- NFR-01: no producer-specific imports inside canonical pricing/risk code; producer metadata belongs at the boundary.
- NFR-02: deterministic reference cases and explicit numerical tolerances; matching two paths that share a bug is insufficient numerical evidence.
- NFR-03: every acceptance result records revision, environment, fixture identity and execution level (source/local HTTP/deployed).
- NFR-04: public examples are synthetic; secrets and private market/portfolio data stay outside the shared pack.

These are proposed obligations, not statements that the current implementation satisfies them.
