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
- [x] Production, 2026-10-29 (yaakov approved in the rollout session):
      - Secret `fix-tls` (step 1), 1:05 pm ET;
      - gateways rolled with the TLS socket in rollout 5a; each logs `FIX TLS acceptor on :18143` (step 4);
      - address `traderx-fix-tls` = 35.196.31.22 (step 2) and `fix-tls-external` allowlisted to 173.77.115.104/32,
        the only source in its firewall rule (step 5), 2:24 pm ET;
      - smoke from the Mac by address, 2:25 pm ET: TLS logon as EXTQFJ, Sell `ord-174330` (42422) rested, Buy (22214)
        filled at 230.66, OrderStatusRequest Filled from the read model, both rows FILLED (step 6);
      - plaintext got a TLS alert `15 03 03 00 02 02 50`; a connect from 34.139.122.85 (not allowlisted) timed out
        (step 7).
- [ ] DNS: `fix` A record → 35.196.31.22 at Name.com (yaakov, step 3), then the smoke by hostname.
