# Every generated source link points at FINOS, and none of the YU branches exist

**Status:** open
**Found:** 2026-08-27 by the main/Pages lane, scoped precisely and confirmed by the coordinator
**Affects:** the public docs site — all 17 YU learning pages

The generated `docs/learning/state-*.md` pages carry a "getting started / view the source" link per
state. **For the 17 YU states, every one of those links 404s, for two independent reasons — either
alone is sufficient, so fixing one changes nothing.**

## Reason 1: the branches were never pushed

    git ls-remote finos   code/generated-state-YU*   -> 0
    git ls-remote origin  code/generated-state-YU*   -> 0

The 14 numbered upstream states' `code/generated-state-*` branches **do** exist on both remotes, so
their links are fine. It is exactly the 17 YU states that have no target.

## Reason 2: the base is hardcoded to the wrong repository

`pipeline/generate-state-docs-from-catalog.mjs:46`

    const repoWebBase = 'https://github.com/finos/traderX'

**Not env-overridable.** `TRADERX_SOURCE_AUTHORING_BRANCH` beside it is read from the environment;
`repoWebBase` is a constant. `origin` is `YU-CS-Summer-2026-Distributed-TraderX/traderX`, so **even if
the YU branches were pushed to origin, every generated link would still point at FINOS**, where they
will never exist.

The same applies to `DOCUSAURUS_URL` in main's Pages workflow: the built `sitemap.xml` is entirely
`https://traderx.finos.org/…`, so the site advertises canonical URLs on a domain it is not served
from.

**`TRADERX_REPO_WEB_BASE` does not exist.** It has been cited in at least two handover documents,
including a chip brief written by this coordinator, as the env var that controls this. It is
referenced nowhere in `pipeline/` or `.github/`. Anyone told to "just set it" will find nothing.

## Why this survived

`onBrokenLinks: 'ignore'` hides broken *routes*, and these are absolute external URLs, which no
Docusaurus check validates at all. A green build has never had anything to say about them.

## Fix

1. Make `repoWebBase` read `process.env.TRADERX_REPO_WEB_BASE` with the FINOS URL as the default —
   which also makes the widely-repeated claim about that variable true.
2. Decide whether the YU generated-code branches get pushed. If they will not be, the links should
   not be generated for those states at all; `validate-state-doc-consistency.sh` currently *requires*
   one per `implemented` state, so the gate is enforcing a link that cannot resolve.

**Do not paper over it by relaxing the gate alone** — that turns a visible 404 into a silent absence.
