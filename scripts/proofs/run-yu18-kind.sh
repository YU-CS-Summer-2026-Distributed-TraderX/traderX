#!/usr/bin/env bash
# run-yu18-kind.sh — the yu18-* proofs on a disposable kind rig, each with its negative control.
#
# A proof counts only if it passes AND its negative control (NEGATIVE=1) fails: a proof that cannot fail proves
# nothing. Each line of the summary is "<proof> pass=<PASS|FAIL> negative=<FAILS|PASSES>"; the runner exits 0
# only when every proof passes and every negative control fails.
#
#   kind create cluster --name traderx-gap18
#   kind load docker-image traderx/cluster-node:<tag> traderx/trade-processor:<tag> --name traderx-gap18
#   python3 scripts/proofs/kind-yu18-rig.py --tag <tag> | kubectl --context kind-traderx-gap18 apply -f -
#   CTX=kind-traderx-gap18 NS=traderx bash scripts/proofs/run-yu18-kind.sh [proof-name-substring...]
#
# The rig must have no other writer: several proofs read the members' trade count as theirs, and
# yu18-store-failure-barrier's closed form covers every side the epoch booked.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${CTX:?set CTX (a kind context)}"; : "${NS:?set NS}"
[[ "${CTX}" == kind-* ]] || { echo "[FAIL] kind only: these proofs make the database read only and run one-off pods" >&2; exit 1; }
export CTX NS FIX_TARGET="${FIX_TARGET:-svc/order-matcher}"
LOG="${PROOF_LOG_DIR:-${TMPDIR:-/tmp}/yu18-kind-proofs}"; mkdir -p "${LOG}"
ACCOUNTS="${ACCOUNTS:-22214 42422}"

# Ordered by risk (order duplication, money, risk gate, security).
# The last two restart a workload: yu18-readiness-diagnostics deletes member 2's pod, yu18-snapshot-read-model
# stops and starts trade-processor.
PROOFS=(yu18-fix-duplicate yu18-preopen-retry yu18-store-failure-barrier yu18-untyped-replace yu18-risk-capacity-metrics
        yu18-dev-secret-refusal yu18-readiness-diagnostics yu18-snapshot-read-model)
if [[ $# -gt 0 ]]; then
  sel=(); for p in "${PROOFS[@]}"; do for w in "$@"; do [[ "${p}" == *"${w}"* ]] && { sel+=("${p}"); break; }; done; done
  PROOFS=("${sel[@]}")
fi

# A fresh epoch knows no trading day and no account (FR-PRG-30, FR-PRG-31): the same sequenced control routes
# rig.sh up uses, idempotent on a rig that already has them.
init() (
  . "${here}/lib-yu18.sh"
  pf GW svc/order-matcher 18110
  H=(-H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: run-yu18-kind')
  c="$(code -X POST "${GW}/session/business-day" "${H[@]}" -d "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}")"
  [[ "${c}" == 200 ]] || fail "business day: ${c} $(body)"
  for a in ${ACCOUNTS}; do
    c="$(code -X POST "${GW}/risk/control/account" "${H[@]}" -d "{\"accountId\":${a},\"enabled\":true}")"
    [[ "${c}" == 200 ]] || fail "account ${a}: ${c} $(body)"
  done
  ok "business day $(TZ=America/New_York date +%F) open; accounts ${ACCOUNTS} admitted"
)
init || exit 1

rc=0; SUMMARY=()
for p in "${PROOFS[@]}"; do
  bash "${here}/${p}.sh" > "${LOG}/${p}.log" 2>&1; pos=$?
  NEGATIVE=1 bash "${here}/${p}.sh" > "${LOG}/${p}.negative.log" 2>&1; neg=$?
  v="pass=$([[ ${pos} == 0 ]] && echo PASS || echo FAIL) negative=$([[ ${neg} != 0 ]] && echo FAILS || echo PASSES)"
  [[ ${pos} == 0 && ${neg} != 0 ]] || rc=1
  SUMMARY+=("${p} ${v}"); echo "${p} ${v}   (${LOG}/${p}{,.negative}.log)"
done
echo; printf '%s\n' "${SUMMARY[@]}"
exit "${rc}"
