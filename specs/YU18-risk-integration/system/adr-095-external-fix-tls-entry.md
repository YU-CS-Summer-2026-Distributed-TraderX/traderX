# ADR-095: External FIX clients reach the gateways over TLS on a separate, allowlisted entry

Status: accepted 2026-10-29 (coordinator brief: an external FIX-over-TLS entry so yaakov can test from his Mac with
QuickFIX/J). Pack: [FIX TLS entry](../maintenance/fix-tls-entry/spec.md) (FR-FTL-01..06). Builds on ADR-082 (FIX logon
auth), ADR-086 (sessions on any replica) and ADR-092 (bounded FIX memory). Production plan of 2026-10-29, option B.

## Context

Production offers FIX only inside the cluster: the console's FIX ticket reaches `order-matcher-gw-fix:18130`. In-region
clients get the internal passthrough LB (option A). A client outside GCP has no FIX entry at all. Over the internet,
FIX needs two things it does not have today:
- Encryption. Logon carries `Password(554)` in clear, and every order and report after it.
- A source restriction. Cloud Armor attaches to a passthrough Network LB only with Cloud Armor Enterprise; the console's
  HTTPS LB has it, a TCP port would not.

QuickFIX/J binds every session to exactly one acceptor socket. Sessions on different `SocketAcceptPort`s get separate
MINA acceptors, each with its own `SocketUseSSL`. ADR-086 keeps a session's numbers and sent messages in JetStream with a
lease, so a session can resume on whichever gateway its TCP connection lands on.

## Decision

1. **TLS terminates in the gateway, on a second QuickFIX/J socket** (FR-FTL-01).
   - `FIX_TLS_PORT` (18143) carries the CompIDs in `FIX_TLS_COMPIDS` (`EXTQFJ`), over TLS 1.3/1.2 only, with the
     PKCS12 keystore at `FIX_SSL_KEYSTORE`.
   - The plaintext port (18130) keeps its CompIDs (`CLIENT1`, the console's ticket). Each CompID is on one socket: the
     external CompID is unknown on 18130, and `CLIENT1` is unknown on 18143.
   - `FIX_TLS_PORT` without a keystore, or naming a plaintext CompID, refuses to start: no plaintext fallback.
2. **The LB passes TCP through** (FR-FTL-04). `fix-tls-external` is a regional external passthrough Network LB of its
   own, `externalTrafficPolicy: Local`, `sessionAffinity: ClientIP`. It is separate from the internal LB that in-region
   clients use and from the console's HTTPS LB, so each can be brought up, changed or removed without the others.
3. **Only allowlisted sources reach the port** (FR-FTL-04). `loadBalancerSourceRanges` lists the operator's /32s; GKE
   turns it into the VPC firewall rule for the forwarding rule. The renderer accepts single public IPv4 addresses only,
   never a range and never `0.0.0.0/0`. The address is a parameter, not a value in the repository.
4. **TLS is not authentication** (FR-FTL-03). `EXTQFJ` logs on with its own `Password(554)` (Secret `fix-tls`,
   `external-password`) through ADR-082's `FIX_SESSION_AUTH`, and is bound to named accounts (`22214|42422`), not `*`.
5. **The certificate is the entry's own and the client pins it** (FR-FTL-02). `provision-fix-tls.py` makes an EC P-256
   certificate for `fix.yaakovseif.dev`, the gateway keystore, and a client trust store holding exactly that certificate.
   QuickFIX/J 2.3 checks no hostname, and with no trust store it trusts every certificate (MINA's
   `BogusTrustManagerFactory`), so the trust store is what authenticates the server.
6. **Mac client** (FR-FTL-05): `scripts/fix-tls/quickfixj-client.cfg` (QuickFIX/J initiator settings) and
   `fix-tls-smoke.sh`, which logs on, crosses one order and confirms it at the effect end.

## Why this termination

- **Stickiness and resume survive a gateway change.** One FIX session is one TCP connection, end to end from the client
  to the gateway that owns the session's lease. ClientIP affinity keeps a reconnect on the same gateway; when that
  gateway is gone, the reconnect lands on another, which takes the lease and resumes the numbers from JetStream
  (ADR-086). Nothing between the client and the gateway holds session state, so nothing else can lose it.
- **The gateway sees the client's address**: allowlist checks and gateway logs both use the real source.
- **No new component.** QuickFIX/J's own SSL, already wired (ENGINE-REVIEW S3) and already in the image.

## Rejected

- **TLS in front of the gateway** (a Google SSL/TCP proxy LB, or a stunnel/Envoy sidecar), FIX in plaintext behind it.
  - Google's proxy LBs terminate at the edge and open a new connection to the backend. The gateway then sees the
    proxy, not the client: no allowlist by source without Cloud Armor on the proxy, and logs name the proxy.
  - A proxy's certificate is Google-managed (the console's model), with no exportable key; a sidecar is a second
    process per gateway pod, with its own lifecycle, to hold one setting the gateway already has.
  - Stickiness would depend on the proxy's backend affinity as well as on the LB's; resume would still work (ADR-086),
    but every hop that can move a connection is one more way a session lands somewhere it waits for a lease.
- **A public CA certificate (Let's Encrypt through cert-manager) for the gateway keystore.**
  - The repository's issuer (`cluster-addons/letsencrypt-issuer.yaml`) solves HTTP-01 through an nginx ingress. The
    CompSci cluster has no nginx ingress; its certificates (demo, desk) are Google-managed on the HTTPS LB.
  - HTTP-01 needs Let's Encrypt to reach port 80 on the hostname from anywhere; this entry admits only the operator's
    /32. DNS-01 needs a cert-manager solver for Name.com's API, which the cluster does not have.
  - QuickFIX/J would still check no hostname, so the client would have to pin the leaf anyway, and re-pin it at every
    90-day renewal. A public CA adds nothing the pin does not already give.
  - The keystore is one Secret key; a Let's Encrypt keystore can replace it with no code change if a non-QuickFIX/J
    client that checks hostnames ever needs one.
- **TLS on the existing port for every CompID** (`FIX_SSL_KEYSTORE` alone, the ENGINE-REVIEW S3 switch). It would move
  the console's in-cluster FIX ticket and every in-region client to TLS at once; the external entry should not change
  them.
