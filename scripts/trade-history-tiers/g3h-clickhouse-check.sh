#!/usr/bin/env bash
# FR-THT-06 / SC-THT-04 at the effect end: rig.sh's own history-store check (HISTORY_CHECK_SQL + history_verdict) on
# a real ClickHouse 25.8, with the history feed's column types. The sides are numbered 4,000,000,001..4,000,000,000+N
# over four partitions (1..4e9 recorded unprojected), so each partition's sum of squares wraps 2^64 as it does at
# production volume. PASS: the clean store is OK; one deleted row, one duplicated row, and one deleted plus one
# duplicated (the count unchanged) are each refused.
set -euo pipefail
N=${N:-200000}; LO=4000000000; T=$((LO + N)); RANGES="1-$LO"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
c=tht-g3h-$$; docker run -d --rm --name $c -e CLICKHOUSE_SKIP_USER_SETUP=1 clickhouse/clickhouse-server:25.8 >/dev/null
trap 'docker rm -f $c >/dev/null 2>&1 || true' EXIT
q(){ docker exec $c clickhouse-client -q "$1"; }
for i in $(seq 60); do q 'SELECT 1' >/dev/null 2>&1 && break; sleep 1; done
q "CREATE TABLE trades (ts DateTime64(3), seq UInt64, part UInt16, trade_seq UInt64) ENGINE=MergeTree PARTITION BY toDate(ts) ORDER BY (part, ts)"
q "INSERT INTO trades SELECT now64(3), number + 1, number % 4, $LO + number + 1 FROM numbers($N)"
ok=1
check(){ local want=$1 why=$2 v; v=$(history_verdict "$T" "$RANGES" "$(q "$HISTORY_CHECK_SQL")")
  case "$want:$v" in OK:OK*|REFUSED:[!O]*) echo "ok   $why: $v";; *) echo "FAIL $why: $v"; ok=0;; esac; }
check OK "clean store, $N sides past 4e9"
[ "$(q "SELECT sum(trade_seq * trade_seq) < max(trade_seq) * max(trade_seq) FROM trades WHERE part = 0")" = 1 ] \
  && echo "ok   partition 0's sum of squares wrapped 2^64" || { echo "FAIL no wrap exercised"; ok=0; }
q "ALTER TABLE trades DELETE WHERE trade_seq = $LO + 777 SETTINGS mutations_sync = 1"
check REFUSED "one row deleted"
q "INSERT INTO trades SELECT now64(3), 0, part, trade_seq FROM trades WHERE trade_seq = $LO + 778"   # restore the count
check REFUSED "one deleted and one duplicated (count unchanged)"
q "INSERT INTO trades SELECT now64(3), 999999999, 1, $LO + 777"; q "ALTER TABLE trades DELETE WHERE seq = 0 SETTINGS mutations_sync = 1"
check OK "repaired"
q "INSERT INTO trades SELECT now64(3), 0, part, trade_seq FROM trades WHERE trade_seq = $LO + 5"
check REFUSED "one row duplicated"
[ $ok = 1 ] && echo PASS || { echo FAIL; exit 1; }
