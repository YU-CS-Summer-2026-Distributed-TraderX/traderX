# Local intake of Alex’s W0 outputs

TraderX can run Alex’s real adapter against the shared synthetic bill, long/short note and SOFR swap bundles, independently validate its outputs, and retain the accepted bytes in the durable local job tracker. This uses no cloud resources, no market-data downloads and no pricing dependencies.

## Reproduce

From this worktree:

```sh
python3 scripts/demo-state-YU18-alex-w0.py --engine /path/to/JAX_Risk_Engine
```

The demo requires clean engine source at `cb9b277a9de702b2ba4f0bcda431a396f54c029a`. It prints the path to private temporary evidence and stored results. Each example has its own coordinator state because the fixtures represent alternative portfolios at the same cut. It exercises the real `engine.integration.price_bundle` function; the captured JSON fixtures also let our tests run without Alex’s checkout.

For manually supplied results, use `coordinator.py --state /private/path/state --w0-results /private/path/results discover /private/path/inbox`, then the same options with `run` or `status`. Name each input result `BUNDLE_ID.json`. Publish complete files atomically. Use a separate state directory from mock/HTTP profiles. Missing results remain pending and reuse their attempt when supplied; this adapter does not submit or execute a remote job.

## What acceptance means

`W0_VALIDATED` means the received adapter outcomes match the accepted input and this pinned compatibility profile. The original output and a local acceptance receipt are retained and revalidated when status is read. `selectedW0Result` is separate from `selectedMockResult`; `usableForRisk` stays false. The receipt’s engine commit is an operator-selected compatibility profile, not authenticated producer identity. The demo additionally verifies the local producer checkout.

The validator recomputes stable item identities, item-order hashes and calculation coverage, reconciles every account/security/contract/currency, checks SOFR missing conventions, and verifies signed accrued-interest conversion. Note conversions use the exported fraction multiplied by signed face amount, with an absolute 0.00000001 currency tolerance for serialization; this is not an independently calculated accrual tolerance. Bills report structural zero. W0 provides no NPV, sensitivities or portfolio VaR/ES. Per-item VaR/ES non-applicability must not be interpreted as portfolio risk completion.

This deliberately narrow profile accepts bundle v2 with terms v1, complete Treasury terms and incomplete SWAP terms, without market inputs. It rejects terms v2, different output shapes, invented numerical prices, altered coverage, missing/duplicate items and mismatched inputs. New engine versions require explicit review and a new compatibility profile. Existing v1 hashes and shared input bytes remain unchanged.

## Next integration boundary

Alex still needs to consume the structured accrual basis in terms v2, agree the output schema/version and exported-accrual provenance, and expose durable authenticated submission and result retrieval. Numerical bill/note pricing and curve-backed risk remain separate work. This local exchange proves adapter interoperability and result custody, not overnight financial computation or GKE deployment.

## Recorded verification

On 2026-09-15, the real-checkout demo passed for all three examples against both the authoritative and regenerated component. The complete local suite passed 106 tests in each location, including corrupt-output rejection, same-attempt pending completion, recovery after result publication, stored-result tampering and profile isolation. Root SpecKit, documentation front-matter and spec-coverage checks passed. No financial valuation, cluster deployment or cloud expenditure was part of this verification.
