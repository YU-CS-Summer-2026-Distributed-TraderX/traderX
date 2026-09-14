# ADR-066: Local immutable EOD bundles

Status: Accepted for the local transport prototype

## Context

YU17 exports two artifacts from one cut. Consumers need to reject mixed files and distinguish resets without requiring access to the live cluster. Development must operate locally with small portfolio files.

## Decision

Use a Python standard-library CLI, fixed file names, a versioned JSON manifest and content hashes. Preserve source CSV bytes and require an explicit epoch. Stage output privately and publish under a new destination. A mock echoes identities and reports no priced instruments.

## Consequences

The workflow is reproducible and independent of compute infrastructure. Hashes detect integrity changes but do not authenticate source state. The caller controls the epoch, valuation timestamp and private storage location. The CLI is a transport prototype with no automated EOD trigger or financial valuation. The manifest records absent market inputs rather than implying complete pricing data.
