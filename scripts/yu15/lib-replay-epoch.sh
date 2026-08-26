#!/usr/bin/env bash
# lib-replay-epoch.sh — ADR-070's two bring-up duties, shared by start-cluster-kind.sh and
# run-proofs.sh (rebuild_fresh_epoch):
#
#   stamp_replay_epoch          stamp the replay-epoch ConfigMap with the CURRENT epoch's mint
#                               instant and roll price-publisher so it reads the new anchor.
#   fetch_replay_extract_secret fetch the resampled extract from the bucket into the
#                               taq-replay-extract Secret (bring-up only; epochs never refetch).
#   fetch_print_sample_secret   the ADR-072 sibling: the sampled PRINTS that become replayed order
#                               flow, into the taq-print-sample Secret. Same posture, same prefix.
#
# THE ANCHOR IS DERIVED, NEVER INVENTED: epochStartMs comes from the member-0 PVC's
# creationTimestamp, and rebuild_fresh_epoch wipes and recreates the PVCs at every mint, so that
# timestamp IS the mint instant. Deriving it makes restamping idempotent — calling this twice on
# the same epoch writes the same value, so no caller has to know whether it is the first — and
# means a stamp can never disagree with the epoch it describes. (Stamping "now" instead would
# drift by however long the rollout waits took, and a RE-stamp would silently rewind the tape.)
#
# Both functions resolve the sourcing script's ${K} kubectl prefix exactly as
# lib-consensus-readings.sh does: string or array, either works.
_rk() {
  if [[ "$(declare -p K 2>/dev/null)" == "declare -"[aA]* ]]; then "${K[@]}" "$@"; else ${K} "$@"; fi
}

stamp_replay_epoch() {
  local ts ms
  ts="$(_rk get pvc data-order-matcher-cluster-0 -o jsonpath='{.metadata.creationTimestamp}' 2>/dev/null)"
  if [[ -z "${ts}" ]]; then
    echo "[epoch] no member-0 PVC to derive the replay epoch from; leaving replay-epoch unstamped"
    return 0
  fi
  ms="$(python3 -c 'import datetime,sys
print(int(datetime.datetime.strptime(sys.argv[1],"%Y-%m-%dT%H:%M:%SZ")
      .replace(tzinfo=datetime.timezone.utc).timestamp()*1000))' "${ts}")" || return 1
  _rk create configmap replay-epoch --from-literal=epochStartMs="${ms}" \
    --dry-run=client -o yaml | _rk apply -f - >/dev/null
  echo "[epoch] replay-epoch stamped: epochStartMs=${ms} (${ts}, from the member-0 PVC)"
  # env vars from a ConfigMap are read at container start, so the stamp is invisible until the
  # publisher rolls. Absent deployment (a tier without the EOD chain) is not an error.
  if _rk get deploy price-publisher >/dev/null 2>&1; then
    _rk rollout restart deployment/price-publisher >/dev/null
    _rk rollout status deployment/price-publisher --timeout=300s >/dev/null \
      || { echo "[fail] price-publisher did not come back after the replay-epoch stamp"; return 1; }
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
