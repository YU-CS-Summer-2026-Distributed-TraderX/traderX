#!/usr/bin/env bash
# FR-THT-02/03/06 at the effect end on kind: the history store as prepare.py renders it (kind-yu18-rig.py --history),
# checked by rig.sh's own G3h (g3_history, sourced, not restated) against the members' trade count.
#
#   G3h      PASS on the store as fed; REFUSED with one trade side deleted; REFUSED with one duplicated; PASS restored.
#   tier     with the tiered render (--history-hot-days N): history-feed put `trades` on the `tiered` policy, and a probe
#            table on that policy moves its old date partition to the S3 cold disk with the same rows before and after.
#            Without it (the plain --history render) the policy does not exist: the negative control.
#
# The caller crosses trades first (the members' count must be > 0). Kind only: it deletes and re-inserts history rows.
#   CTX=kind-<c> NS=traderx bash scripts/trade-history-tiers/kind-history-store.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
[[ $CTX == kind-* ]] || { echo "kind only: this deletes and re-inserts history rows" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-history-store-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }
gate(){ (g3_history "$1") 2>&1; }   # a subshell: its die is a verdict here, not this script's exit

t=$(member 0 | cut -d' ' -f3); [[ $t =~ ^[0-9]+$ && $t -gt 0 ]] || fail "the members report no trades ($t): cross some first"
echo "members' trades: $t"

out=$(gate "$t") || fail "G3h on the store as fed: $out"
echo "$out" | grep -F 'G3h ok' || fail "G3h printed no verdict: $out"

row=$(q "SELECT * FROM trades WHERE trade_seq = $t FORMAT TSV")
[[ $(wc -l <<<"$row") -eq 1 && -n $row ]] || fail "trade side $t is not exactly one row in the store"
put(){ kc exec -i sts/clickhouse -c clickhouse -- clickhouse-client -q "INSERT INTO trades FORMAT TSV" <<<"$row"; }
drop(){ q "ALTER TABLE trades DELETE WHERE trade_seq = $t SETTINGS mutations_sync = 1"; }
restored=0; trap '[ $restored = 1 ] || { drop; put; echo "restored trade side $t"; }' EXIT

drop
out=$(gate "$t") && fail "G3h passed with trade side $t deleted: $out"
grep -F "holds $((t - 1)) sides" <<<"$out" >/dev/null || fail "deleted side refused for the wrong reason: $out"
echo "negative (side $t deleted): $(grep REFUSED <<<"$out")"
put; put
out=$(gate "$t") && fail "G3h passed with trade side $t twice: $out"
grep -F '1 duplicated' <<<"$out" >/dev/null || fail "duplicated side refused for the wrong reason: $out"
echo "negative (side $t twice): $(grep REFUSED <<<"$out")"
drop; put; restored=1
out=$(gate "$t") || fail "G3h after the restore: $out"
echo "restored: $(grep -F 'G3h ok' <<<"$out")"

policy=$(q "SELECT storage_policy FROM system.tables WHERE database = currentDatabase() AND name = 'trades'")
if [[ $(q "SELECT count() FROM system.storage_policies WHERE policy_name = 'tiered'") == 0 ]]; then
  err=$(q "CREATE TABLE tht_tier_probe (ts DateTime64(3), v UInt64) ENGINE = MergeTree ORDER BY ts SETTINGS storage_policy = 'tiered'" 2>&1) \
    && { q "DROP TABLE tht_tier_probe SYNC"; fail "an untiered render accepted the tiered policy"; }
  echo "tier negative (untiered render): trades on '$policy'; no 'tiered' policy: $(grep -o 'Unknown storage policy[^.]*' <<<"$err" | head -1)"
  echo; echo "KIND-HISTORY-STORE PASS (G3h + controls; untiered render)"; exit 0
fi
[[ $policy == tiered ]] || fail "the tiered render left trades on '$policy': history-feed did not apply HISTORY_HOT_DAYS"
q "DROP TABLE IF EXISTS tht_tier_probe SYNC"
q "CREATE TABLE tht_tier_probe (ts DateTime64(3), v UInt64) ENGINE = MergeTree PARTITION BY toDate(ts) ORDER BY ts
   TTL toDate(ts) + toIntervalDay(1) TO VOLUME 'cold' SETTINGS storage_policy = 'tiered'"
trap 'q "DROP TABLE IF EXISTS tht_tier_probe SYNC" || true' EXIT
q "INSERT INTO tht_tier_probe SELECT now64(3) - toIntervalDay(5 * (number % 2)), number FROM numbers(2000)"
probe="SELECT toDate(ts) d, count(), sum(v) FROM tht_tier_probe GROUP BY d ORDER BY d FORMAT TSV"
before=$(q "$probe")
s3files(){ kc exec deploy/s3mock -- sh -c 'find /data -type f | wc -l' | tr -d ' '; }
objects0=$(s3files)
q "ALTER TABLE tht_tier_probe MATERIALIZE TTL SETTINGS mutations_sync = 2"
disks="SELECT partition, groupUniqArray(disk_name) FROM system.parts WHERE table = 'tht_tier_probe' AND active GROUP BY partition ORDER BY partition FORMAT TSV"
for _ in $(seq 60); do
  where=$(q "$disks"); [[ $(head -1 <<<"$where") == *cold_object* && $(tail -1 <<<"$where") != *cold_object* ]] && break; sleep 2
done
[[ $(head -1 <<<"$where") == *"['cold_object']"* && $(tail -1 <<<"$where") == *"['default']"* ]] \
  || fail "the old partition did not move to the cold disk alone: $where"
after=$(q "$probe")
[[ $after == "$before" ]] || fail "the moved partition answers differently: before $before, after $after"
objects=$(( $(s3files) - objects0 ))
[[ $objects -gt 0 ]] || fail "nothing reached the S3 cold store"
echo "tier: trades on '$policy'; probe partitions $(tr '\n\t' '; ' <<<"$where"); rows equal before and after ($(tr '\n\t' '; ' <<<"$after")); $objects objects in S3"
echo; echo "KIND-HISTORY-STORE PASS (G3h + controls; tiered render, FR-THT-03 move)"
