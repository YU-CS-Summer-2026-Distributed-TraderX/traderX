# Demo KDB-X history (FR-DA10, FR-DA12)

`demo-hdb.q` serves one account's 5M-trade history as `POST /q` for the console's Kdb tab.

Production runs it on the `traderx-kdb` VM (us-east1-b, e2-standard-2, `traderx-demo-vpc`, no service account;
firewall `traderx-kdb-iap-ssh` and `traderx-kdb-q` = 5050 from the pod range). `rig.sh up`/`down` start and stop it.

1. yaakov installs KDB-X on the VM himself (the licence never leaves it):
   `gcloud compute ssh yaakov@traderx-kdb --zone us-east1-b --project project-compsci-484414 --tunnel-through-iap`
2. `scripts/demo-kdb/install-service.sh` copies `demo-hdb.q` and starts `traderx-kdb.service` (first start builds the HDB).
