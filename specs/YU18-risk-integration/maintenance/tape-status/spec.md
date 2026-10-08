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

## Offline declared-universe diagnostic (evening RI25)

FR-TS-05: A standalone local CLI requires explicit publisher-default selection or a supplied JSON environment declaration and a supplied gzip v1 tape extract. It snapshots supplied bytes, records source hashes/roles and evaluates the pinned publisher declaration expression and actual tape loader without starting the publisher. No inherited shell PRICE_TICKERS or live quote sample is input. Report declared keys, tape keys, intersection and both differences as sorted exact identities. Empty primary populations cannot yield successful analysis; supplied missing/invalid inputs have unavailable/invalid states and null relations. Normalization is solely the actual declaration split/trim/uppercase/filter transformation, recorded alongside raw tokens; no tape/reference aliases are inferred. Duplicate effective declaration identities and duplicate JSON keys are diagnostic refusals, explicitly stricter than the producer's existing array/last-value behavior.

FR-TS-06: Optional supplied reference CSV is an exact directory inventory using an explicitly selected column (default ticker), not the reference service's seeded/filter/capped view or engine admission. Strict CSV/identity/duplicate checks are diagnostic-specific; record the actual loader's uppercase/FB-to-META/supplement/filter/cap differences. Optional gzip TAQP1 print sample uses the pinned actual decoder. Report exact sample/tape/declaration relations and clock/calendar agreement separately; sample missing tape or clock mismatch marks runtime alignment false, preserving actual loader all-or-nothing semantics. Static candidate keys do not imply addressable replay time, enabled accounts/security or live tradability.

NFR-TS-02: Offline standard-library Python/Node only. Pinned current reader hashes refuse unsupported source changes. Input/decompressed/output/identity budgets are adjustable local diagnostic safety limits, never production capacity recommendations. No runtime source/config, data normalization, stored object, finance, clock, engine, network or universe widening change. Output is private (0600), bounded and outside Git checkouts; stdout gives status/counts/output path, no identities. No timestamps/random fields in the report, so identical supplied bytes and paths produce deterministic output. Non-supplied optional roles and relations stay explicitly unavailable.

SC-TS-04: Actual CLI and readers against independently inventoried synthetic fixtures produce nonempty overlap/exclusions, exact case/root/suffix distinctions and optional directory/print gaps. Missing/corrupt/empty/duplicate/unsupported source inputs refuse without empty successful sets. Explicit/default/empty-string default selection follows publisher semantics. Hash/determinism/private-output and omitted/miscalculated-set negative controls exercise the diagnostic. Prior tape status regression suite and private generated reader parity remain required; no historical cloud counts become current claims.

## Offline CT/CQ source identity loss (RI25 follow-up)

FR-TS-07: A local diagnostic snapshots explicitly supplied synthetic/raw CT or CQ CSV and pins the actual YU07 ingester source. Parse its Python AST as data, extracting only the current read_csv/header/auto_detect, SELECT and WHERE portion from the COPY expression; do not import/call ingest/main, GCS or COPY/write/Parquet. Unsupported source/query shape refuses. Cached DuckDB only, in-memory pure SELECT execution with extension installation/loading disabled and 20-second child limit. Observe actual reader values/types, filter eligibility and current output symbol, without defining canonical ticker mapping.

FR-TS-08: Report unfiltered-reader and eligible-current-projection scopes separately. Count exact reader-observed (root,suffix) tuples, repeated observations of an identical tuple, distinct tuples per output symbol and lost distinctions. Repeats alone are not identity collisions. Exact case, SQL NULL and literal strings remain distinct where the actual reader distinguishes them; raw lexical empty/NULL meanings and inferred types are recorded as reader semantics, not vendor interpretation. Actual current SELECT must agree with annotated lineage output count/symbol multiset. Missing suffix/columns, malformed/incompatible/empty/zero-eligible/over-budget input cannot pass empty evidence. Source/data/query/dependency hashes and private bounded exclusive output are required.

SC-TS-05: Independent nonempty synthetic CT/CQ fixture inventories show suffix collapse, exact-tuple repeats without false collision, different-root/case controls, null/empty reader behavior and CT price-vs-CQ quote eligibility. Actual SELECT and annotated reader lineage are exercised. Omitted-suffix/wrong-projection implementation mutants fail independent acceptance. Previous universe/status tests and generated ingester-source parity remain intact. No actual vendor data, Parquet conversion, normalization, storage, financial or cloud claims.
