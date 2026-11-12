#!/usr/bin/env bash
# kind-drs55-claims.sh — FR-DRS-55 read back on a live rig (reads only): each gateway's claim and fill-feed log lines,
# the KV bucket FILLS_SHARD_CLAIMS (key <k> -> pod), the consumers on each TRADERX_FILLS_<k>, the router's gateway
# names (<pod>_s<k> once something was routed to them). Prints one JSON summary last, with
# ok = every running gateway holds its own stream, a stream's consumers are exactly its holder's one, the rest have none.
#
#   CTX=kind-<c> NS=traderx [OUT=<file>] bash scripts/proofs/kind-drs55-claims.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
S="$("${K[@]}" get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="FILLS_STREAMS")].value}')"
N="$("${K[@]}" get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="FILLS_SHARD_CLAIM")].value}')"
(( ${S:-1} > 1 )) || fail "FILLS_STREAMS='${S}': not a sharded rig"
GWS=($("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name))
(( ${#GWS[@]} >= 1 )) || fail "no running cluster-gateway pod"
pf NATSP "${NATS_TARGET:-svc/nats}" 4222 tcp
pf TP "${TP_TARGET:-svc/trade-processor}" 18091
tmp="$(mktemp -d)"
for p in "${GWS[@]}"; do
  glog="$("${K[@]}" logs "${p}" 2>/dev/null)" || fail "logs ${p}"
  { grep -E 'fills stream claim' <<<"${glog}"; grep -E 'FIX fill feed: routed, JetStream ordered consumer on' <<<"${glog}"; } \
    | sed "s|^|${p#pod/} |" >> "${tmp}/logs"
done
# No bucket is a reading (no gateway ever claimed: FR-DRS-55's fallback), not an error: it is reported as kv {}.
if python3 "${here}/lib-jetstream.py" info "127.0.0.1:${NATSP}" KV_FILLS_SHARD_CLAIMS | grep -q '"config"'; then
  python3 "${here}/lib-jetstream.py" read "127.0.0.1:${NATSP}" KV_FILLS_SHARD_CLAIMS '$KV.FILLS_SHARD_CLAIMS.>' 1 > "${tmp}/kv" \
    || fail "KV_FILLS_SHARD_CLAIMS unreadable"
else
  echo "note: no KV_FILLS_SHARD_CLAIMS stream" >&2; : > "${tmp}/kv"
fi
for ((k = 0; k < S; k++)); do
  python3 "${here}/lib-jetstream.py" consumers "127.0.0.1:${NATSP}" "TRADERX_FILLS_${k}" | sed "s|^|${k} |" >> "${tmp}/consumers" \
    || fail "consumers of TRADERX_FILLS_${k} unreadable"
done
curl -s -m 10 "${TP}/v2/projection-feed" | jget 'json.dumps(d["orders"]["fills"])' > "${tmp}/router"
python3 - "${tmp}" "${S}" "${N:-1}" "${GWS[@]#pod/}" <<'PY' | tee "${OUT:-/dev/null}"
import json, re, sys
tmp, S, N, gws = sys.argv[1], int(sys.argv[2]), max(1, int(sys.argv[3])), sys.argv[4:]
logs = [l.rstrip("\n") for l in open(f"{tmp}/logs")]
claims = {}   # pod -> k from "fills stream claim: <pod> holds stream <k> of <S>"
failed = [l for l in logs if "claim failed" in l or "not renewed" in l or "all " in l and " held" in l]
subs = {}
for l in logs:
    pod, msg = l.split(" ", 1)
    m = re.search(r"fills stream claim: (\S+) holds stream (\d+) of (\d+)", msg)
    if m: claims.setdefault(pod, []).append(int(m.group(2)))
    m = re.search(r"consumer on (\S+) of (\S+)", msg)
    if m: subs.setdefault(pod, []).append((m.group(2), m.group(1)))
kv = {}   # latest value per key (the read is in stream order)
for l in open(f"{tmp}/kv"):
    if l.strip():
        d = json.loads(l)
        if "subject" not in d: continue   # the reader's trailing {"_read": n, "_pending": m}
        key = d["subject"].rsplit(".", 1)[1]
        if "data" not in d: kv.pop(key, None); continue   # no payload: a delete / purge / TTL marker
        kv[key] = d["data"] if isinstance(d["data"], str) else json.dumps(d["data"])
cons = {}
for l in open(f"{tmp}/consumers"):
    if l.strip():
        k, j = l.split(" ", 1); cons.setdefault(int(k), []).append(json.loads(j).get("filters"))
router = json.loads(open(f"{tmp}/router").read() or "null")
held = {p: claims.get(p, [None])[-1] for p in gws}
bad = []
if len(set(held.values())) != len(gws) or None in held.values(): bad.append(f"claims not distinct: {held}")
for p, k in held.items():
    want = [f"/fillsk.{k}.{p}_s{k}"]
    if cons.get(k) != [want]: bad.append(f"TRADERX_FILLS_{k} consumers {cons.get(k)}, want [{want}] ({p})")
    if kv.get(str(k)) != p: bad.append(f"KV {k} = {kv.get(str(k))}, want {p}")
    if subs.get(p, [(None, None)])[-1] != (f"TRADERX_FILLS_{k}", f"/fillsk.{k}.{p}_s{k}"): bad.append(f"{p} subscribed {subs.get(p)}")
for k in range(S):
    if k not in held.values() and cons.get(k): bad.append(f"unclaimed TRADERX_FILLS_{k} has consumers {cons[k]}")
by_server = {s: sorted(k for k in held.values() if k is not None and k % N == s) for s in range(N)}
print("\n".join(logs))
print(json.dumps({"kvRecords": [json.loads(l) for l in open(f"{tmp}/kv") if l.strip() and "subject" in l], "held": held, "byServer(k mod FILLS_SHARD_CLAIM)": by_server, "kv": kv, "consumers": {str(k): cons.get(k, []) for k in range(S)},
                  "routerGateways": (router or {}).get("gateways"), "claimFailures": failed, "ok": not bad, "bad": bad}, indent=1))
PY
