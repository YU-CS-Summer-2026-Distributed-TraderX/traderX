#!/usr/bin/env bash
# Replay anchor stamping is required by default. K must name an explicit context and namespace.
# Existing simple strings and indexed arrays are accepted; no eval or ambient kubectl target.
# The only anchor source is the creationTimestamp of the bound member-0 PVC actually selected
# by cluster-node's /data mount, cross-checked against its PV claimRef. This is storage-creation
# evidence, not authenticated engine-epoch identity. Never substitute now, pod start or container ID.
# REPLAY_ANCHOR_MODE=disabled explicitly skips stamping (status=disabled, rc=0); this makes no
# assertion about tape, synthetic mode, a fresh epoch or a verified anchor. Required refusal is rc=1.
# REPLAY_ANCHOR_STATUS and REPLAY_ANCHOR_PRODUCER distinguish stored anchors from producer rollout.
# Extract-fetch functions below retain their existing best-effort contracts and _rk behavior.
_REPLAY_ANCHOR_EVIDENCE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/replay-anchor-evidence.py"

_rk() {
  if [[ "$(declare -p K 2>/dev/null)" == "declare -"[aA]* ]]; then "${K[@]}" "$@"; else ${K} "$@"; fi
}

_replay_anchor_target() {
  local declaration attributes executable token value context="" namespace=""
  declaration="$(declare -p K 2>/dev/null)" || return 1
  attributes="${declaration#declare }"; attributes="${attributes%% *}"
  case "${attributes}" in
    *A*) return 1 ;;
    *a*) replay_k=("${K[@]}") ;;
    --|-x|-r|-rx|-xr)
      # Deliberately simple whitespace-separated legacy prefixes; quoted/escaped strings must
      # migrate to indexed arrays. read performs no globbing, evaluation or command substitution.
      [[ "${K}" != *$'\n'* ]] || return 1
      read -r -a replay_k <<< "${K}" ;;
    *) return 1 ;;
  esac
  [[ ${#replay_k[@]} -gt 0 && "${replay_k[0]##*/}" == kubectl ]] || return 1
  executable="${replay_k[0]}"
  replay_k=("${replay_k[@]:1}")
  while [[ ${#replay_k[@]} -gt 0 ]]; do
    token="${replay_k[0]}"; replay_k=("${replay_k[@]:1}")
    case "${token}" in
      --context|-n|--namespace)
        [[ ${#replay_k[@]} -gt 0 ]] || return 1
        value="${replay_k[0]}"; replay_k=("${replay_k[@]:1}") ;;
      --context=*|--namespace=*) value="${token#*=}" ;;
      *) return 1 ;;
    esac
    # No unknown flags, duplicate targets, whitespace, quotes, shell syntax or empty values.
    [[ "${value}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:/@-]*$ ]] || return 1
    case "${token}" in
      --context*) [[ -z "${context}" ]] || return 1; context="${value}" ;;
      *) [[ -z "${namespace}" && "${value}" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ && ${#value} -le 63 ]] || return 1
         namespace="${value}" ;;
    esac
  done
  [[ -n "${context}" && -n "${namespace}" ]] || return 1
  # Preserve the selected executable, normalize flags, and expose the explicit target for evidence.
  replay_k=("${executable}" --context "${context}" -n "${namespace}")
  replay_context="${context}"; replay_namespace="${namespace}"
}

stamp_replay_epoch() {
  local replay_k=() replay_context replay_namespace
  local pod pvc pv volume claim evidence ms ts uid yaml deployment producer
  REPLAY_ANCHOR_STATUS=unavailable
  REPLAY_ANCHOR_PRODUCER=uninspected
  _replay_anchor_target || { echo '[epoch] unavailable: K must be kubectl with one explicit --context and namespace; quoted or escaped string prefixes require an indexed array' >&2; return 1; }
  case "${REPLAY_ANCHOR_MODE:-required}" in
    disabled)
      REPLAY_ANCHOR_STATUS=disabled
      echo "[epoch] disabled: explicit operator choice; no anchor verified or written (context=${replay_context}, namespace=${replay_namespace})"
      return 0 ;;
    required) ;;
    *) echo '[epoch] unavailable: REPLAY_ANCHOR_MODE must be required or disabled' >&2; return 1 ;;
  esac
  pod="$("${replay_k[@]}" get pod order-matcher-cluster-0 -o json)" \
    || { echo '[epoch] unavailable: member-0 pod inspection failed' >&2; return 1; }
  claim="$(python3 "${_REPLAY_ANCHOR_EVIDENCE}" claim "${replay_namespace}" <<< "${pod}")" || return 1
  pvc="$("${replay_k[@]}" get pvc "${claim}" -o json)" \
    || { echo '[epoch] unavailable: member-0 PVC inspection failed' >&2; return 1; }
  volume="$(python3 "${_REPLAY_ANCHOR_EVIDENCE}" volume "${replay_namespace}" <<< "${pvc}")" || return 1
  pv="$("${replay_k[@]}" get pv "${volume}" -o json)" \
    || { echo '[epoch] unavailable: bound PV inspection failed' >&2; return 1; }
  evidence="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${pod}" "${pvc}" "${pv}")" || return 1
  evidence="$(python3 "${_REPLAY_ANCHOR_EVIDENCE}" anchor "${replay_namespace}" <<< "${evidence}")" || return 1
  IFS=$'\t' read -r ms ts uid volume <<< "${evidence}"
  # --ignore-not-found distinguishes a confirmed absent producer (empty successful read) from
  # permission/connectivity errors. Inspect before changing the ConfigMap.
  deployment="$("${replay_k[@]}" get deploy price-publisher --ignore-not-found -o json)" \
    || { echo '[epoch] unavailable: price-publisher inspection failed; no anchor written' >&2; return 1; }
  producer="$(python3 "${_REPLAY_ANCHOR_EVIDENCE}" deployment "${replay_namespace}" <<< "${deployment}")" || return 1
  REPLAY_ANCHOR_PRODUCER="${producer}"
  # Keep dry-run and apply separate: callers using || disable errexit inside this function,
  # and pipeline status without pipefail can hide dry-run failure behind successful apply.
  yaml="$("${replay_k[@]}" create configmap replay-epoch --from-literal=epochStartMs="${ms}" --dry-run=client -o yaml)" \
    || { echo '[epoch] unavailable: replay-epoch dry-run failed; no anchor written' >&2; return 1; }
  [[ -n "${yaml//[[:space:]]/}" ]] || { echo '[epoch] unavailable: replay-epoch dry-run returned no manifest' >&2; return 1; }
  "${replay_k[@]}" apply -f - <<< "${yaml}" >/dev/null \
    || { echo '[epoch] unavailable: replay-epoch apply failed; stored anchor is unverified' >&2; return 1; }
  REPLAY_ANCHOR_STATUS=stored
  echo "[epoch] storage-derived anchor stored: epochStartMs=${ms}, PVC=${claim}, uid=${uid}, PV=${volume}, creationTimestamp=${ts}, context=${replay_context}, namespace=${replay_namespace}"
  if [[ "${producer}" == present ]]; then
    REPLAY_ANCHOR_PRODUCER=restart-failed
    "${replay_k[@]}" rollout restart deployment/price-publisher >/dev/null \
      || { echo '[epoch] anchor stored; price-publisher restart failed' >&2; return 1; }
    REPLAY_ANCHOR_PRODUCER=rollout-failed
    "${replay_k[@]}" rollout status deployment/price-publisher --timeout=300s >/dev/null \
      || { echo '[epoch] anchor stored; price-publisher rollout failed' >&2; return 1; }
    REPLAY_ANCHOR_PRODUCER=rollout-complete
    echo '[epoch] price-publisher rollout complete; replay position requires a separate health reading'
  else
    echo '[epoch] price-publisher confirmed absent; anchor stored, no producer rollout performed'
  fi
  return 0
}

# Bring-up only. ADR-068 rule 1 shapes every branch: no gcloud, no bucket access, no object —
# the Secret is simply absent or stale, the publisher walks, and /health says why. Never fatal.
fetch_replay_extract_secret() {
  local uri="${TAQ_REPLAY_EXTRACT_URI:-gs://traderx-501015-tick-store/replay/taq-replay-2025-02/extract-v1.json.gz}"
  local tmp="${TMPDIR:-/tmp}/taq-replay-extract.$$.json.gz"
  if ! command -v gcloud >/dev/null 2>&1; then
    echo "[warn] no gcloud on PATH: taq-replay-extract Secret not fetched; equities stay synthetic"
    return 0
  fi
  if ! gcloud storage cp "${uri}" "${tmp}" >/dev/null 2>&1; then
    echo "[warn] could not fetch ${uri}; taq-replay-extract Secret left as-is (equities synthetic"
    echo "       unless an earlier fetch already created it — /health.taqReplay is the reading)"
    return 0
  fi
  # delete+create, NOT dry-run|apply: client-side apply stores the whole object in the
  # last-applied annotation, and the ~240 KB extract blows the 256 KB annotation cap (measured
  # 2026-08-26 — the apply was refused and the old Secret silently stayed operative).
  _rk delete secret taq-replay-extract --ignore-not-found >/dev/null 2>&1
  _rk create secret generic taq-replay-extract --from-file=extract.json.gz="${tmp}" >/dev/null
  rm -f "${tmp}"
  echo "[ok] taq-replay-extract Secret updated from ${uri}"
  return 0
}

# ADR-072's sibling of the above, and deliberately a SEPARATE Secret rather than a second key in
# the same one: a Secret's 1 MiB cap applies to the sum of its values, and the print sample alone
# measured 777 KB (2026-08-26). Sharing an object would make the reference extract's size a
# function of the replayed order RATE, which is a coupling nobody would expect to find.
#
# ADR-068 rule 1 shapes every branch exactly as it does above: no gcloud, no bucket access, no
# object — the Secret is absent, print-replay.js records the sentence on /health.printReplay, the
# publisher publishes prices and submits no orders. Never fatal.
fetch_print_sample_secret() {
  local uri="${TAQ_PRINT_SAMPLE_URI:-gs://traderx-501015-tick-store/replay/taq-replay-2025-02/prints-v1.bin.gz}"
  local tmp="${TMPDIR:-/tmp}/taq-print-sample.$$.bin.gz"
  if ! command -v gcloud >/dev/null 2>&1; then
    echo "[warn] no gcloud on PATH: taq-print-sample Secret not fetched; no replayed order flow"
    return 0
  fi
  if ! gcloud storage cp "${uri}" "${tmp}" >/dev/null 2>&1; then
    echo "[warn] could not fetch ${uri}; taq-print-sample Secret left as-is (no replayed order flow"
    echo "       unless an earlier fetch already created it — /health.printReplay is the reading)"
    return 0
  fi
  # delete+create, NOT dry-run|apply, for the reason recorded above: a client-side apply stores the
  # whole object in the last-applied annotation and blows the 256 KiB cap silently.
  local bytes; bytes="$(wc -c < "${tmp}" | tr -d ' ')"
  _rk delete secret taq-print-sample --ignore-not-found >/dev/null 2>&1
  if ! _rk create secret generic taq-print-sample --from-file=prints.bin.gz="${tmp}" >/dev/null; then
    # CHECKED, unlike its sibling above, because the failure mode here is size: the sample is
    # 777 KB against a 1 MiB per-Secret cap, so a wider universe or a raised replay rate lands on
    # a refusal rather than on a warning. An "[ok] updated" line over a Secret that does not exist
    # is the worst possible reading — the publisher then walks, quietly, having been told it was
    # configured.
    rm -f "${tmp}"
    echo "[warn] taq-print-sample Secret was NOT created (see the error above); no replayed order flow"
    return 0
  fi
  rm -f "${tmp}"
  echo "[ok] taq-print-sample Secret updated from ${uri} (${bytes} bytes, cap 1 MiB)"
  return 0
}
