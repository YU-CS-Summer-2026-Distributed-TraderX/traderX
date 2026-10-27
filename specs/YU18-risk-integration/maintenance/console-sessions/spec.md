# Console sessions specification

## Requirements

- FR-CS-01: Each sign-in SHALL get its own session. The signed admin token SHALL carry a random
  per-login session id, which `/auth/me` and `/auth/login` return and the masthead shows.
  Old-format cookies SHALL be refused.
- FR-CS-02: A hosted Desk session SHALL carry the desk user's own name, after the password check against
  `DESK_USERS_FILE`.
- FR-CS-03: Desk sign-out SHALL accept an empty body and clear both cookies.
- FR-CS-04: Every `/auth/*` response SHALL be `Cache-Control: no-store`.
- FR-CS-05: The S1 gateway routes proxied by the console SHALL be on the admin gate. A signed-out request
  SHALL get `401 admin_auth_required`. A signed-in request SHALL carry the console's JWT.
- NFR-CS-01: `AUTH_SESSION_SECRET` and `ADMIN_PASSWORD_HASH` come from `auth-secrets`. Replicas share
  one session secret.

## Success Criteria

- SC-CS-01: `sessions.test.mjs`: two cookie jars get independent console and desk sessions, and signing
  one out leaves the other signed in.
- SC-CS-02: `risk-control-proxy.test.mjs` covers all seven S1 routes, signed out and signed in.
