# Provisional local HTTP mock contract

Status: implemented for local transport/recovery testing, 2026-09-15. **Not agreed with Alex and not his API.** The financial request/result drafts in `proposed/` remain separate and unused by this transport.

## Workload and state

Wire schema: `traderx.http-mock.draft-1`. Profile equals the original mock profile except adapter=`http-transport-mock-draft-1`: calculations=[transport-check], marketInputs=NOT_SUPPLIED, usableForRisk=false.

Workload key uses the existing bundle canonicalization over `{bundleId, profile}`. It is the coordinator job ID. This fixed test profile contains no pricing/precision/scenario choices; the future real profile must include all agreed calculation-affecting inputs. Changing transport endpoint alone does not change workload identity. Use the same durable worker store after a restart; a different empty store cannot know another worker's accepted jobs.

A coordinator state with existing jobs is restricted to their adapter profile. Existing in-process mock identities and database schema remain unchanged. Local attempt IDs and worker attempt IDs are separate, both retained. The worker deduplicates by workload key rather than client attempt ID. No exactly-once computation guarantee is claimed for a real external engine or a crash during computation.

## Endpoints

| Request | Response |
|---|---|
| GET /capabilities | 200: `{schema, profile, pricing:false}` with exact known values |
| POST /risk/jobs | 202 or 200: workload envelope; body below |
| GET /risk/results/by-workload/{workloadKey} | 200: workload envelope; 404: unknown workload |

Submission body: exactly `{schema, workloadKey, profile, clientAttemptId, bundle}`. The bundle contains its unchanged v1 manifest and the original positions/contracts CSV bytes encoded as base64. There are no file URIs for the fake worker to dereference. The receiver validates the decoded data against the manifest and workload key before durably accepting it.

Envelope: exactly `{schema, workloadKey, profile, workerAttemptId, status}`, with status QUEUED or RUNNING. MOCK_COMPLETE adds `result` and `resultSha256`; FAILED adds `reason`. The result is the existing `traderx.mock-result.v1` object, and resultSha256 covers its canonical JSON bytes. The receiver's normal identity, currency, coverage, null-NPV and empty-Greeks checks still apply. HTTP 200 alone is never completion evidence.

The fake worker publishes its accepted request and worker identity privately before returning. It publishes the complete mock result atomically and can recover it after process restart. For this fixture implementation, pending accepted work advances during submit/lookup; this is not a model for an overnight execution scheduler. One process owns a filesystem lock for the worker store, and a thread lock serializes workload access. No cloud store or distributed lock is implemented.

## Recovery behavior

The adapter looks up the workload before submission. Only an explicit 404 permits POST. A completed result from lookup is accepted without reposting. Capability validation is required before a new submission. Polls inspect the same workload key.

A timeout, disconnected response, HTTP 429/5xx or a still-running workload leaves the local job and attempt RUNNING with a REMOTE_PENDING explanation. The next coordinator run reuses that attempt and looks up the workload again. A missing result after an earlier accepted poll does not trigger a second POST within that run. Durable worker lookup and submission deduplication are required for recovery across runs.

If the coordinator dies after remote publication, it retrieves that result on restart. If it dies after local publication, local validation completes the attempt without contacting the worker. Malformed replies, identity/hash mismatches, unauthorized financial claims and declared failures produce FAILED; explicit retry preserves earlier attempts. This test protocol does not implement an API for forcing a new remote benchmark attempt after a terminal result.

Local HTTP result directories contain `results.json` and `transport.json`. The latter retains the envelope without its embedded result, including workerAttemptId and resultSha256. The coordinator's accepted hash covers both files; changing either invalidates status. This is separate from the in-process mock's one-file result directory. All outputs remain explicitly unusable for risk.

## Local limits and security boundary

Only `http://127.0.0.1:PORT` is accepted. Proxies and redirects are disabled; the worker binds only to loopback. This is an unauthenticated local development tool, not a service to expose to other users or networks. Inputs/state/results stay outside Git in private directories. The worker does not log request bodies.

The test transport has a 4 MiB JSON body ceiling, including base64 overhead. Default socket timeout is 2 seconds, with at most three lookup polls at 0.1-second intervals per execute call. These are development bounds, not production tuning or a hard end-to-end job deadline. Pending work resumes only on another explicit run. No background coordinator scheduler is added.

## Still needed for Alex

Agree the actual wire schemas, capability matrix, authentication/TLS, private artifact references, all calculation-affecting workload fields, remote attempt/benchmark policy, failure codes and per-calculation coverage. Add the versioned market/reference packages and financial result validator. Then implement a new real adapter/profile against his service and exchange shared acceptance fixtures. The local fake must never silently substitute for that engine.

Bundle v2 is explicitly rejected by this adapter before network access. Its new instrument-terms artifact cannot be transported by the frozen two-CSV draft. Use the local mock to exercise v2 pending a newly agreed HTTP contract.
