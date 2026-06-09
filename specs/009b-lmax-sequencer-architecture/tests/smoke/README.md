# Smoke Tests: 009b-lmax-sequencer-architecture

- Primary smoke script: `scripts/test-state-009b-lmax-sequencer-architecture.sh`
- Focused messaging smoke: `scripts/test-messaging-009b-lmax-sequencer-architecture.sh`
- No-GC gate: `pipeline/validate-no-gc-conformance.sh` (CI; runnable locally)

Planned smoke checks:

- Runtime starts cleanly with the inherited `009` stack; hot-path node becomes ready only after
  snapshot load + journal replay + JIT warm-up.
- Parity journeys (must behave identically to `009` — SC-09B03):
  - create order through ingress API; query account-filtered open orders; user cancel
  - admin force-fill via admin endpoint
  - in-the-money order auto-fills with the `009` quantity policy (`<1000 => full`, `>=1000 => half
    rounded up`) — now on event arrival, not on a poll tick
  - filled order appears as a trade and updates the account position
  - market trade via the trade ticket books and updates positions through the sequenced path
- Realtime push checks (SC-09B09):
  - `/accounts/{accountId}/orders` and `/orders` receive lifecycle events for create/auto-fill/cancel/
    force-fill with `009`-identical payload shapes
  - trade/position streams update on fills; no polling loops in Angular views
  - API explorer / pub-sub inspector surface from `009` still resolves (re-validates SC-01312/SC-01313)
- Hot-path metrics present and sane in `/metrics`:
  - `traderx_disruptor_input_remaining_capacity`, `traderx_input_seq_lag`,
    `traderx_journal_write_latency_seconds`, `traderx_blp_event_latency_seconds`,
    `traderx_output_publish_latency_seconds`, `traderx_projector_lag_seq`
  - `traderx_hotpath_alloc_bytes_total` ~0 in steady state
  - retained `009` families: `traderx_orders_open_total`, `traderx_orders_unfilled_total`,
    `traderx_order_events_total`, `traderx_order_match_latency_seconds` (non-zero-filled)
- Event-sourcing checks:
  - determinism: captured journal replayed in a clean process yields identical state and output
    (SC-09B06)
  - recovery: restart hot-path node; state restored to last journaled sequence within the window
    (SC-09B08)
  - rebuild: drop read-model, re-project from journal, assert identical rows (SC-09B11)
- Decoupling checks (SC-09B10, SC-09B15):
  - DB stopped: matching continues; projector lag grows and drains after DB restart
  - NATS stopped: matching continues; UI streams resume on reconnect
  - ack latency measured independent of DB/NATS latency
- Failover check (SC-09B12): demo profile exercises the replication contract in loopback/stub mode;
  perf profile kills the leader and asserts follower promotion at the same sequence with no journaled
  input lost.
- Gates:
  - Epsilon-GC allocation gate passes (SC-09B05); banned-API static check passes (SC-09B13)
  - penny-parity fixture passes (SC-09B04)
- Grafana has the hot-path dashboards provisioned (ring headroom, sequence lag, latency percentiles,
  projector lag, allocation alert, GC-pause panel).
