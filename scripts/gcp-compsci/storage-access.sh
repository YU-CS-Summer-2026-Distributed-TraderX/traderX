#!/usr/bin/env bash
# Prepare scoped access. This does not create HMAC keys, expose credentials,
# start a cluster, or grant storage privileges to its node account.
set -euo pipefail
PROJECT=project-compsci-484414
ACCOUNT=aseif@mail.yu.edu
WRITER="traderx-risk-extract@${PROJECT}.iam.gserviceaccount.com"
G=(gcloud --project "$PROJECT" --account "$ACCOUNT")
if [[ "${1:-}" != --apply || $# != 1 ]]; then
  echo 'Usage: storage-access.sh --apply'
  echo 'Creates dedicated extract writer; grants Creator+Viewer on extracts only.'
  echo 'Grants console Viewer on extracts and taq-reader Viewer on raw TAQ via Workload Identity.'
  echo 'No journal restore/backup privileges: that profile remains separate.'
  exit 0
fi
number="$("${G[@]}" projects describe "$PROJECT" --format='value(projectNumber)')"
[[ "$number" == 918355398369 ]] || { echo 'Unexpected project identity' >&2; exit 1; }
existing="$("${G[@]}" iam service-accounts list --filter="email=$WRITER" --format='value(email)')"
if [[ -z "$existing" ]]; then
  "${G[@]}" iam service-accounts create traderx-risk-extract --display-name='TraderX extract writer'
fi
for role in roles/storage.objectCreator roles/storage.objectViewer; do
  "${G[@]}" storage buckets add-iam-policy-binding "gs://${PROJECT}-traderx-risk-extracts" \
    --member="serviceAccount:$WRITER" --role="$role"
done
principal="principal://iam.googleapis.com/projects/${number}/locations/global/workloadIdentityPools/${PROJECT}.svc.id.goog/subject/ns/traderx/sa"
"${G[@]}" storage buckets add-iam-policy-binding "gs://${PROJECT}-traderx-risk-extracts" \
  --member="$principal/web-front-end-console" --role=roles/storage.objectViewer
"${G[@]}" storage buckets add-iam-policy-binding "gs://${PROJECT}-traderx-taq-raw" \
  --member="$principal/taq-reader" --role=roles/storage.objectViewer
echo 'Scoped bindings applied. Existing risk-extract Java writer still requires a private HMAC key.'
