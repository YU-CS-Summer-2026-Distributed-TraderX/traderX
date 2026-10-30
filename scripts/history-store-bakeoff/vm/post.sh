#!/bin/bash
# post.sh SINK: bench.sh's check, queries and export alone, against a store already drained (a fresh query process
# for kdb), with the fill's acked totals as the reference (the stream may have aged or been refilled since).
SINK=$1; CP=/opt/hsb/build/classes:/opt/hsb/build/lib/jnats-2.20.5.jar; OUT=/data/results/$SINK; R=$OUT/post; mkdir -p $R
S="ssh -o StrictHostKeyChecking=no -i /opt/hsb/key bench@hsb-store sudo"
case $SINK in
  clickhouse) QPORT=8123;; questdb) QPORT=9000;;
  kdb) QPORT=5011; $S docker rm -f hdb >/dev/null; $S /opt/hsb/vm/store.sh kdb hdb >/dev/null; sleep 10;;
esac
J="java -DexpectTrades=240000000 -DexpectOrders=360000000 -cp $CP HsbQuery sink=$SINK store=hsb-store:$QPORT"
$J mode=check url=nats://127.0.0.1:4222 > $R/check.log 2>&1
$S /opt/hsb/vm/store.sh $SINK dropcache; $J mode=query account=4242 reps=5 > $R/query.log 2>&1
$S /opt/hsb/vm/store.sh $SINK dropcache; $J mode=export > $R/export.log 2>&1
grep -E "CHECK|QUERY|EXPORT|Exception" $R/*.log
