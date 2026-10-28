# Control feed republish specification

Status: implemented (`3b10e7e2`), unit-verified; on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13). Scope: `reference-data`'s security control outbox
and the `TRADERX_CONTROL_SECURITY` stream it feeds. The account-service outbox is out of this change.

- FR-CFR-01: `reference-data` SHALL compare its outbox's `published` flags with the stream on the first outbox
  poll after start and every `SECURITY_OUTBOX_AUDIT_INTERVAL_MS` (default 30000) after that.
- FR-CFR-02: If `TRADERX_CONTROL_SECURITY` is missing, holds fewer messages than there are published rows, or its
  last message's `version` is below the highest published version, `reference-data` SHALL mark every outbox row
  unpublished; the poller SHALL then republish the whole universe in version order (FR-IMRG-OUTBOX-ORDER). A
  missing stream SHALL be recreated empty before the comparison.
- FR-CFR-03: A stream whose last version is above the highest published version SHALL be logged as
  `ALERT control stream last version ... is ahead of the outbox watermark ...` and SHALL NOT trigger a republish.
- FR-CFR-04: A stream that holds every published row SHALL be left alone: no flag changes and no publishes.
- FR-CFR-05: A failed comparison (broker or database unavailable) SHALL be logged and retried at the next
  interval; it SHALL NOT stop the poller or mark rows.
- NFR-CFR-01: A republish replays existence deltas only. The gateway applies each as a registration plus
  `SECURITY_CONTROL enabled=true`, as it does for every message when it replays the stream from its start on any
  restart, so a security halted or restricted through `/risk/control` is re-enabled by a republish exactly as by a
  gateway restart. Halts are not carried by this feed.

## Acceptance

`stocks-outbox-publisher.service.spec.ts`, against an in-memory outbox and stream:

| Case | FR | Expected |
| --- | --- | --- |
| publish 3, wipe the stream, restart | 01, 02 | stream holds 1, 2, 3 again; 3 rows published |
| publish 3, wipe, one newer row, restart | 02 | message count decides: 1..4 republished (last version alone equals the watermark) |
| complete stream, audit again | 04 | nothing republished |
| stream ahead of the outbox | 03 | ALERT, nothing republished |

Without the fix the first two cases fail and the last two pass.

Production on 2026-10-27 (before the fix shipped) is the case this closes: 533 rows published to a NATS store
roll 2 replaced, then one new security, so the gateway replica held 1 security.
