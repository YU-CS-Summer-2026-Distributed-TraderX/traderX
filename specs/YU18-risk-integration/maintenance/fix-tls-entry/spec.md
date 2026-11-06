# FIX TLS entry specification

Status: new 2026-10-29. Scope: how a FIX client outside GCP reaches the production gateways. Decision:
[ADR-095](../../system/adr-095-external-fix-tls-entry.md).

## Requirements

- FR-FTL-01: With `FIX_TLS_PORT` set, the gateway SHALL serve the CompIDs in `FIX_TLS_COMPIDS` on that port over TLS
  (1.3 or 1.2) and on no other port. The CompIDs in `FIX_SESSION_COMPIDS` SHALL stay on `FIX_ACCEPTOR_PORT`, in
  plaintext, and SHALL NOT log on through the TLS port. A plaintext connection to the TLS port SHALL get no FIX answer.
  The gateway SHALL refuse to start with `FIX_TLS_PORT` but no `FIX_SSL_KEYSTORE`, or with a TLS CompID that is blank or
  also on the plaintext port.
- FR-FTL-02: The server certificate SHALL name the entry's hostname (`fix.yaakovseif.dev`). The client SHALL trust
  exactly that certificate (a pinned trust store), because QuickFIX/J 2.3 checks no hostname and without a trust store
  accepts any certificate. Keystore and passwords SHALL live in Secret `fix-tls` and outside the checkout, never in
  the repository or on a command line that reaches the cluster.
- FR-FTL-03: A TLS session SHALL log on with its CompID's `Password(554)` (ADR-082), from Secret `fix-tls`, and act only
  on its named accounts. TLS SHALL NOT replace the password: a wrong one is refused over TLS as over plaintext.
- FR-FTL-04: The external entry SHALL be its own L4 Service (`fix-tls-external`, passthrough, `externalTrafficPolicy:
  Local`, ClientIP affinity), independent of the internal LB and the console's HTTPS LB. It SHALL admit only the
  listed single /32 source addresses; the renderer SHALL refuse a range, a private address, or no address. A
  connection from any other source SHALL NOT reach a gateway.
- FR-FTL-05: The Mac client SHALL be a QuickFIX/J settings file with SSL, and a smoke that logs on through the entry,
  sends a NewOrderSingle that crosses a resting order of the session, and confirms it at the effect end: the Filled
  ExecutionReport, the resting order Filled through OrderStatusRequest (answered from the order read model), and, when
  it can reach the cluster, the database's `orderbook` rows.
- FR-FTL-06: A session on the TLS port SHALL keep ADR-086's behaviour: one gateway per live session (lease), resume on
  any replica, ADR-092's bounds.
- FR-FTL-07 (2026-11-01, supersedes FR-FTL-03's named accounts): `EXTQFJ` SHALL be bound to every account (`*`), as
  CLIENT1 is, so an account created on the Desk minutes earlier is tradable from the Mac. Its password, the /32
  allowlist and the pinned certificate still gate the session; the render SHALL refuse it with no account binding.
- FR-FTL-08: `scripts/fix-tls/fix-order.sh ACCOUNT Buy|Sell QTY TICKER [PX]` SHALL send one order through the entry
  (Limit DAY at PX, else Market IOC) and print its ExecutionReports until it is filled, cancelled or rejected, or rests
  (New, then 5 s quiet); exit 0 unless rejected or unanswered, 64 on bad arguments. Credentials and the trust store
  come from `PRIVATE_DIR`, never argv.
- FR-FTL-09: `node scripts/fix-tls/fix-web.mjs` SHALL serve, on 127.0.0.1 only (default :7070), a page that sends each
  order through `fix-order.sh` to the entry (default 35.196.31.22:18143) and shows its ExecutionReports. It SHALL accept
  only a numeric account, Buy|Sell, a positive whole quantity, a ticker and an optional decimal price, refuse a
  cross-origin POST, and run one order at a time. Every order is a Limit DAY: a blank price SHALL be the engine's mark
  for that ticker (`MARK_URL`, default the demo front door's `/mem/0/bbo`), as the Desk's "Price at market" does, so it
  rests on an empty book instead of cancelling as a Market IOC; a ticker with no mark SHALL be refused, not sent.

- FR-FTL-10 (2026-11-06): The TLS port SHALL offer TLS 1.2 only. Under TLS 1.3, MINA 2.1.4 (QuickFIX/J 2.3.1) left
  a Logon that arrived with the end of the handshake unread until the client closed, so a client that logs on at once
  (QuickFIX/J does) waited for its logon timeout. `FixGatewayTlsTest` fails on TLS 1.3 and passes on TLS 1.2.

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| EXTQFJ over TLS, right password | 01, 03 | `FixGatewayTlsTest.theExternalCompId…` | Logon(A) |
| EXTQFJ on the plaintext port | 01 | same | no Logon(A) |
| EXTQFJ over TLS, wrong password | 03 | same | no Logon(A) |
| CLIENT1 over TLS on the TLS port | 01 | same | no Logon(A) |
| CLIENT1 on the plaintext port (control) | 01 | same | Logon(A) |
| plaintext FIX Logon to the TLS port | 01 | `FixGatewayTlsTest.plaintextToTheTlsPort…` | no FIX bytes back |
| no keystore; TLS CompID on plaintext; blank CompID | 01 | `FixGatewayTlsTest.aTlsPortWithoutAKeystore…` | refuses to start |
| TLS settings left plaintext (mutation) | 01 | `FixGatewayTlsTest` with `SocketUseSSL` removed | fails |
| production render | 01–04 | `test_prepare.py` `test_external_fix_compid_is_tls_only_from_the_secret` | TLS env, Secret refs, keystore volume; 4 mutations refused |
| kind: TLS logon through `fix-tls-external`, order booked | 01, 03, 05 | `yu18-fix-tls-entry.sh` | smoke OK; members' trades +1; `orderbook` rows FILLED |
| kind: plaintext to the entry | 01 | same | no FIX answer |
| kind: source outside the allowlist | 04 | same | connection does not reach the gateway |
