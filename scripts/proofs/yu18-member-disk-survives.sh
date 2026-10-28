#!/usr/bin/env bash
# yu18-member-disk-survives.sh — FR-GMR-01 / ADR-087 on the live cluster: a member's log outlives its pod.
# Members 1 and 2 are taken away TOGETHER — a majority — and must return holding the disks they left with,
# and the cluster must resume from the history it had. On a tier whose member disks are deleted with their pods
# this is the path that destroyed a majority of the logs (proof-audit suspect #15): two empty members can elect
# one of themselves on an empty history, and the member that kept the real log can never rejoin. Such a tier
# FAILS step 4 here ("came back on a different disk"). DESTRUCTIVE (the cluster loses quorum), disposable rigs.
#
#   MODE=scale       (default) StatefulSet 3 -> 1 for WINDOW s (default 20) -> 3: the audit's path
#   MODE=delete      both pods force-deleted at once (--grace-period=0 --force), the StatefulSet left at 3
#   MODE=reschedule  member 1 only: its node cordoned, the pod deleted, so it must re-attach its disk on another
#                    node of the pool (needs a spare node: anti-affinity keeps one member per node)
#
#   1. control: an order is acked; the three members agree on applied and on both book hashes (the BEFORE)
#   2. a marker file on members 1 and 2's /data, and their claims' uids
#   3. take them away (MODE)
#   4. they return holding the SAME disk: marker present, claim uid unchanged
#   5. one leader, and every member at the BEFORE: same applied, same order hash, same position hash
#   6. a fresh order is acked and every member's applied moves past the BEFORE
#
# Usage: CTX=<ctx> NS=<ns> DESTRUCTIVE=1 [MODE=scale|delete|reschedule WINDOW=20 ACCT=22214] \
#        bash scripts/proofs/yu18-member-disk-survives.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${DESTRUCTIVE:-0}" == 1 ]] || fail "DESTRUCTIVE=1 required: this takes a majority of the members away"
ACCT="${ACCT:-22214}"; WINDOW="${WINDOW:-20}"; MODE="${MODE:-scale}"; T="$(mint_ticker)"
STS="${STS:-order-matcher-cluster}"; MARK="/data/.yu18-disk-marker-$$-${RANDOM}"
case "${MODE}" in scale|delete) AWAY=(1 2) ;; reschedule) AWAY=(1) ;; *) fail "MODE must be scale, delete or reschedule" ;; esac
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
rest() { code -m 20 -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":${ACCT},\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":$1}"; }
# history <member>: "applied order-hash position-hash" off the member's own /metrics
history() { mget "$1" /metrics | awk -v m="$1" '
  $1 == "traderx_cluster_applied{member=\"" m "\"}" {a=$2}
  $1 == "traderx_book_order_hash{member=\"" m "\"}" {o=$2}
  $1 == "traderx_book_position_hash{member=\"" m "\"}" {p=$2}
  END {print a, o, p}'; }
agreed() { # the one history all members share, or nothing
  local m h first=""; for ((m=0; m<MEMBERS; m++)); do h="$(history "${m}")"; [[ "${h}" =~ ^-?[0-9]+\ -?[0-9]+\ -?[0-9]+$ ]] || return 1
    [[ -z "${first}" ]] && first="${h}"; [[ "${h}" == "${first}" ]] || return 1; done; echo "${first}"; }
claim_uid() { "${K[@]}" get pvc "data-${STS}-$1" -o jsonpath='{.metadata.uid}' 2>/dev/null; }
pod_node() { "${K[@]}" get pod "${STS}-$1" -o jsonpath='{.spec.nodeName}' 2>/dev/null; }
CORDONED=""
restore() { "${K[@]}" scale sts "${STS}" --replicas=3 >/dev/null 2>&1; [[ -n "${CORDONED}" ]] && kubectl --context "${CTX}" uncordon "${CORDONED}" >/dev/null 2>&1; true; }
trap 'restore; _lib_yu18_cleanup' EXIT

step "1. control: an order is acked, and the members agree on one history (the BEFORE)"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-disk' -d "{\"accountId\":${ACCT},\"tickers\":\"${T}\",\"price\":50}")"
[[ "${c}" == 200 ]] || fail "seed ${T}: ${c}"
c="$(rest 49)"; [[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] || fail "control order not acked: ${c} $(body)"
BEFORE=""; for i in $(seq 1 30); do BEFORE="$(agreed)" && break; sleep 1; done
[[ -n "${BEFORE}" ]] || fail "the members never agreed on applied + book hashes (a busy rig? this proof needs it quiet)"
ok "acked $(body); every member at applied/order-hash/position-hash ${BEFORE}"

step "2. mark the disks of member(s) ${AWAY[*]}"
BACKING="$("${K[@]}" get sts "${STS}" -o jsonpath='{.spec.volumeClaimTemplates[*].metadata.name}' 2>/dev/null)"
echo "    StatefulSet claim templates: '${BACKING:-none}'  (none = /data is not a per-member claim)"
UID0=(); NODE0=()   # indexed by member ordinal (bash 3.2 has no associative arrays)
for m in "${AWAY[@]}"; do
  "${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" "${STS}-${m}" -- sh -c "echo ${T} > ${MARK}" || fail "could not mark member ${m}"
  UID0[$m]="$(claim_uid "${m}")"; NODE0[$m]="$(pod_node "${m}")"
  echo "    member ${m}: ${MARK} written; claim data-${STS}-${m} uid '${UID0[$m]:-none}', node ${NODE0[$m]}"
done

step "3. take member(s) ${AWAY[*]} away (MODE=${MODE})"
case "${MODE}" in
  scale)
    "${K[@]}" scale sts "${STS}" --replicas=1 >/dev/null
    "${K[@]}" wait --for=delete pod "${STS}-1" "${STS}-2" --timeout=180s >/dev/null 2>&1 || fail "members 1 and 2 did not go"
    echo "    member 0 alone for ${WINDOW} s"; sleep "${WINDOW}"
    "${K[@]}" scale sts "${STS}" --replicas=3 >/dev/null ;;
  delete)
    "${K[@]}" delete pod "${STS}-1" "${STS}-2" --grace-period=0 --force >/dev/null 2>&1 ;;
  reschedule)
    CORDONED="${NODE0[1]}"; kubectl --context "${CTX}" cordon "${CORDONED}" >/dev/null || fail "could not cordon ${CORDONED}"
    "${K[@]}" delete pod "${STS}-1" --wait=true >/dev/null ;;
esac
# `kubectl wait --for=condition=Ready` returns NotFound at once for a pod not yet recreated: wait for existence.
for m in "${AWAY[@]}"; do
  for _ in $(seq 1 150); do "${K[@]}" get pod "${STS}-${m}" >/dev/null 2>&1 && break; sleep 2; done
  "${K[@]}" wait --for=condition=Ready "pod/${STS}-${m}" --timeout=600s >/dev/null || fail "member ${m} never became Ready"
done
if [[ -n "${CORDONED}" ]]; then kubectl --context "${CTX}" uncordon "${CORDONED}" >/dev/null; CORDONED=""; fi

step "4. member(s) ${AWAY[*]} returned holding the same disk"
for m in "${AWAY[@]}"; do
  got="$("${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" "${STS}-${m}" -- cat "${MARK}" 2>/dev/null)"
  [[ "${got}" == "${T}" ]] || fail "member ${m} came back on a different disk: ${MARK} is gone (claim uid '${UID0[$m]:-none}' -> '$(claim_uid "${m}")'). A majority returning empty is the suspect-#15 path."
  [[ -n "${UID0[$m]}" && "$(claim_uid "${m}")" == "${UID0[$m]}" ]] || fail "member ${m}'s claim changed: '${UID0[$m]}' -> '$(claim_uid "${m}")'"
  if [[ "${MODE}" == reschedule ]]; then
    [[ "$(pod_node "${m}")" != "${NODE0[$m]}" ]] || fail "member ${m} is back on ${NODE0[$m]}: the reschedule never happened (no spare node?)"
    echo "    member ${m}: node ${NODE0[$m]} -> $(pod_node "${m}"), same claim"
  fi
done
ok "marker and claim uid intact on member(s) ${AWAY[*]}"

step "5. one leader, and every member at the BEFORE"
for i in $(seq 1 90); do
  roles="$(for ((m=0; m<MEMBERS; m++)); do mget "${m}" /health | jget 'd.get("role")'; done | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
  AFTER="$(agreed)"; [[ "${roles}" == *"1 LEADER"* && -n "${AFTER}" ]] && break; sleep 2
done
[[ "${roles}" == *"1 LEADER"* ]] || fail "no single leader after the return: ${roles}"
[[ "${AFTER}" == "${BEFORE}" ]] || fail "history moved across the return: ${BEFORE} -> ${AFTER:-<members disagree>}"
ok "roles ${roles} every member at ${AFTER}, the BEFORE"

step "6. a fresh order commits"
for i in $(seq 1 30); do c="$(rest 48)"; [[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] && break; sleep 2; done
[[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] || fail "after the return a fresh order is not acked: ${c} $(body)"
read -r A0 _ <<<"${BEFORE}"
for i in $(seq 1 30); do NOW="$(agreed)" && { read -r A1 _ <<<"${NOW}"; (( A1 > A0 )) && break; }; sleep 1; done
(( ${A1:-0} > A0 )) || fail "the acked order did not move every member past applied ${A0}"
ok "acked $(body); every member at applied ${A1}"
for m in "${AWAY[@]}"; do "${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" "${STS}-${m}" -- rm -f "${MARK}" >/dev/null 2>&1; done
echo; echo "YU18-MEMBER-DISK-SURVIVES PASS mode=${MODE}"
