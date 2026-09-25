# Combined trader workspace

Status: selected UI connected to the local rig; production authentication and full feature migration remain open.
Owner: Codex coordinator. User selected Codex login and Claude branding, then authorized local rig connection and Demo console navigation on 2026-09-25.

Operative source: `web-front-end-console-combined-prototype/`; imported console components and API in `web-front-end-console/`. The shared console server retains authorization and gains loopback binding, local-only cloud-read refusal and WebSocket reset handling. No runtime override of this server exists under specs. Generation/deployment inputs remain state-level; no rig images were rebuilt or rolled.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md) and the UI README. Original UI and both independent design candidates remain available.
