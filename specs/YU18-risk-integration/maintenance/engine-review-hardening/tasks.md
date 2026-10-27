# Engine-review hardening tasks

- [x] S1 JWT-bound order routes; S2 operator pair; S3 FIX session credentials; S4 lookup-only order
      paths; S5 refuse dev secrets; S6 bounded bodies and batches.
- [x] Console order entry signs for a signed-in operator; trade-service mints a bearer for legacy `/trades`.
- [x] Control feed and operator security control register again after S4.
- [x] F1–F7, F9, F10 in the deterministic core.
- [x] F8 strict full-history replay; F11 bounded ClOrdID ledger with ambiguous entries.

## Still open

- A live check of the legacy UI ticket on GKE (signed in, and with a wrong account).
