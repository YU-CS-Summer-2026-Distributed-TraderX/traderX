# Risk-engine integration spec kit

**Status: proposal for Yaakov and Alex to review. Not an accepted replacement contract.**

This is a portable feature pack for an engine integration boundary, with TraderX as its first consumer. Its location in TraderX is a drafting location, not a requirement that the engine depend on TraderX. No new TraderX runtime state is introduced by this documentation and test harness.

Read in order:

1. [spec.md](spec.md): observable requirements and scope.
2. [architecture.md](architecture.md): responsibilities and the engine reuse audit.
3. [contracts.md](contracts.md): compatibility and lifecycle rules.
4. [traderx-profile.md](traderx-profile.md): the first consumer's economics and existing contracts.
5. [acceptance.md](acceptance.md): evidence required for each requirement.
6. [plan.md](plan.md): implementation sequence and open decisions.
7. [tasks.md](tasks.md): explicit completion criteria.

The pack separates proposed requirements from observed implementation. Alex owns canonical engine components; both sides agree on the boundary. After agreement, select one authoritative repository/revision for the shared pack. Consumers pin that revision; they do not maintain independently edited copies.

AI implementation workflow: read the pack, map existing components, propose the smallest changes, implement one task, and attach evidence to its requirement IDs. Do not add a pricer to the adapter because it is easier than finding the engine implementation. A missing engine capability belongs in an engine task.

## Local acceptance starter

The initial executable cases target Alex's current EOD HTTP interface through FastAPI TestClient. They are not portable transport certification or a complete acceptance suite. They use only synthetic fixtures and temporary storage; they do not start a network server or modify Alex's checkout.

From this directory, using an external Python environment with Alex's numerical dependencies plus FastAPI/httpx:

```sh
PYTHONDONTWRITEBYTECODE=1 /path/to/review-env/bin/python acceptance/check_current_api.py --engine /path/to/JAX_Risk_Engine
```

Expected behavior is asserted normally: known defects FAIL, never xfail or silently skip. The initial target is commit `e4ca50bd3f5179e230e589275c3a09a5a88422c8`. Results must record the tested commit. See [acceptance.md](acceptance.md) for remaining cases; a green starter suite does not certify the entire pack.
