#!/bin/bash
# bench.sh SINK [KILL_AT]: one bake-off run, from the feed VM, against the stream HsbLoad total=... filled ONCE
# (identical input for every store). Same steps for every store:
#  store up -> feeder drains the whole backlog flat out (sustained ingest = the knee; the stream holds a full-rate
#  10-minute volume) -> kill -9 the feeder at KILL_AT s, restart it 30 s later -> drain -> exactly-once check against
#  the stream -> queries (cold after a page-cache drop, then warm) -> EOD export.
set -uo pipefail
SINK=$1; KILL_AT=${2:-240}
STORE_HOST=${STORE_HOST:-hsb-store}
CP=/opt/hsb/build/classes:/opt/hsb/build/lib/jnats-2.20.5.jar
OUT=/data/results/$SINK; mkdir -p $OUT
S="ssh -o StrictHostKeyChecking=no -i /opt/hsb/key bench@$STORE_HOST sudo"
case $SINK in clickhouse) PORT=8123; QPORT=8123;; questdb) PORT=9000; QPORT=9000;; kdb) PORT=5010; QPORT=5011;; esac
log() { echo "$(date +%T) $*" | tee -a $OUT/bench.log; }

log "== $SINK: drain, kill -9 at ${KILL_AT}s"
FEED="java -Xmx8g -cp $CP HistoryFeed sink=$SINK name=${NAME:-$SINK} store=$STORE_HOST:$PORT streams=4"
if [ -z "${RESUME:-}" ]; then   # RESUME=1: keep the store and feed1's rows, run only the restart onward
  $S /opt/hsb/vm/store.sh $SINK stop; $S sh -c "'echo $SINK > /opt/hsb/current-sink'"
  $S /opt/hsb/vm/store.sh $SINK start; sleep 20
  $S pkill -f /opt/hsb/vm/sample.sh   # its own pkill: a pkill inside the starting sh -c would match that sh -c
  $S sh -c "'nohup /opt/hsb/vm/sample.sh /data/samples-$SINK.csv >/dev/null 2>&1 &'"
  log "feeder start"
  $FEED > $OUT/feed1.log 2>&1 & F=$!
  sleep $KILL_AT; kill -9 $F; log "feeder kill -9"
  sleep 30
fi
log "feeder restart"
$FEED idleExit=30 > $OUT/feed2.log 2>&1
log "drained: $(tail -1 $OUT/feed2.log)"
[ $SINK = kdb ] && { $S /opt/hsb/vm/store.sh kdb hdb; sleep 10; }
java -cp $CP HsbQuery sink=$SINK store=$STORE_HOST:$QPORT mode=check url=nats://127.0.0.1:4222 > $OUT/check.log 2>&1; cat $OUT/check.log | tee -a $OUT/bench.log
log "disk bytes: $($S /opt/hsb/vm/store.sh $SINK du)"
$S /opt/hsb/vm/store.sh $SINK dropcache
java -cp $CP HsbQuery sink=$SINK store=$STORE_HOST:$QPORT mode=query account=4242 reps=5 > $OUT/query.log 2>&1; cat $OUT/query.log | tee -a $OUT/bench.log
$S /opt/hsb/vm/store.sh $SINK dropcache
java -cp $CP HsbQuery sink=$SINK store=$STORE_HOST:$QPORT mode=export > $OUT/export.log 2>&1; cat $OUT/export.log | tee -a $OUT/bench.log
$S pkill -f /opt/hsb/vm/sample.sh; $S cat /data/samples-$SINK.csv > $OUT/samples.csv
log "== $SINK done"
