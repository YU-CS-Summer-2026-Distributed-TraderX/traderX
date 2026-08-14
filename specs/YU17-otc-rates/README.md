# Feature Pack: YU17-otc-rates

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `functional`
Lineage role: `optional`
Previous state: `YU16-cdm-instruments`

OTC interest-rate swaps on the cluster tier: the first instrument class that does not fit the
position model and does not match. A swap booking is a sequenced consensus command that creates
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
  `T_CONTRACT` at snapshot format 5, restorable from a format-4 epoch.
- A swap path in the risk gate: the notional is the notional, and the rate is not a price
  (ADR-063).
- Market conventions — float index, payment frequency, day count, currency — as a compile-time
  table addressed by index, so the per-trade payload fits the existing record and every member
  resolves them identically (ADR-062).
- One cut, two artifacts at one consensus sequence and one `cutSha256`: the netted position
  extract unchanged at CSV schema 3, and a per-contract swap artifact at schema 1 (ADR-064).
- Terms and nothing else — no NPV, no mark, no curve, no discounting, no sensitivities.

Core artifacts:

- `generation/runtime-overrides/order-matcher/.../lmax/SwapConventions.java` — the convention
  table, compiled in, append-only by index
- `generation/runtime-overrides/order-matcher/.../lmax/InputEvent.java` — `TYPE_SWAP_BOOK` and
  its slot map on the unchanged 64-byte record
- `generation/runtime-overrides/order-matcher/.../risk/BlpRiskState.java` — `decideSwapBooking`
- `generation/runtime-overrides/order-matcher/.../cluster/MatchingEngineClusteredService.java` —
  the contract store, `T_CONTRACT`, snapshot format 5
- `generation/runtime-overrides/order-matcher/.../cluster/SwapContractCsv.java` — the per-trade
  artifact
- `generation/runtime-overrides/order-matcher/.../cluster/RiskExtractCut.java` — the cut's
  `#contracts` section at cut schema 2
- `generation/runtime-overrides/order-matcher/.../cluster/ClusterGatewayMain.java` — `POST /swaps`
- `system/adr-062 … adr-064`, `system/architecture.model.json`
- `../../scripts/proofs/yu17-swap-netting.sh` — the headline live proof

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
  included; a format-4 snapshot from `YU16-cdm-instruments` still restores.
- The full inherited proof suite stays green: a swap changes nothing for equities, ETFs,
  Treasuries or listed options.
