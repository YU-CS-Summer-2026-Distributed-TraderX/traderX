# RI-02 — Containerize the risk engine

Updated: 2026-09-18. Status: spec draft exists; implementation unverified. Owner: unassigned; proposed packaging by Yaakov, engine behavior by Alex. Dependencies: current engine review and agreed checkout ownership.

Authoritative draft is outside TraderX: `/Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec/`. Do not copy its financial/API contracts into a second implementation here.

- [ ] Inspect engine 992db30 (or newer actual HEAD), preserve edits and agree implementation branch with Alex.
- [ ] Build a reproducible Linux CPU image with pinned dependencies and narrow build context.
- [ ] Document loopback-only startup, input/result mounts and startup validation.
- [ ] Verify identical host/container requests and outputs for both supported APIs.
- [ ] Exercise shutdown, child-process cleanup and interrupted execution; record limitations.
- [ ] Record image digest, platform, engine/spec revisions, resource use and executable acceptance evidence.

Next: reconcile the existing pack with current source. First milestone is one local container, not Kubernetes or distributed exactly-once processing. Multiple-portfolio workers belong to RI-03. Done means built and exercised, not merely a Dockerfile present.
