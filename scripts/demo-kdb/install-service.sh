#!/usr/bin/env bash
# FR-DA12: run demo-hdb.q as a systemd service on the traderx-kdb VM, from the KDB-X install yaakov made there
# (~yaakov/.kx). This script never reads, prints or copies the licence: q finds it in QHOME itself.
#   scripts/demo-kdb/install-service.sh            # copy demo-hdb.q, (re)install and start traderx-kdb.service
set -euo pipefail
VM=${KDB_VM:-traderx-kdb}; ZONE=${ZONE:-us-east1-b}; PROJECT=${PROJECT:-project-compsci-484414}
ssh_(){ gcloud compute ssh "yaakov@$VM" --zone "$ZONE" --project "$PROJECT" --tunnel-through-iap --command "$1"; }
gcloud compute scp "$(dirname "$0")/demo-hdb.q" "yaakov@$VM:/tmp/demo-hdb.q" --zone "$ZONE" --project "$PROJECT" --tunnel-through-iap
ssh_ 'set -e
test -x "$HOME/.kx/bin/q" || { echo "no KDB-X at ~/.kx/bin/q: run the installer on this VM first" >&2; exit 2; }
sudo install -D -m 644 /tmp/demo-hdb.q /opt/traderx-kdb/demo-hdb.q
sudo install -d -o yaakov /var/lib/traderx-kdb
sudo tee /etc/systemd/system/traderx-kdb.service >/dev/null <<UNIT
[Unit]
Description=TraderX demo HDB, q over HTTP on 5050 (FR-DA12)
After=network-online.target
[Service]
User=yaakov
Environment=QHOME=/home/yaakov/.kx
Environment=QLIC=/home/yaakov/.kx
WorkingDirectory=/var/lib/traderx-kdb
ExecStart=/home/yaakov/.kx/bin/q /opt/traderx-kdb/demo-hdb.q -p 5050 -db /var/lib/traderx-kdb/hdb
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload && sudo systemctl enable --now traderx-kdb && sudo systemctl restart traderx-kdb
for i in $(seq 1 120); do curl -sf -m 5 -X POST -d "{\"q\":\"count trades\"}" http://127.0.0.1:5050/q && exit 0; sleep 5; done
echo "q did not answer on 5050 within 10 min" >&2; sudo journalctl -u traderx-kdb -n 20 --no-pager >&2; exit 1'
