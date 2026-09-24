#!/usr/bin/env bash
# Negative controls for scripts/ri07/startup-probe.sh against a REAL MariaDB with the shipped YU18
# DDL (review R3). Needs Docker and the mariadb:11.4 image; starts ONE container named
# traderx-ri07-sqltest and removes it on exit. No kind rig.
#   bash scripts/ri07/test-startup-probe-sql.sh
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
C=traderx-ri07-sqltest
DDL_SRC="${ROOT}/specs/YU18-risk-integration/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml"
fails=0
check() { if [[ "$2" == "$3" ]]; then echo "[ok]   $1"; else echo "[FAIL] $1: got '$2', want '$3'"; fails=$((fails+1)); fi; }

docker inspect "${C}" >/dev/null 2>&1 && { echo "[refused] a container named ${C} exists; not touching it"; exit 2; }
trap 'docker rm -f "${C}" >/dev/null 2>&1' EXIT
docker run -d --name "${C}" -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=traderx mariadb:11.4 >/dev/null
for _ in $(seq 1 60); do docker exec "${C}" mariadb -uroot -proot -e 'SELECT 1' traderx >/dev/null 2>&1 && break; sleep 1; done
db() { docker exec -i "${C}" mariadb -uroot -proot traderx -N -B -e "$1"; }

# The shipped first-boot DDL: accounts .. orderbook, as the configmap carries it (dedented).
sed -n '/^    CREATE TABLE accounts (/,/^    CREATE INDEX idx_orderbook_updatedat/p' "${DDL_SRC}" | sed 's/^    //' \
  | docker exec -i "${C}" mariadb -uroot -proot traderx || { echo "[FAIL] DDL did not apply"; exit 1; }
cap="$(db 'SELECT @@group_concat_max_len;')"
db "INSERT INTO accounts VALUES (22214,'Test Account 20'),(880001,'RI-07 startup probe A (automated, not for trading)');
    INSERT INTO orderbook (orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat)
      SELECT CONCAT('1-',seq),22214,'IBM','Buy',10,10,100,'NEW','2026-09-24 00:00:00','2026-09-24 00:00:00' FROM seq_1_to_40000;
    INSERT INTO orderbook (orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat)
      VALUES ('9-1',880001,'PRB1','Sell',1,1,100,'NEW',NOW(),NOW());
    INSERT INTO trades (id,accountid,created,updated,security,side,quantity,price,state)
      SELECT CONCAT(seq,'-B'),22214,NOW(),NOW(),'IBM','Buy',1,100,'Settled' FROM seq_1_to_20000;"
bytes="$(db "SELECT SUM(LENGTH(CONCAT_WS('|',orderid,accountid,security,status,remainingquantity,updatedat))+1) FROM orderbook;")"
echo "[info] group_concat_max_len=${cap}; old-digest input would be ${bytes} bytes"
[[ "${bytes}" -gt "${cap}" ]] || { echo "[FAIL] dataset does not exceed the cap; test would be vacuous"; exit 1; }

PROBE_A=880001 PROBE_B=880002
source "${ROOT}/scripts/ri07/startup-probe.sh"   # functions only; main does not run when sourced
sql() { db "$1"; }                               # point the probe's one query function at this DB
OLD_Q="SELECT MD5(COALESCE(GROUP_CONCAT(CONCAT_WS('|',orderid,accountid,security,status,remainingquantity,updatedat) ORDER BY orderid SEPARATOR ';'),'')) FROM orderbook WHERE accountid NOT IN (880001,880002);"

old1="$(db "${OLD_Q}" 2>/dev/null)"; new1="$(foreign_digest)"; rc=$?
check "foreign_digest succeeds on a healthy DB" "${rc}" 0
check "foreign_digest is stable with no change" "$(foreign_digest)" "${new1}"
# Change the LAST foreign row in orderid order: beyond the retained GROUP_CONCAT prefix.
db "UPDATE orderbook SET status='CANCELED', remainingquantity=0 WHERE orderid='1-9999';"
old2="$(db "${OLD_Q}" 2>/dev/null)"; new2="$(foreign_digest)"
check "OLD GROUP_CONCAT digest is BLIND to a change beyond the cap (the reviewed defect)" "$([[ "${old1}" == "${old2}" ]] && echo blind || echo sees)" blind
check "new digest sees that change" "$([[ "${new1}" != "${new2}" ]] && echo sees || echo blind)" sees
db "UPDATE trades SET price=101 WHERE id='19999-B';"
check "new digest sees a trade change" "$([[ "$(foreign_digest)" != "${new2}" ]] && echo sees || echo blind)" sees
new3="$(foreign_digest)"
db "UPDATE orderbook SET status='FILLED', remainingquantity=0 WHERE orderid='9-1';"
check "a probe-owned row change does not move the foreign digest" "$(foreign_digest)" "${new3}"
check "digest reports complete row counts" "${new3#* }" "40000+20000 rows"

sql() { if [[ "$1" == *"FROM trades"* ]]; then return 1; else db "$1"; fi; }
out="$(foreign_digest)"; rc=$?
check "second (trades) query failing -> foreign_digest fails, prints nothing" "${rc}:${out}" "1:"
sql() { if [[ "$1" == *"FROM orderbook"* ]]; then return 1; else db "$1"; fi; }
out="$(foreign_digest)"; rc=$?
check "first (orderbook) query failing -> foreign_digest fails, prints nothing" "${rc}:${out}" "1:"
sql() { docker exec -i "${C}" mariadb -uroot -proot no_such_db -N -B -e "$1" 2>/dev/null; }
out="$(foreign_digest)"; rc=$?
check "a real SQL error (unknown database) -> foreign_digest fails" "${rc}:${out}" "1:"

sql() { db "$1"; }
check "absent probe account" "$(probe_account_state 880002 B)" absent
check "existing account with the automated name and no users is ours" "$(probe_account_state 880001 A)" ours
db "INSERT INTO accounts VALUES (880003,'Alice trading');"
check "existing account with another name is a conflict" "$(probe_account_state 880003 A | cut -d: -f1)" conflict
db "INSERT INTO accountusers VALUES (880001,'user01');"
check "automated name but a user mapping is a conflict" "$(probe_account_state 880001 A | cut -d: -f1)" conflict
check "conflict record was not modified" "$(db 'SELECT displayname FROM accounts WHERE id=880003;')" "Alice trading"
sql() { return 1; }
probe_account_state 880001 A >/dev/null; check "ownership query failure is an error, not 'absent'" "$?" 1

echo
[[ ${fails} -eq 0 ]] && { echo "[ok] all startup-probe SQL controls passed"; exit 0; }
echo "[FAIL] ${fails} control(s) failed"; exit 1
