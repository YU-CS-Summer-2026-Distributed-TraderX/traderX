#!/usr/bin/env bash
# OTEL-01 proof: a single order produces ONE distributed trace spanning the gateway and the
# cluster member, even though no trace context is ever written into the replicated log.
#
# WHY THIS IS FALSIFIABLE, and not just "look, spans appeared":
# the script computes the expected trace id and the expected parent span id from the ClOrdID
# ALONE, in awk/python, with no input from either server — the same derivation OrderTrace.java
# performs independently in the gateway JVM and in the member JVM. It then asks Tempo for exactly
# that trace id. If the gateway and the member did not independently agree on the identity, the
# lookup 404s or returns a half-trace, and this script fails. A version of the system that
# smuggled a traceparent through the log would also pass the "spans appeared" test; only this
# one pins the actual claim.
#
# Asserts:
#   1. GET /api/traces/<predicted id> returns a trace (the derivation is right on both sides)
#   2. it contains 5 spans from 2 services (gateway: order/queue/consensus; member: commit/apply)
#   3. the member's spans parent to the gateway's cluster.consensus span, whose id was PREDICTED
#   4. the span sinks report zero drops and zero export failures for this run
#   5. ground truth advanced by exactly the number of orders submitted (traderx_cluster_next_order_ref)
#
# This is a FUNCTIONAL proof. It says nothing about cost: the "telemetry is free" claim is a
# timing claim and must be measured on GKE with the before/after benchmark, never on kind.
#
# Usage: MATCHER_URL=http://localhost:18110 TEMPO_URL=http://localhost:3200 bash $0
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog(){ [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
MEMBER_POD="${MEMBER_POD:-order-matcher-cluster-0}"
NS="${NS:-traderx}"
KCTX="${KCTX:-${CTX:-}}"   # CTX accepted too, so one env var drives every proof
# REFUSE rather than inherit. This used to fall through to the operator's ambient current-context
# when neither var was set, which is how a proof reports on the wrong cluster: this project keeps
# TWO rigs in kubeconfig, and on 2026-08-12 current-context was the GKE bench cluster while the
# subject was kind. Deliberately NOT defaulted to kind either -- that only points the same bug the
# other way, silently wrong the day someone runs this against GKE. The caller chooses the rig.
if [ -z "${KCTX}" ]; then
  AMBIENT="$(kubectl config current-context 2>/dev/null || true)"
  echo "[FAIL] neither KCTX nor CTX is set, so this proof does not know which rig to assert about." >&2
  echo "       It will NOT fall back to the ambient current-context (${AMBIENT:-<none>})." >&2
  echo "       Set one: CTX=kind-traderx-yu12-cluster  (the YU15 Aeron cluster rig)" >&2
  echo "                CTX=kind-traderx-state-014     (the single-BLP tier)" >&2
  echo "                CTX=<gke context>              (to assert against GKE)" >&2
  exit 1
fi
K="kubectl --context ${KCTX} -n ${NS}"
ORDERS="${ORDERS:-3}"
TAG="otel-$$-$(date +%s)"

fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }

command -v python3 >/dev/null || fail "python3 required"

curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null || fail "gateway not ready at ${MATCHER_URL}"

# PRECONDITION, not a warning. start-cluster-kind.sh deploys only the trading tier, so the
# observability stack is absent by default and the trace pipeline dies SILENTLY: orders book
# normally, spans go nowhere, and the only symptom is an empty Tempo. This probe used to warn and
# continue, which turned that into a Tempo 404 reported as "the gateway and member did NOT derive
# the same identity" — a precondition failure wearing the verdict of a real defect. Tempo is the
# right thing to probe (not the collector): it is what the assertions below actually query.
need_obs() { # url  name  port-forward-target
  local i rc
  for i in $(seq 1 "${OBS_WAIT_TRIES:-10}"); do
    rc=0; curl -sf --max-time 5 "$1" >/dev/null 2>&1 || rc=$?
    if [[ ${rc} -eq 0 ]]; then return 0; fi
    # 7 = nothing listening. No stack and no port-forward look identical from here, and neither
    # heals on its own — retrying is theatre. Anything else (a 503 from a Tempo that is still
    # coming up after a fresh forward) is worth waiting out.
    if [[ ${rc} -eq 7 ]]; then break; fi
    sleep 2
  done
  echo "[FAIL] $2 is not reachable at $1 (curl rc=${rc}; 7=nothing listening, 28=timed out)." >&2
  echo "[hint] the only bring-up in this tree is scripts/yu15/start-observability-kind.sh, and it" >&2
  echo "       is the KIND rig's — on another rig that is not your path. If yours reaches these" >&2
  echo "       endpoints through a forward: ${K} port-forward $3 &" >&2
  exit 1
}
need_obs "${TEMPO_URL}/ready" Tempo "svc/tempo 3200:3200"

ref_before="$(curl -s --max-time 5 "${MATCHER_URL}/metrics" | awk '/traderx_gateway_pipeline_total\{stage="ack_completed"\}/{print $2}')"
ref_before="${ref_before:-0}"

echo "[run] submitting ${ORDERS} orders tagged ${TAG}"
for i in $(seq 1 "${ORDERS}"); do
  curl -s --max-time 15 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":22214,\"ticker\":\"AAPL\",\"side\":\"Buy\",\"quantity\":10,\"limitPrice\":150.0,\"clientOrderId\":\"${TAG}-${i}\"}" \
    >/dev/null || fail "order ${i} was not acked"
done
ok "${ORDERS} orders acked by the cluster"

# The exporter batches on a flush interval (OTEL_FLUSH_MS, default 1s) and Tempo needs a moment
# to make the trace queryable. This wait is the ONLY thing in the pipeline that is allowed to be
# slow — by design, the trade path never waits for any of it.
echo "[run] waiting for the asynchronous exporter to flush"
sleep "${FLUSH_WAIT:-8}"

vlog "   tempo=${TEMPO_URL}  matcher=${MATCHER_URL}  member=${MEMBER_POD}  tag=${TAG}  orders=${ORDERS}"
TAG="${TAG}" ORDERS="${ORDERS}" TEMPO_URL="${TEMPO_URL}" PROOF_VERBOSE="${VERBOSE}" python3 - <<'PY'
import base64, json, os, sys, urllib.error, urllib.request

VERBOSE = os.environ.get("PROOF_VERBOSE") == "1"

M = (1 << 64) - 1
TRACE_SALT = 0x5851F42D4C957F2D
CLUSTER_SALT = 0x14057B7EF767814F
SAMPLE_SALT = 0x2545F4914F6CDD1D

def client_order_key(s):                      # ClusterGatewayMain.clientOrderKey (FNV-1a)
    h = 0xcbf29ce484222325
    for ch in s:
        h = ((h ^ ord(ch)) * 0x100000001b3) & M
    return h or 1

def mix(x):                                   # OrderTrace.mix (splitmix64 finalizer)
    z = (x + 0x9E3779B97F4A7C15) & M
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M
    return (z ^ (z >> 31)) & M

tag, orders = os.environ["TAG"], int(os.environ["ORDERS"])
tempo = os.environ["TEMPO_URL"]
failures = []

for i in range(1, orders + 1):
    clordid = f"{tag}-{i}"
    key = client_order_key(clordid)
    if (mix(key ^ SAMPLE_SALT) & int(os.environ.get("OTEL_SAMPLE_MASK", "0"))) != 0:
        print(f"[skip] {clordid} is not in the head sample")
        continue
    trace_id = f"{mix(key) or 1:016x}{mix(key ^ TRACE_SALT) or 1:016x}"
    predicted_parent = f"{mix(key ^ CLUSTER_SALT) or 1:016x}"
    if VERBOSE:
        # The whole point of this proof is that these three values come from the ClOrdID and
        # NOTHING else — no server was consulted to produce them. Printing the inputs and the
        # derived ids is what lets a reader check that claim instead of trusting it.
        print(f"      derive {clordid}: key=0x{key:016x}", file=sys.stderr)
        print(f"        traceId        = {trace_id}   (predicted, before asking Tempo)", file=sys.stderr)
        print(f"        parentSpanId   = {predicted_parent}   (the gateway's cluster.consensus span)", file=sys.stderr)
        print(f"        GET {tempo}/api/traces/{trace_id}", file=sys.stderr)
    try:
        doc = json.load(urllib.request.urlopen(f"{tempo}/api/traces/{trace_id}", timeout=15))
    except urllib.error.HTTPError as e:
        failures.append(f"{clordid}: Tempo has no trace {trace_id} (HTTP {e.code}) — the gateway "
                        f"and member did NOT derive the same identity")
        continue

    spans = []
    for batch in doc.get("batches", []):
        svc = next((a["value"]["stringValue"] for a in batch["resource"]["attributes"]
                    if a["key"] == "service.name"), "?")
        for scope in batch.get("scopeSpans", []):
            for s in scope.get("spans", []):
                spans.append({
                    "svc": svc,
                    "name": s["name"],
                    "id": base64.b64decode(s["spanId"]).hex(),
                    "parent": base64.b64decode(s["parentSpanId"]).hex() if s.get("parentSpanId") else "",
                })

    if VERBOSE:
        # The terse line says "5 spans, 2 services". This is the tree it is summarising: which
        # service emitted each span and what it parents to, which is the actual shape assertions
        # 2 and 3 are about.
        print(f"      span tree for {clordid}:", file=sys.stderr)
        for sp in sorted(spans, key=lambda x: (x["svc"], x["name"])):
            par = sp["parent"] or "<root>"
            print(f"        {sp['svc']:<26} {sp['name']:<18} id={sp['id']} parent={par}", file=sys.stderr)
    services = {s["svc"] for s in spans}
    names = {s["name"] for s in spans}
    consensus = [s for s in spans if s["name"] == "cluster.consensus"]
    member_spans = [s for s in spans if s["svc"].endswith("member")]

    if len(spans) != 5:
        failures.append(f"{clordid}: expected 5 spans, got {len(spans)} ({sorted(names)})")
    if len(services) != 2:
        failures.append(f"{clordid}: expected gateway+member, got {sorted(services)}")
    if not consensus:
        failures.append(f"{clordid}: no cluster.consensus span")
    elif consensus[0]["id"] != predicted_parent:
        failures.append(f"{clordid}: consensus span id {consensus[0]['id']} != predicted "
                        f"{predicted_parent} — the derivation does not match the running code")
    if len(member_spans) != 2:
        failures.append(f"{clordid}: expected 2 member spans, got {len(member_spans)}")
    elif consensus and {s['parent'] for s in member_spans} != {consensus[0]['id']}:
        failures.append(f"{clordid}: member spans are not children of cluster.consensus — the "
                        f"trace is split across the consensus boundary")

    if not failures:
        print(f"[ok] {clordid}: trace {trace_id} joined across {sorted(services)}; "
              f"predicted parent {predicted_parent} confirmed")

if failures:
    for f in failures:
        print(f"[FAIL] {f}", file=sys.stderr)
    sys.exit(1)
print("[ok] every sampled order produced one joined gateway+member trace")
PY

echo "[check] span sinks: drops and export failures must be zero"
gw_metrics="$(curl -s --max-time 5 "${MATCHER_URL}/metrics")"
gw_dropped="$(awk '/traderx_otel_spans_total\{outcome="dropped"\}/{print $2}' <<<"${gw_metrics}")"
gw_failed="$(awk '/traderx_otel_export_failures_total/{print $2}' <<<"${gw_metrics}")"
[[ "${gw_dropped:-0}" == "0" ]] || fail "gateway dropped ${gw_dropped} spans"
[[ "${gw_failed:-0}" == "0" ]] || fail "gateway had ${gw_failed} export failures"
ok "gateway sink clean (0 dropped, 0 export failures)"

if ${K} get pod "${MEMBER_POD}" >/dev/null 2>&1; then
  mem_metrics="$(${K} exec "${MEMBER_POD}" -- curl -s --max-time 5 http://localhost:8080/metrics 2>/dev/null || true)"
  # Skip "# TYPE ..." help lines — matching them too yields a two-line value that reads as "TYPE".
  mem_dropped="$(awk '!/^#/ && /traderx_otel_spans_total\{outcome="dropped"\}/{print $2}' <<<"${mem_metrics}")"
  role="$(awk '!/^#/ && /^traderx_cluster_role/{print $2}' <<<"${mem_metrics}")"
  next_ref="$(awk '!/^#/ && /^traderx_cluster_next_order_ref/{print $2}' <<<"${mem_metrics}")"
  [[ "${mem_dropped:-0}" == "0" ]] || fail "member dropped ${mem_dropped} spans"
  ok "member sink clean; role=${role} (1 = leader); ground truth next_order_ref=${next_ref}"
fi

echo
echo "[PASS] one order, one trace, across the consensus boundary — with nothing about tracing"
echo "       in the replicated log. Trace identity was PREDICTED from the ClOrdID and confirmed."
