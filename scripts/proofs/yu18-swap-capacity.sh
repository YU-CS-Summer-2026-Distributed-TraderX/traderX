#!/usr/bin/env bash
# yu18-swap-capacity.sh — YU17 FR-OTC09: the contract store holds at most MAX_CONTRACTS (4096) and
# refuses a booking at capacity with RiskReason.CAPACITY, deterministically on every member.
#
#   1. book 1-USD swaps (no clientOrderId: each a distinct contract, FR-OTC contract-delta) until the
#      first refusal; every refusal before capacity fails the proof, so the count is the store's
#   2. at capacity: the refusal is 422 {booked:false, reason CAPACITY}, and three more bookings on
#      other accounts and both directions are refused the same way (deterministic, not a race)
#   3. every member's applied sequence moved by the same amount, and the members agree on it
#      (NFR-OTC04: a booking, refused or not, is a sequenced command)
#   4. the store size: booked here + the contracts already present. PRIOR=<n> (the last
#      `RISK-EXTRACT-CUT ... contracts=<n>` before this run) makes it an equality with 4096.
#
# IRREVERSIBLE for the epoch (FR-OTC11: nothing removes a contract): run it LAST, after the yu17-swap
# proofs, on a rig you will tear down. Usage: CTX=<ctx> NS=<ns> [PRIOR=<n>] bash scripts/proofs/yu18-swap-capacity.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
MAX=4096
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
applied_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(member_applied "${m}") "; done; echo "${s% }"; }

step "1-2. book until the store refuses"
A0="$(applied_all)"
OUT="$(python3 - "${GW}" "${ADMIN}" "${MAX}" <<'PY'
import json, sys, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
gw, tok, cap = sys.argv[1], sys.argv[2], int(sys.argv[3])
def book(acct, pr):
    body = {"accountId": acct, "payReceive": pr, "notional": 1, "fixedRate": 0.042,
            "effectiveDate": "2026-11-02", "maturityDate": "2031-11-02", "conventions": "USD-SOFR-1Y-ACT360"}
    rq = urllib.request.Request(gw + "/swaps", json.dumps(body).encode(), method="POST",
                                headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok})
    try:
        with urllib.request.urlopen(rq, timeout=30) as r: return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")
booked, first_refusal, other = 0, None, []
with ThreadPoolExecutor(16) as ex:
    while first_refusal is None and booked <= cap:
        for c, d in ex.map(lambda i: book(22214, "Receive"), range(64)):
            if c == 200 and d.get("booked"): booked += 1
            elif c == 422 and "CAPACITY" in json.dumps(d): first_refusal = first_refusal or d
            else: other.append((c, d))
        if other: break
after = [book(a, pr) for a, pr in ((42422, "Pay"), (22214, "Pay"), (42422, "Receive"))]
print(json.dumps({"booked": booked, "firstRefusal": first_refusal, "other": other[:3], "after": after}))
PY
)"
echo "    ${OUT:0:600}"
booked="$(jget 'd["booked"]' <<<"${OUT}")"
[[ "$(jget 'len(d["other"])' <<<"${OUT}")" == 0 ]] || fail "a booking was refused for something other than capacity before the store filled: $(jget 'd["other"]' <<<"${OUT}")"
[[ "$(jget 'd["firstRefusal"] is not None' <<<"${OUT}")" == True ]] || fail "booked ${booked} without a CAPACITY refusal (cap ${MAX})"
[[ "$(jget 'all(c==422 and "CAPACITY" in json.dumps(x) and x.get("booked") is False for c,x in d["after"])' <<<"${OUT}")" == True ]] \
  || fail "after capacity, not every booking was refused CAPACITY: $(jget 'd["after"]' <<<"${OUT}")"
ok "${booked} booked, then CAPACITY: $(jget 'd["firstRefusal"]' <<<"${OUT}"); three more refused identically"

step "3. every member sequenced the same commands"
for i in $(seq 1 30); do A1="$(applied_all)"; read -r -a a <<<"${A1}"; [[ "${a[0]}" == "${a[1]}" && "${a[1]}" == "${a[2]}" ]] && break; sleep 1; done
read -r -a a0 <<<"${A0}"; read -r -a a1 <<<"${A1}"
[[ "${a1[0]}" == "${a1[1]}" && "${a1[1]}" == "${a1[2]}" ]] || fail "members' applied ${A1} disagree"
(( a1[0] - a0[0] >= booked + 4 )) || fail "applied moved ${a0[0]} -> ${a1[0]}, fewer than the ${booked} bookings + refusals"
ok "applied ${A0} -> ${A1}"

step "4. the store size"
if [[ -n "${PRIOR:-}" ]]; then
  [[ $(( PRIOR + booked )) == "${MAX}" ]] || fail "prior ${PRIOR} + booked ${booked} = $(( PRIOR + booked )), want ${MAX} (FR-OTC09)"
  ok "prior ${PRIOR} + booked ${booked} = ${MAX}"
else
  (( booked <= MAX )) || fail "booked ${booked} > ${MAX}"
  echo "    PRIOR unset: ${booked} booked here; the store held $(( MAX - booked )) before if FR-OTC09 holds"
fi
echo; echo "YU18-SWAP-CAPACITY PASS booked=${booked}"
