# Tape status contract

Date: 2026-10-07. Owner: codex-tape-status. Status: implemented and locally verified; coordinator review pending. Scope: RI25 producer status milestone only.

FR-TS-01: `/health.taqReplay` and replay status carry additive `state` and `reason` strings. Preserve all existing fields and readable error detail. Fields describe availability and transport, never financial or trading validity.

| state | reason | meaning |
|---|---|---|
| not_attempted | NOT_ATTEMPTED | startup load has not been attempted |
| unavailable | EXTRACT_MISSING | file read returned ENOENT |
| invalid | EXTRACT_UNREADABLE | another filesystem read error, including permission refusal |
| invalid | EXTRACT_INVALID_GZIP | bytes cannot be decompressed |
| invalid | EXTRACT_INVALID_JSON | decompressed bytes cannot be parsed as JSON |
| invalid | EXTRACT_INVALID_SCHEMA | parsed value fails the extract checks |
| invalid | CLOCK_MISSING | epoch environment input is absent, empty or whitespace |
| invalid | CLOCK_INVALID | epoch input is nonfinite, nonpositive or outside Date range |
| invalid | CLOCK_UNADDRESSABLE | loaded clock cannot address a tape position at request time |
| replaying | REPLAY_ACTIVE | loaded clock addresses a nonterminal position and is running |
| paused | REPLAY_PAUSED | loaded clock addresses a nonterminal frozen position |
| finished | REPLAY_FINISHED | clock reaches final hold; last price/asOf remain held |

FR-TS-02: Read failures precede clock-input checks; clock-input checks precede gzip/JSON/schema checks. This reports the first failed prerequisite, not an exhaustive error inventory. Structured status does not imply ticker coverage. A paused final hold reports finished plus existing `paused: true`; finished takes precedence. Status derives transport codes from the same `positionAt` used by prices. Missing/invalid input continues the existing synthetic fallback with its current provenance labels; supported valid inputs retain their current economics and journal behavior.

FR-TS-03: Replay-clock prefers structured state/reason pairs. Only known not-attempted/absent pairs without loaded metadata yield synthetic; known loaded pairs require position and no error. Invalid, unknown, incomplete or contradictory pairs alarm. Older producers with neither code retain the existing narrow absence-prose match and fail-safe unknown error behavior. Readable prose remains diagnostic detail, never the discriminator for coded producers.

NFR-TS-01: No new dependency, service, persistence, network acquisition or second replay clock. No ingestion/schema normalization or price/model changes.

SC-TS-01: Actual module with synthetic missing/unreadable/permission/bad gzip/truncated gzip/JSON/schema/clock fixtures returns distinct stable outcomes. Valid control replays, pauses/resumes and holds identical price/asOf.
SC-TS-02: Actual producer statuses reach the consumer; identical prose in absent and corrupt cases produces different classifications. Unknown/incomplete codes and older producers have explicit tests.
SC-TS-03: Generate YU18 into a private output root; compare module/test hashes and execute generated publisher plus consumer suites. Source acceptance does not establish live rig or financial validation.

FR-TS-04 (R1): Tape day opens and derived first/last window timestamps must be finite and representable by JavaScript Date (inclusive numeric range -8640000000000000 through 8640000000000000 milliseconds). Preserve the existing positive-open requirement. Session/window seconds must be finite and positive; milliseconds and endpoint arithmetic must not overflow. Refuse invalid extract time data at load with EXTRACT_INVALID_SCHEMA. At request time the effective wall timestamp (frozen when paused), epoch, raw tape seconds, derived indices and asOf must remain finite and addressable. Invalid runtime arithmetic or unrepresentable timestamps return null position/price and CLOCK_UNADDRESSABLE status. Finite in-range time before the epoch still clamps to the first window, and finite in-range time past the tape still holds the final close. This is a timestamp representation bound, not a financial/session convention. R1 synthetic regressions exercise invalid endpoints, arithmetic overflow and valid Date boundaries through producer and consumer.
