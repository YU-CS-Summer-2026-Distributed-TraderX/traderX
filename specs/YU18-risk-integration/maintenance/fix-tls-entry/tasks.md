# FIX TLS entry tasks

- [x] Second TLS-only acceptor socket for `FIX_TLS_COMPIDS` (FR-FTL-01); `FixGatewayTlsTest` 3/3, and fails with the
      TLS settings removed.
- [x] Production render: TLS env, Secret `fix-tls`, keystore volume, port 18143, validation (FR-FTL-01..03);
      `test_prepare.py` 36/36.
- [x] `provision-fix-tls.py` (certificate, keystore, pinned trust store, Secret) and `render-fix-tls-access.py`
      (`fix-tls-external`, /32 only) (FR-FTL-02, 04).
- [x] Mac client settings and smoke (FR-FTL-05).
- [x] kind proof: TLS logon and an order booked end to end through the external Service, plaintext refused, a source
      outside the allowlist refused (`yu18-fix-tls-entry.sh`).
- [ ] Production: reserve the address, DNS `fix.yaakovseif.dev`, Secret, rolled gateway, Service, smoke from the Mac
      (runbook; the rollout session, with yaakov's approval).
