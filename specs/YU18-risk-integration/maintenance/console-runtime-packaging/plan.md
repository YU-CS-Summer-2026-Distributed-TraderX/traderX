# Console runtime packaging plan

1. Confirm the root Dockerfile owns runtime packaging and enumerate the server import closure.
2. Add the missing module to the explicit runtime copy list; preserve application semantics.
3. Run the installed-image regression against the original immutable image and retain its missing-module failure.
4. Build a unique local linux/amd64 image and run the same regression against its resolved identity.
5. Deliver source, image identity, build log and endpoint/module evidence for coordinator review. The live owner alone may load and roll a reviewed candidate. Human approval is required before source integration.

The source correction, regression and documentation are isolated on `codex/lr05-console-packaging`. Shared maintenance indexes and LR acceptance records remain coordinator-owned. No generated runtime override is changed.

Platform follow-on extends the explicitly reviewed clean fcba84f4 dependency. Replace the amd64-only base with the official pinned multi-platform stable base; retain native Node and kubectl package installation. Parse installed executable and PT_INTERP loader ELF headers from a never-started owned container. Reject mismatched labels before startup, then run the installed-image smoke and offline bridge CLI checks. Preserve earlier successful amd64 and failed deployment evidence separately. New source remains a candidate until direct human integration approval.
