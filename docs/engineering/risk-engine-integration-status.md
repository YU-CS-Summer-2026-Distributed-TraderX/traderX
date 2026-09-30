---
title: Risk integration status
---

# Risk integration status

Current integration is YU18, with behavior and limits documented in [EOD integration and recovery](integration-and-recovery.md). The closed container profile uses a synthetic Treasury-bill bundle and an assumed curve; production portfolio risk remains unavailable.

The former August 12 page described YU16 and an earlier external engine interface. Its rounded performance statements, **~260k orders/s**, **~6M/s matching-core throughput**, **~220 µs consensus commit** and **p50 client RTT under 1.5 ms**, were historical claims without a complete per-run manifest on that page. They are preserved here for traceability, not restated as current YU18 measurements. The more specific published figures and provenance gaps are in [measurement context](measurements.md).

Use the [feature map](feature-map.md), [consumer guide](risk-extract-consumer-guide.md) and [verification commands](testing-strategy.md) for current behavior. Financial-engine support must be checked against the agreed external revision and profile, independently of which instruments TraderX can book.
