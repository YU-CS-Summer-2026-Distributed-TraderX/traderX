# Research: EOD Risk Bundles

## Existing foundations

YU17 `RiskExtractCsv` produces schema 3 positions and `SwapContractCsv` produces schema 2 OTC terms from the same cut. Their preambles share four cut fields. `RiskExtractMain` also emits individual file hashes. The CLI preserves the original CSV bytes and validates internal consistency; it does not authenticate the producer or attest correspondence to the cluster log.

## Chosen boundary

An explicit file invocation avoids relying on the inherited core-NATS ready notification as a durable delivery mechanism. Python standard-library CSV, Decimal, JSON and hashing keep the additional runtime local and dependency-free. The source exports define the financial units; the wrapper does not reinterpret quantities or calculate prices.

## Identity and integrity

A content-derived bundle ID covers the manifest, which covers artifact hashes. The caller supplies the cluster epoch because the export preambles do not contain one. The manifest names an explicit valuation timestamp separately from the session date. Consumers compare the entire expected manifest rather than trusting supplied paths.

## Current operational limits

The CLI reads each small portfolio export into memory. It operates on a trusted local filesystem. Publication uses a sibling exclusive lock and directory rename; a hard-killed publisher can leave a lock/staging directory requiring operator inspection. Local SHA-256 checks are not signatures. Market inputs are explicitly absent. Mock success proves transport acceptance only. The contract is a TraderX prototype and carries no claim of compatibility with an external engine.
