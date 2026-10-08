# Offline tape universe coverage

This diagnostic compares supplied source roles. It never changes `PRICE_TICKERS`, starts a publisher, fetches quotes, submits orders, or connects to a service. Results describe the supplied declaration, tape, directory and print artifact; they do not establish engine admission, instrument support, licenses or tradability.

Run Python 3 and Node with no added packages:

```bash
python3 scripts/diagnostics/tape-universe/coverage.py \
  --declaration-config /private/tmp/supplied-price-config.json \
  --tape-extract /private/tmp/supplied-extract.json.gz \
  --reference-csv /private/tmp/supplied-directory.csv \
  --reference-column ticker \
  --print-sample /private/tmp/supplied-prints.bin.gz
```

`--declaration-config` accepts an explicitly supplied JSON object containing only optional `PRICE_TICKERS`, whose value must be an environment string. An absent key or empty string chooses the actual publisher source default. `--declaration-default` explicitly selects that default without a config file. The CLI never takes a declaration from the caller's environment, a live `/prices` response, YAML guessing, or another service. A whitespace-only string becomes empty under the publisher's filter and is refused as coverage evidence.

The pinned `main.js` declaration expression runs in an isolated VM, including its actual split/trim/uppercase/filter behavior. Raw tokens, selected source default and effective identities are recorded. This declaration is `PRICE_TICKERS` only; the publisher's separate option-contract declaration is outside this diagnostic. Tape and CSV keys remain exact. Whether similar roots, suffixes or case variants identify the same real security is not established by this diagnostic. AAA/aaa and BRK/BRK.B/BRK/A cannot be silently merged. Duplicate effective declarations, duplicate JSON keys and duplicate directory/sample identities are refused rather than silently collapsed; these diagnostic checks are stricter than the runtime's existing array/JSON behavior.

Tape input is a gzip v1 extract. Bounded UTF-8/JSON/duplicate preflight precedes the pinned actual `taq-replay.load` against a private byte snapshot. A synthetic epoch value of 1 permits validation only; no replay position or price is sampled. Thus the key inventory is independent of quote timing or current wall time. Nonempty valid declared/tape populations yield deterministic overlap, declaration-without-tape and tape-excluded-by-declaration lists. A zero intersection of two nonempty populations is valid evidence of no overlap, not a healthy-coverage verdict.

Optional CSV is an exact supplied directory using the selected case-sensitive header. Quoted fields are parsed by Python's strict CSV reader. Empty/boundary-whitespace identities, malformed rows, duplicate headers and duplicate identities refuse the directory role. This deliberately differs from the actual reference loader: it uppercases the first CSV column, maps FB to META, deduplicates, appends supplemental/ETF/Treasury seeds, and applies supportedTickers/maxTickers. The diagnostic reports directory-missing identities without reproducing those transformations or treating a file as database/outbox/engine state.

Optional print input is a gzip TAQP1 binary sample. The pinned actual decoder reads its metadata. Lists report sample keys, static declaration/tape candidates, exclusions and declared/tape keys absent from the sample. Clock/calendar alignment and sample keys missing from tape are separate: `runtimeArtifactAlignment: false` means the actual print loader's all-or-nothing artifact checks would refuse the pair. A `valid` sample source means decoded metadata, not successful runtime loading/enablement. No account, quantity, stride, addressable clock, gateway, risk gate or actual order-flow claim is made.

Missing/invalid required input prevents core relations. Non-supplied optional roles have `not_supplied` state and null relations; a supplied optional failure makes overall analysis incomplete even if core relations can still be computed. Exit 0 means the source-role analysis completed, not that coverage is full. Exit 2 means incomplete/refused input or output. Unknown reader hashes/sizes or declaration forms refuse rather than guess. Reader/hash changes need a fresh source-semantic review; no automatic pin refresh.

Outputs are bounded private files (0600) outside Git checkouts. Stdout prints status, counts and output path only. `--output` is optional and never overwrites an existing path. By default the file is in the system temporary directory. Reports include exact captured input and decoded hashes, reader/diagnostic source hashes, raw identities and limitations. There is no creation timestamp or output filename in the report, so identical bytes and supplied paths produce identical report bytes. Invalid reader messages replace private snapshot paths with the original supplied path. Keep real inputs and reports private; only explicitly synthetic fixtures are tracked.

The adjustable defaults (16 MiB per input/decoded stream, 10,000 identities and 4 MiB report) are local diagnostic safety budgets. They are not production capacity, tape sizing, or performance recommendations. A regular-file check, reader byte-size pins and subprocess timeout bound reader work. Unsupported larger input is refused; no truncation masquerades as complete coverage.

Synthetic dry run:

```bash
python3 - <<'PY'
from pathlib import Path
import gzip
fixture = Path('scripts/diagnostics/tape-universe/fixtures/tape.json')
Path('/private/tmp/synthetic-coverage-tape.gz').write_bytes(gzip.compress(fixture.read_bytes(), mtime=0))
PY
python3 scripts/diagnostics/tape-universe/coverage.py \
  --declaration-config scripts/diagnostics/tape-universe/fixtures/declaration.json \
  --tape-extract /private/tmp/synthetic-coverage-tape.gz \
  --reference-csv scripts/diagnostics/tape-universe/fixtures/reference.csv
```

This dry run inventories invented inputs and writes only a private diagnostic report. Acceptance:

```bash
python3 scripts/diagnostics/tape-universe/test_coverage.py
```

For isolated generated readers, pass `--publisher-root <generated-root>/code/target-generated/price-publisher`; the same pins and declaration/read semantics must match. The CLI remains a repository diagnostic, not an installed generated service. See the existing [tape-status component](../../../specs/YU18-risk-integration/components/tape-status/README.md).
