# Console sessions

Status: integrated, 2026-10-26 (de5e5d53, 12a0a21b).

Source: the root-owned console `web-front-end-console/` (`server.mjs`, `desk-session.mjs`,
`src/app/api.ts`, `src/app/sign-in.ts`, `sessions.test.mjs`, `risk-control-proxy.test.mjs`). The console is
not rendered from a runtime override. `scripts/gcp-compsci/build-images.py` binds its source tree hash
into the image derivation, and `admit.py` refuses the image if that tree changes (CompSci admission,
"root console source").

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md). The signing rule is FR-ERH-C1 in
[engine-review hardening](../engine-review-hardening/spec.md).
