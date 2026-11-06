#!/usr/bin/env bash
# yu18-readiness-diagnostics.sh — YU18 FR-RD01..04 and FR-GMR-08: a member's /ready, read as an operator reads it.
#
#   1. healthy: every member answers 200 with ready true, electionState CLOSED, 2 configured and 2 observed peers,
#      coverage complete, observedLag = max(0, maxObservedPeerApplied - applied), withinLagTolerance = (observedLag <=
#      configuredMaxLag), comparisonScope maximum_observed_applied_sequence, and a sample started <= completed
#      within the last 10 s of wall clock
#   2. member 2's pod is deleted: while it is away the survivors report 1 observed / 1 unavailable peer, coverage partial
#   3. member 2 comes back: its /ready is polled from member 0 every ~100 ms; on EVERY sample, ready true implies
#      electionState CLOSED and HTTP 200, and not ready implies 503 (FR-GMR-08, FR-RD01); it ends ready
# Deletes one member pod (kind only); the StatefulSet recreates it on its retained volume.
#
# Negative control: NEGATIVE=1 skips the deletion in step 2. Step 2 must FAIL (coverage stays complete); a pass
# means it cannot see a missing peer.
# Usage: CTX=<kind ctx> NS=<ns> [NEGATIVE=1] bash scripts/proofs/yu18-readiness-diagnostics.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${CTX}" == kind-* ]] || fail "kind only: step 2 deletes a member pod"
check() { python3 -c '
import json, sys, time
d = json.loads(sys.argv[1]); now = time.time() * 1000; bad = []
want = {"ready": True, "electionState": "CLOSED", "configuredPeerCount": 2, "observedPeerCount": 2, "unavailablePeerCount": 0,
        "peerObservationStatus": "complete", "localSequenceAvailable": True, "comparisonScope": "maximum_observed_applied_sequence"}
bad += [f"{k}={d.get(k)!r} want {v!r}" for k, v in want.items() if d.get(k) != v]
lag = max(0, d["maxObservedPeerApplied"] - d["applied"])
got, mx = d.get("observedLag"), d["maxObservedPeerApplied"]
if got != lag: bad.append(f"observedLag {got} != max(0, {mx} - applied)")
if d.get("withinLagTolerance") != (lag <= d["configuredMaxLag"]): bad.append("withinLagTolerance disagrees with observedLag <= configuredMaxLag")
s, c = d.get("sampleStartedAtEpochMs"), d.get("sampleCompletedAtEpochMs")
if not (isinstance(s, int) and isinstance(c, int) and s <= c and now - c < 10000): bad.append(f"sample {s}..{c} not ordered or older than 10 s")
print("; ".join(bad) or "ok")' "$1"; }
# poll_ready <member> — from inside member 0, member <m>'s /ready every ~20 ms until it answers ready (or 180 s): one
# "<http> <json>" line per answer; "000" lines (not listening yet) are dropped. One exec, so the sampling is not
# bounded by kubectl's per-call cost (a rejoin on a short log closes its election in well under a second).
poll_ready() { "${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" order-matcher-cluster-0 -- sh -c '
  end=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    out=$(wget -S -T 1 --content-on-error -qO- "http://order-matcher-cluster-'"$1"'.order-matcher-cluster:8080/ready" 2>/tmp/rd-h)
    code=$(awk "/^  HTTP\\//{c=\$2} END{print c}" /tmp/rd-h)
    [ -n "$out" ] && echo "${code:-000} $out"
    case "$out" in *\"ready\":true*) break;; esac
    sleep 0.02
  done' 2>/dev/null; }

step "1. healthy: every member's /ready"
for ((m=0; m<MEMBERS; m++)); do
  [[ "$(mstatus "${m}" /ready)" == 200 ]] || fail "member ${m} /ready answered $(mstatus "${m}" /ready)"
  r="$(mget "${m}" /ready)"; v="$(check "${r}")"; [[ "${v}" == ok ]] || fail "member ${m}: ${v}: ${r}"
done
ok "3 members: 200, ready, CLOSED, 2/2 peers complete, lag and tolerance consistent, fresh samples"

step "2. member 2 away${NEGATIVE:+ (NEGATIVE: not deleted)}"
[[ "${NEGATIVE:-0}" == 1 ]] || "${K[@]}" delete pod order-matcher-cluster-2 --wait=false >/dev/null
seen=""
for i in $(seq 1 60); do
  r="$(mget 0 /ready)"
  [[ "$(jget '[d.get("observedPeerCount"), d.get("unavailablePeerCount"), d.get("peerObservationStatus")]' <<<"${r}")" == '[1, 1, "partial"]' ]] && { seen=1; break; }
  sleep 0.5
done
[[ -n "${seen}" ]] || fail "member 0 never reported 1 observed / 1 unavailable peer, partial (FR-RD02): ${r}"
ok "member 0 with a peer away: $(jget '{k: d[k] for k in ("configuredPeerCount","observedPeerCount","unavailablePeerCount","peerObservationStatus")}' <<<"${r}")"

step "3. member 2 rejoins: ready only with a CLOSED election"
samples=0; notready=0; states=""; rd=""
while IFS= read -r o; do
  h="${o%% *}"; r="${o#* }"; samples=$((samples + 1))
  rd="$(jget 'd.get("ready")' <<<"${r}")"; es="$(jget 'd.get("electionState")' <<<"${r}")"
  [[ "${states}" == *"${es}"* ]] || states+="${es} "
  if [[ "${rd}" == True ]]; then
    [[ "${es}" == CLOSED && "${h}" == 200 ]] || fail "member 2 ready with electionState ${es}, HTTP ${h} (FR-GMR-08): ${r}"
  else
    notready=$((notready + 1)); [[ "${h}" == 503 ]] || fail "member 2 not ready but HTTP ${h} (FR-RD01: readiness status preserved): ${r}"
  fi
done < <(poll_ready 2)
[[ "${rd:-}" == True ]] || fail "member 2 never became ready (${samples} samples, states: ${states})"
ok "${samples} samples; ${notready} not ready (503), then ready at CLOSED; election states seen: ${states}"
for i in $(seq 1 60); do r="$(mget 0 /ready)"; [[ "$(jget 'd.get("peerObservationStatus")' <<<"${r}")" == complete ]] && break; sleep 0.5; done
v="$(check "$(mget 2 /ready)")"; [[ "${v}" == ok ]] || fail "member 2 after rejoining: ${v}"
ok "coverage complete again; member 2's /ready passes step 1's checks"

echo; echo "YU18-READINESS-DIAGNOSTICS PASS"
