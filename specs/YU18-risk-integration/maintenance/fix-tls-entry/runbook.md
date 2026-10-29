# FIX TLS entry runbook (production `traderx` on traderx-demo)

Decision: [ADR-095](../../system/adr-095-external-fix-tls-entry.md). Every step below writes to production or to DNS, so
each one is the rollout session's, with yaakov's approval. Run on production 2026-10-29: every step but DNS
([tasks](tasks.md)).

Names: Service `fix-tls-external`, Secret `fix-tls`, address `traderx-fix-tls` (us-east1), host `fix.yaakovseif.dev`,
port 18143, CompID `EXTQFJ` on accounts 22214 and 42422.

## 0. Before

- The gateway from a `prepare.py` render at or after the ADR-095 commit has the TLS socket. It mounts Secret `fix-tls`
  and does not start without it, so **step 1 comes before the gateway roll that carries ADR-095**. Its plan.json lists
  `fix-tls: external-password, keystore-password` under `requiredSecretKeys`.
- Until step 5 nothing outside the cluster reaches port 18143.

## 1. Certificate, keystore, pinned trust store, Secret

Use the private directory `provision-secrets.py` uses (outside the checkout, mode 0700):

```bash
python3 scripts/gcp-compsci/provision-fix-tls.py --private-dir ~/traderx-private --apply
```

It writes `~/traderx-private/fix-tls/` (keystore, `truststore.p12`, `server.crt`, `client.json` with the passwords) once
and creates Secret `fix-tls` in `traderx` through stdin. Re-running checks the Secret matches and changes nothing.
The certificate is EC P-256 for `fix.yaakovseif.dev`, valid 397 days.

## 2. Address

```bash
gcloud compute addresses create traderx-fix-tls --region us-east1 --project project-compsci-484414
gcloud compute addresses describe traderx-fix-tls --region us-east1 --project project-compsci-484414 --format='value(address)'
```

## 3. DNS (yaakov, at Name.com)

An `A` record `fix` → the address from step 2, TTL 300. Not done by any script.

The earlier static IP `traderx-ip` (34.24.82.47) was released on 2026-08-01, so no old record can be reused. The bare
`yaakovseif.dev` still has no certificate of its own (the HTTPS LB presents demo's); that is a separate gap and does not
affect this entry, whose certificate is its own.

## 4. Gateway roll

Roll the candidate that carries ADR-095 the rollout's usual way (one gateway at a time). On each gateway:

```bash
kubectl --context gke_project-compsci-484414_us-east1-b_traderx-demo -n traderx logs deploy/cluster-gateway | grep 'FIX TLS acceptor on :18143'
```

## 5. External Service, allowlisted to your address

Your public IPv4 (one /32; repeat `--source` for a second one, never a range):

```bash
curl -s https://checkip.amazonaws.com
```

```bash
python3 scripts/gcp-compsci/render-fix-tls-access.py --address <step 2 address> --source <your IPv4> | kubectl --context gke_project-compsci-484414_us-east1-b_traderx-demo apply -f -
```

Check the Service took the address, and the firewall rule GKE made admits only your /32 on tcp:18143:

```bash
kubectl --context gke_project-compsci-484414_us-east1-b_traderx-demo -n traderx get svc fix-tls-external
```

```bash
gcloud compute firewall-rules list --project project-compsci-484414 --filter='name~^k8s-fw' --format='table(name,sourceRanges.list(),allowed[].map().firewall_rule().list())' | grep -e NAME -e 18143
```

When your address changes, re-run this step with the new one. Cost: one regional forwarding rule and one static address,
about $0.03/h.

## 6. Smoke from the Mac

`TICKER`/`PX`: a ticker the venue quotes, at a price that crosses (the seed job enables 22214 and 42422 on the
reference tickers at 150). It books one real trade between the two demo accounts (+1/−1), so it is part of the
approval. With kube access it also reads the two `orderbook` rows.

```bash
PRIVATE_DIR=~/traderx-private FIX_HOST=fix.yaakovseif.dev TICKER=AAPL PX=150 CTX=gke_project-compsci-484414_us-east1-b_traderx-demo NS=traderx bash scripts/fix-tls/fix-tls-smoke.sh
```

Pass: `FIX-TLS-SMOKE PASS fix.yaakovseif.dev:18143 as EXTQFJ`.

For your own QuickFIX/J client, start from `scripts/fix-tls/quickfixj-client.cfg` and set `fix.host`, `fix.port`,
`fix.sender` (`EXTQFJ`), `fix.truststore` (`~/traderx-private/fix-tls/truststore.p12`), `fix.truststore.password` and
`fix.password` (both in `client.json`) as system properties. Keep `SocketTrustStore`: without it QuickFIX/J accepts
any certificate.

## 7. Negative checks

Plaintext to the entry gets a TLS alert (`15 03 03 …`), never `8=FIX`:

```bash
printf '8=FIX.4.4\x019=5\x0135=A\x0110=000\x01' | nc -w 5 fix.yaakovseif.dev 18143 | od -An -tx1 | head -1
```

From a network that is not on the allowlist (a phone hotspot, Cloud Shell), the connect times out:

```bash
nc -vz -w 5 fix.yaakovseif.dev 18143
```

## Removing or rotating

- Close the entry: `kubectl -n traderx delete svc fix-tls-external`, then
  `gcloud compute addresses delete traderx-fix-tls --region us-east1`, and remove the DNS record. The gateways keep the
  socket, reachable from nothing outside the cluster.
- New certificate (expiry, or a lost Mac): move `~/traderx-private/fix-tls` aside, delete Secret `fix-tls`, re-run
  step 1, roll the gateways. The old trust store stops working, which is the point. `provision-fix-tls.py` never
  rotates by itself.
