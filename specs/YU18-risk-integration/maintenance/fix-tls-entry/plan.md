# FIX TLS entry plan

The gateway already had QuickFIX/J's SSL switch, but for its one acceptor port: turning it on would have moved the
console's FIX ticket and every in-region client to TLS. QuickFIX/J gives each session its own acceptor socket, so the
external CompID gets a second socket, TLS-only, and the plaintext port is unchanged. A passthrough L4 Service with a
/32 allowlist exposes only that socket; the client pins the entry's certificate because QuickFIX/J does not check
hostnames. Logon still needs the CompID's password. See ADR-095.
