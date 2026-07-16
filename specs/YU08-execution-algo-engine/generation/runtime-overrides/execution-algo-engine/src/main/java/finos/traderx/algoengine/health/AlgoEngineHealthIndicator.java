package finos.traderx.algoengine.health;

import finos.traderx.algoengine.eventstore.AlgoEventStore;
import finos.traderx.algoengine.fills.OrderUpdateSubscriber;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Workload health for the algo engine: the pod is only ready when both NATS legs are live —
 * the JetStream event store (parent-order state) and the fill subscriber (child-order fills).
 * Wired into the readiness group (application.properties), NOT liveness: a broker outage must
 * unroute the pod, not restart-loop it. Before this existed the engine shipped no probes at all
 * and a dead JVM stayed "Ready" (live incident, 2026-07-15).
 */
@Component("algoEngine")
public class AlgoEngineHealthIndicator implements HealthIndicator {

  private final AlgoEventStore eventStore;
  private final OrderUpdateSubscriber orderUpdateSubscriber;

  public AlgoEngineHealthIndicator(AlgoEventStore eventStore, OrderUpdateSubscriber orderUpdateSubscriber) {
    this.eventStore = eventStore;
    this.orderUpdateSubscriber = orderUpdateSubscriber;
  }

  @Override
  public Health health() {
    boolean store = eventStore.healthy();
    boolean fills = orderUpdateSubscriber.healthy();
    Health.Builder b = (store && fills) ? Health.up() : Health.down();
    return b.withDetail("eventStore", store ? "connected" : "down")
        .withDetail("fillSubscriber", fills ? "connected" : "down")
        .build();
  }
}
