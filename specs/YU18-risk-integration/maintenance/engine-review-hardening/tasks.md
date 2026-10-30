# Engine-review hardening tasks

- [x] S1 JWT-bound order routes; S2 operator pair; S3 FIX session credentials; S4 lookup-only order
      paths; S5 refuse dev secrets; S6 bounded bodies and batches.
- [x] Console order entry signs for a signed-in operator; trade-service mints a bearer for legacy `/trades`.
- [x] Control feed and operator security control register again after S4.
- [x] F1–F7, F9, F10 in the deterministic core.
- [x] F8 strict full-history replay; F11 bounded ClOrdID ledger with ambiguous entries.
- [x] FR-ERH-F12: `loadSnapshot` rethrows the first refused record instead of letting `Image.poll` log and skip
      it, and reassembles fragments (no format change). Test `SnapshotRealChannelRestoreTest` fails without the
      fix: a refused security is skipped and the load completes; a too-new header hangs the load.
      `ClusterRecon`'s replay guard (3f8db417) is pinned by `ClusterReconOtcRefusalTest`: with the guard off, its
      REGULATORY_MAX_RECORDS refusal is swallowed and the report comes back partial.
      On this branch it ships with FR-XOD-18 (ADR-090 §2a); production gets the reader alone from branch
      claude/snapshot-reader-failclosed-00b31d33.

- [x] FR-ERH-F13 (ADR-100, codeX review §13): the ref generator fails closed at 2^31-1 instead of casting through
      0 and 1 again; wrapped snapshot headers refused; `traderx_cluster_order_ref_headroom`; `/trades` tickets carry
      `bookingSource: TICKET` and no `sourceOrderId`. `OrderRefExhaustionTest` fails on the previous service (refs
      -2147483648, -2147483647, -2147483646 issued). Deterministic core: fresh epoch, same image on all members.

## Still open

- A live check of the legacy UI ticket on GKE (signed in, and with a wrong account).
