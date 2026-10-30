#!/bin/bash
# store.sh SINK start|stop|hdb|dropcache|du: one store on the store VM, data under /data/SINK, host network.
set -uo pipefail
SINK=$1; OP=$2
case "$SINK:$OP" in
  clickhouse:start) mkdir -p /data/clickhouse && docker run -d --name store --network host --ulimit nofile=262144:262144 \
      -e CLICKHOUSE_SKIP_USER_SETUP=1 -v /data/clickhouse:/var/lib/clickhouse clickhouse/clickhouse-server:25.8 ;;
  questdb:start) mkdir -p /data/questdb && docker run -d --name store --network host -e QDB_CAIRO_COMMIT_MODE=sync \
      -v /data/questdb:/var/lib/questdb questdb/questdb:9.1.0 ;;
  # The feed's write-down process (RDB, port 5010). KDB-X licence: /opt/kx/kc.lic; total q RAM <= 16 GiB.
  kdb:start) mkdir -p /data/kdb && docker run -d --name store --network host -e QHOME=/opt/kx -e QLIC=/opt/kx \
      -e HSB_DB=/data/kdb/db -v /opt/kx:/opt/kx:ro -v /opt/hsb/kdb:/opt/hsb:ro -v /data/kdb:/data/kdb -w /data/kdb \
      debian:12-slim /opt/kx/bin/q /opt/hsb/hsb.q -p 5010 -s 4 ;;
  # Query process (HDB, port 5011) over the written-down partitions, started after the drain.
  kdb:hdb) docker run -d --name hdb --network host -e QHOME=/opt/kx -e QLIC=/opt/kx -v /opt/kx:/opt/kx:ro \
      -v /data/kdb:/data/kdb -w /data/kdb debian:12-slim /opt/kx/bin/q /data/kdb/db -p 5011 -s 4 ;;
  *:stop) docker rm -f store hdb >/dev/null 2>&1; rm -rf /data/"$SINK" ;;
  *:dropcache) sync; echo 3 > /proc/sys/vm/drop_caches ;;
  *:du) du -sb /data/"$SINK" | cut -f1 ;;
  *) echo "usage: store.sh clickhouse|questdb|kdb start|stop|hdb|dropcache|du"; exit 2 ;;
esac
