# ADR-076: Read-only GCS staging and explicit archive evidence

Status: Accepted for TraderX's local integration tooling, 2026-09-15.

## Context

The local receipt bridge consumes file URIs, while the existing cloud producer stores exports in GCS. Historical cloud artifacts do not persist the completion event, cluster epoch or valuation timestamp. Cloud object presence cannot establish those missing facts. Alex's accepted ownership split assigns input discovery and submission to TraderX.

## Decision

Add an opt-in local gcloud-backed stager. Resolve each concrete object to a generation, read that generation within a caller-selected byte budget, retain URI/generation/size/SHA-256 privately, and publish a local directory only after validation. Restrict requests to an explicit prefix. Preserve source CSV bytes and the existing bundle v1 hash rules.

A supplied producer receipt is checked with the bridge's shared validator before a local-URI receipt is published. Retain the exact original receipt separately. This verifies consistency, not producer authentication; the caller must still provide recorded epoch, valuation time and origin when invoking the bridge.

For historical archives, validate positions/contracts and the source cut, but publish no receipt and assert no missing context. Archive inspection cannot silently become a ready job. GCS provenance is a private staging sidecar, not a new field in the frozen bundle schema.

## Consequences and verification

No cloud write, deployment, scheduler or worker API is needed. gcloud credentials and storage-read permission are required only for this command; core local tests remain offline. Identical staging retries are accepted after re-reading and validating sources. Changed generations or existing local bytes require a new destination. Interrupted staging publishes no completed directory; publication-lock recovery follows the existing local publisher rules.

Tests cover failure before publication, generation loss, metadata/size mismatch, corrupt receipts and cuts, empty OTC exports, path restrictions, duplicate delivery, and receipt staging through the coordinator. A real historical archive read verifies the GCS CLI separately from the fake-store tests. A live producer-to-coordinator proof still requires a recorded completion event and its valuation/run context.
