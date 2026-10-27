#!/usr/bin/env bash
# yu18-networkpolicy-enforced.sh — proves the members' NetworkPolicy (ADR-079 §4, 4b526bd0) is
# ENFORCED on this cluster, not merely applied.
#
# A NetworkPolicy object is accepted by every GKE cluster and enforced only on one created with
# Dataplane V2 (--enable-dataplane-v2) or --enable-network-policy. On the hxb rig it was inert:
# applied, listed, and every pod could reach the members' Aeron ingress. "kubectl get networkpolicy"
# therefore proves nothing. This proof uses the real protocol, through the real policy, twice:
#
#   positive control  a pod carrying an allowed label (app: cluster-proof-client) runs the
#                     ClusterProofClient and must CONNECT to the members within the deadline;
#   negative control  the SAME image, SAME secret, SAME endpoints, with a label the policy does
#                     not list, must NOT connect within the deadline, and must be seen TRYING
#                     (CONNECT-RETRY lines), so a pod that never ran cannot pass as "blocked".
#
# The only variable between the two pods is the label. Both carry CLUSTER_CLIENT_SECRET, so the
# authenticator (which refuses a session without it) cannot be what blocks the negative control.
#
# Precondition, checked first when gcloud is available: the cluster reports Dataplane V2
# (networkConfig.datapathProvider=ADVANCED_DATAPATH) or network policy enabled. Without either the
# negative control connects and the proof FAILS, which is the correct answer: the policy is inert.
#
# Usage: ./yu18-networkpolicy-enforced.sh   (kubectl context on the GKE cluster, members Running)
#   CTX, NS, DEADLINE_S (default 90), CLUSTER/ZONE/PROJECT for the gcloud precondition (optional)
set -euo pipefail

CTX="${CTX:-$(kubectl config current-context)}"
NS="${NS:-traderx}"
DEADLINE_S="${DEADLINE_S:-90}"
K="kubectl --context ${CTX} -n ${NS}"
here="$(cd "$(dirname "$0")" && pwd)"
PROOF_CLIENT="${PROOF_CLIENT:-${here}/../../specs/YU17-otc-rates/generation/kubernetes/cluster/gke/proof-client.yaml}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

cleanup() { ${K} delete pod netpol-positive netpol-negative --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

step "0. preflight"
[[ "${CTX}" != kind-* ]] || fail "context ${CTX} is kind; this proof is for the GKE tier (kind enforces policy on its own CNI)"
[[ -f "${PROOF_CLIENT}" ]] || fail "proof-client manifest not found at ${PROOF_CLIENT}"
${K} get networkpolicy order-matcher-cluster >/dev/null 2>&1 || fail "NetworkPolicy order-matcher-cluster is not applied in ${NS}"
[[ "$(${K} get pod -l app=order-matcher-cluster --field-selector=status.phase=Running -o name | wc -l | tr -d ' ')" == "3" ]] \
  || fail "need 3 Running cluster members"
${K} get secret auth-secrets -o jsonpath='{.data.cluster-client-secret}' | grep -q . \
  || fail "auth-secrets/cluster-client-secret is missing; both controls present it, so it must exist"
if [[ -n "${CLUSTER:-}" ]] && command -v gcloud >/dev/null 2>&1; then
  DP="$(gcloud container clusters describe "${CLUSTER}" ${ZONE:+--zone "${ZONE}"} ${PROJECT:+--project "${PROJECT}"} \
        --format='value(networkConfig.datapathProvider,networkPolicy.enabled)' 2>/dev/null || true)"
  echo "    cluster ${CLUSTER}: datapathProvider/networkPolicy = ${DP:-<describe failed>}"
  case "${DP}" in
    *ADVANCED_DATAPATH*|*True*) echo "[ok] enforcement is enabled on ${CLUSTER}" ;;
    "") echo "[warn] could not describe ${CLUSTER}; the negative control below decides" ;;
    *) fail "cluster ${CLUSTER} has neither Dataplane V2 nor network-policy enforcement; the policy is inert here. Create the next cluster with --enable-dataplane-v2 (CLOUD-ARCHITECTURE.md)" ;;
  esac
else
  echo "    (set CLUSTER/ZONE/PROJECT to check the cluster's datapath first; the negative control decides regardless)"
fi

# Both pods are the checked-in proof client with only the pod name and the app label changed.
# The positive control keeps the label the policy lists; the negative control gets one it does not.
# PROOF_INTERVAL_MS is raised to 10 min: the client sequences a REAL resting order every interval,
# and at the manifest's 10 ms the positive control alone left 3,285 resting AAL buys (account 11) on
# a side-namespace rig in 60 s (proof audit, 2026-10-27). One probe order is enough to show CONNECTED.
render() { # render <pod-name> <app-label>
  sed -e "s/^  name: cluster-proof-client\$/  name: $1/" \
      -e "s/^    app: cluster-proof-client\$/    app: $2/" \
      -e '/name: PROOF_INTERVAL_MS/{n;s/value: "[0-9]*"/value: "600000"/;}' "${PROOF_CLIENT}"
}
grep -q '^  name: cluster-proof-client$' "${PROOF_CLIENT}" && grep -q '^    app: cluster-proof-client$' "${PROOF_CLIENT}" \
  && grep -q 'name: PROOF_INTERVAL_MS' "${PROOF_CLIENT}" \
  || fail "proof-client.yaml no longer has the name/label lines this proof rewrites; update render()"

step "1. launch both controls (same image, same secret, same endpoints; only the label differs)"
cleanup
render netpol-positive cluster-proof-client | ${K} apply -f - >/dev/null
render netpol-negative netpol-negative-control | ${K} apply -f - >/dev/null
echo "[ok] netpol-positive (app=cluster-proof-client) and netpol-negative (app=netpol-negative-control) created"

# Read whole, then grep: `logs | grep -q` under pipefail fails on SIGPIPE once the log is long.
connected() { local l; l="$(${K} logs "$1" 2>/dev/null)"; grep -q '^CONNECTED' <<<"${l}"; }
tried()     { local l; l="$(${K} logs "$1" 2>/dev/null)"; grep -q '^CONNECT-.*RETRY' <<<"${l}"; }

step "2. positive control: the allowed label must connect within ${DEADLINE_S}s"
deadline=$((SECONDS + DEADLINE_S))
until connected netpol-positive; do
  (( SECONDS < deadline )) || { ${K} logs netpol-positive --tail=20 >&2 || true; fail "positive control never connected: the policy (or the rig) blocks even the allowed label"; }
  sleep 3
done
echo "[ok] positive control connected: $(${K} logs netpol-positive | grep -m1 '^CONNECTED')"

step "3. negative control: the unlisted label must be seen trying and must NOT connect within ${DEADLINE_S}s"
# The positive control has already proven the members accept a client, so a negative control that
# cannot connect now is blocked by the policy, not by a cluster that is down.
deadline=$((SECONDS + DEADLINE_S))
while (( SECONDS < deadline )); do
  if connected netpol-negative; then
    ${K} logs netpol-negative --tail=20 >&2 || true
    fail "negative control CONNECTED: the NetworkPolicy is applied but NOT enforced on this cluster"
  fi
  sleep 3
done
tried netpol-negative || { ${K} logs netpol-negative --tail=20 >&2 || true
  fail "negative control never attempted a connect (no CONNECT-RETRY line): the control did not run, so 'blocked' is unproven"; }
echo "[ok] negative control tried and was refused for ${DEADLINE_S}s: $(${K} logs netpol-negative | grep -m1 '^CONNECT-.*RETRY')"

echo
echo "NETWORKPOLICY-ENFORCED cluster-context=${CTX} namespace=${NS} positive=connected negative=blocked deadline=${DEADLINE_S}s"
