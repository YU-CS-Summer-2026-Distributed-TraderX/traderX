# Feature Pack: YU17-otc-rates

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `functional`
Lineage role: `optional`
Previous state: `YU16-cdm-instruments`

OTC interest-rate swaps and swaptions on the cluster tier: the first instrument class that does not
fit the position model and does not match. A swap booking is a sequenced consensus command that creates
a contract — no order, no book, no crossing, no position — and the EOD cut carries those
contracts one row per trade alongside the netted positions it has always carried. Netting is
lossy for a swap and the second artifact is what stops that loss: receive fixed 4.2% on 10mm and
pay fixed 4.3% on 10mm net to quantity zero at an `(accountId, security)` grain while the account
is locked into paying ~10bp on 10mm for five years. For a fungible instrument the price is what
you paid; for a swap the rate is what the contract is.

Primary intent:

- `TYPE_SWAP_BOOK` (12) on the existing SBE template 1, sequenced through consensus and applied
  in the clustered service — never handed to the matching engine (ADR-062).
- A replicated contract store keyed by the booking's own consensus sequence, snapshotted as
  `T_CONTRACT` at snapshot format 6, restorable from a format-4 or format-5 epoch.
- A swap path in the risk gate: the notional is the notional, and the rate is not a price
  (ADR-063).
- Market conventions — float index, payment frequency, day count, currency — as a compile-time
  table addressed by index, so the per-trade payload fits the existing record and every member
  resolves them identically (ADR-062).
- One cut, two artifacts at one consensus sequence and one `cutSha256`: the netted position
  extract unchanged at CSV schema 3, and a per-contract OTC artifact at schema 2 (ADR-064).
- Terms and nothing else — no NPV, no mark, no curve, no discounting, no sensitivities.
- Swaptions as contract records like any other (ADR-065): `TYPE_SWAPTION_BOOK` (13), the option
  wrapper riding one word, and the exercise style published as a TERM — a European and a Bermudan
  on identical underlying terms are different instruments, and nothing is ever exercised.

Core artifacts:

- `generation/runtime-overrides/order-matcher/.../lmax/SwapConventions.java` — the convention
  table, compiled in, append-only by index
- `generation/runtime-overrides/order-matcher/.../lmax/InputEvent.java` — `TYPE_SWAP_BOOK`,
  `TYPE_SWAPTION_BOOK` and their slot maps on the unchanged 64-byte record
- `generation/runtime-overrides/order-matcher/.../risk/BlpRiskState.java` — `decideSwapBooking`
- `generation/runtime-overrides/order-matcher/.../cluster/MatchingEngineClusteredService.java` —
  the contract store, `T_CONTRACT`, snapshot format 6 and its by-format restore width
- `generation/runtime-overrides/order-matcher/.../cluster/SwapContractCsv.java` — the per-trade
  artifact
- `generation/runtime-overrides/order-matcher/.../cluster/RiskExtractCut.java` — the cut's
  `#contracts` section at cut schema 3
- `generation/runtime-overrides/order-matcher/.../cluster/ClusterGatewayMain.java` — `POST /swaps`
  and `POST /swaptions` over one shared validator
- `system/adr-062 … adr-065`, `system/architecture.model.json`
- `../../scripts/proofs/yu17-swap-netting.sh` — the swap headline live proof
- `../../scripts/proofs/yu17-swaption-terms.sh` — the swaption headline live proof

Target runtime behavior:

- `POST /swaps` with a notional, a fixed rate, a direction, two dates and a conventions name
  returns `{"contractId":"SW-<N>","sequence":N,"booked":true}`, where N is the consensus sequence
  the booking landed at; the applied sequence on every member moves by exactly one.
- A booking the risk gate refuses answers 422 with a `RiskReason` and leaves no contract; a term
  the record cannot represent answers 400 and is never sequenced.
- All three members log `RISK-EXTRACT-CUT seq=N rows=R contracts=C sha256=…` with the identical
  hash, so the contract store is agreed, not sampled.
- `risk.extract.ready` announces both artifacts — `uri`/`sha256` and `contractsUri`/
  `contractsSha256` — sharing `consensusSequence`, `sessionDate` and `cutSha256`.
- `RiskExtractMain --rebuild <cut> <positions.csv> <contracts.csv>` reproduces both files
  byte-identically from the stored cut alone.
- A member destroyed to an empty disk rebuilds and re-renders the identical cut, contracts
  included; a format-4 snapshot from `YU16-cdm-instruments` and a format-5 one from this state's
  first phase both still restore, the latter as swaps with an empty option wrapper.
- `POST /swaptions` returns `SWPT-<N>`; a European and a Bermudan on identical underlying terms are
  two rows in the contracts artifact differing in exactly one column, beside a plain `SWAP` row that
  leaves the two option columns empty.
- The full inherited proof suite stays green: an OTC contract changes nothing for equities, ETFs,
  Treasuries or listed options.
