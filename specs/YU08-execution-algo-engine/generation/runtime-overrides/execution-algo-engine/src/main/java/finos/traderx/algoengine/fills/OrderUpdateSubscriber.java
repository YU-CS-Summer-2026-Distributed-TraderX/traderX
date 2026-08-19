package finos.traderx.algoengine.fills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.algoengine.service.AlgoOrderService;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * research.md Decision 5: subscribes to the order-lifecycle broadcast and correlates each event to
 * a child order this engine submitted, via {@link AlgoOrderService#onOrderUpdate}. No new subject,
 * no publisher change.
 *
 * <p><b>Two tiers publish that broadcast, and this subscriber has to speak both</b> (the defect
 * `algo-engine-never-sees-child-fills-on-the-cluster-tier`, fixed here). On the single-BLP tier
 * {@code NatsBridgeHandler} publishes each order twice — once on {@code /accounts/<id>/orders} and
 * once on bare {@code /orders} — with the payload's id in {@code orderId}. On the Aeron cluster
 * tier the leader-side {@code OrderNatsPublisher} publishes on bare {@code /orders} ONLY, with the
 * id in {@code id} and epoch-qualified as {@code <epoch>-<orderRef>}. The original filter accepted
 * only {@code /accounts/<id>/orders} and read only {@code orderId}, so on the cluster tier every
 * child fill was discarded twice over and every parent order stayed RUNNING for ever.
 *
 * <p>These subjects use a literal {@code /}, not NATS's {@code .}-delimited token hierarchy, so the
 * whole thing is one token from NATS's perspective and {@code *} cannot be embedded inside it as a
 * wildcard (jnats's subject validator rejects {@code "/accounts/*}{@code /orders"} outright:
 * "Subject wildcard improperly placed"). Instead this subscribes to NATS's full match-all
 * ({@code ">"}) and filters client-side, at the cost of also receiving every other subject on the
 * connection (pricing ticks, trades, positions) and discarding non-matches.
 */
@Component
public class OrderUpdateSubscriber {
  private static final Logger log = LoggerFactory.getLogger(OrderUpdateSubscriber.class);
  private static final String CATCH_ALL_SUBJECT = ">";

  /** Selects exactly {@code /orders} and {@code /accounts/<id>/orders} — the two order-lifecycle
   * subject forms in this system, and nothing else that is published on this bus
   * ({@code /accounts/<id>/trades}, {@code /accounts/<id>/positions}, {@code /trades},
   * {@code /prices/<ticker>}). Accepting BOTH rather than swapping one hardcode for the other is
   * deliberate: which forms exist is a property of the tier, not of this engine, and a subscriber
   * that goes deaf when the venue changes shape is precisely the defect being fixed. The
   * single-BLP tier's double publish therefore delivers each update here twice; that is harmless
   * because every mutation downstream is a full field replacement, not an increment
   * ({@code AlgoOrderState}), and a completed parent is not re-completed. */
  private static final String ORDERS_SUBJECT_SUFFIX = "/orders";

  /** {@code <epoch>-<orderRef>}, the cluster bridge's epoch-qualified read-model key. Both halves
   * are digits by contract — {@code CLUSTER_EPOCH} is a counter defaulting to {@code 1}
   * ({@code ClusterNodeMain}, {@code YU13/system/messaging-subject-map.md}) and {@code orderRef} is
   * the engine's order sequence — and the pattern is anchored that narrowly ON PURPOSE, so that
   * every other id shape on this bus falls through untouched rather than being rewritten by a rule
   * that was only ever meant for one publisher. In particular the single-BLP tier's
   * {@code ord-013-0042} does not match, so fixing the cluster tier cannot break the tier that
   * already worked. If {@code CLUSTER_EPOCH} is ever set to something non-numeric this stops
   * matching and the join goes quiet again — the rig check for this fix therefore reads the
   * member's actual {@code CLUSTER_EPOCH}, rather than trusting the default. */
  private static final Pattern EPOCH_QUALIFIED_ID = Pattern.compile("\\d+-(\\d+)");

  private final String natsAddress;
  private final AlgoOrderService algoOrderService;
  private final ObjectMapper mapper = new ObjectMapper();
  private volatile Connection connection;

  public OrderUpdateSubscriber(
      @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
      AlgoOrderService algoOrderService) {
    this.natsAddress = natsAddress;
    this.algoOrderService = algoOrderService;
  }

  @PostConstruct
  void start() throws Exception {
    connection = Nats.connect(new Options.Builder()
        .server(natsAddress)
        .connectionTimeout(Duration.ofSeconds(10))
        .maxReconnects(-1)
        .build());
    connection.createDispatcher(this::onMessage).subscribe(CATCH_ALL_SUBJECT);
    log.info("subscribed to {} (filtering for subjects ending {}) for child-order fill tracking",
        CATCH_ALL_SUBJECT, ORDERS_SUBJECT_SUFFIX);
  }

  /** True when the fill-tracking NATS connection is up. Feeds the readiness health group. */
  public boolean healthy() {
    Connection conn = connection;
    return conn != null && conn.getStatus() == Connection.Status.CONNECTED;
  }

  void onMessage(io.nats.client.Message msg) {
    String subject = msg.getSubject();
    if (subject == null || !subject.endsWith(ORDERS_SUBJECT_SUFFIX)) {
      return;
    }
    try {
      JsonNode body = mapper.readTree(msg.getData());
      handle(body);
    } catch (Exception ex) {
      log.warn("failed to process order-update message: {}", ex.toString());
    }
  }

  /** Extracted from {@link #onMessage} so the correlation logic is testable without a live NATS
   * connection. Every message on this bus is wrapped in a {@code NatsEnvelope}
   * ({@code topic}/{@code payload}/{@code date}/{@code from}/{@code type}); the order fields live
   * under {@code payload}, not at the envelope's top level. */
  void handle(JsonNode envelope) {
    JsonNode body = envelope.hasNonNull("payload") ? envelope.get("payload") : envelope;
    // "orderId" is the single-BLP OrderResponse's field; "id" is the cluster bridge's OrderUpdate.
    JsonNode id = body.hasNonNull("orderId") ? body.get("orderId")
        : body.hasNonNull("id") ? body.get("id") : null;
    if (id == null) {
      return;
    }
    String orderId = childOrderId(id.asText());
    Integer remainingQuantity = body.hasNonNull("remainingQuantity") ? body.get("remainingQuantity").asInt() : null;
    BigDecimal lastExecutionPrice = body.hasNonNull("lastExecutionPrice")
        ? new BigDecimal(body.get("lastExecutionPrice").asText())
        : null;
    // The cluster bridge renders "no execution yet" (Px.NONE) as 0.000000 rather than omitting the
    // field, so a resting child would otherwise be reported as having executed at zero. No order
    // executes at zero; the single-BLP tier sends null here and is unaffected.
    if (lastExecutionPrice != null && lastExecutionPrice.signum() == 0) {
      lastExecutionPrice = null;
    }
    algoOrderService.onOrderUpdate(orderId, remainingQuantity, lastExecutionPrice);
  }

  /**
   * The published id, reduced to the form this engine stored when it submitted the child.
   *
   * <p>The cluster bridge qualifies its ids with the cluster epoch ({@code 1-2549}) because its
   * read-model key must not collide across incarnations; the gateway's {@code POST /orders}
   * response carries the bare {@code orderRef} ({@code 2549}), which is what
   * {@code OrderMatcherClient} returns and what the child index is keyed on. The join is therefore
   * normalised HERE, on comparison, rather than by storing the qualified form at submission:
   * the engine never learns the epoch (it is a member-side {@code CLUSTER_EPOCH} env the gateway
   * response does not carry), so storing it would mean plumbing a second copy of the cluster's
   * epoch into this service and keeping the two in lockstep — and the next epoch bump that missed
   * that copy would silently break this join again, exactly as the subject mismatch did. Stripping
   * on comparison is epoch-agnostic by construction: this engine records no epoch at all, so there
   * is nothing to keep in step.
   *
   * <p>ponytail: the cost of not recording the epoch is that two children with the same orderRef
   * in different incarnations collapse to one key. That needs a parent to outlive an epoch bump,
   * which wipes the rig's state; parents live minutes. If algo parents ever have to survive an
   * incarnation, the fix is for the gateway to return the qualified id, not for this to guess.
   */
  static String childOrderId(String publishedId) {
    Matcher m = EPOCH_QUALIFIED_ID.matcher(publishedId);
    return m.matches() ? m.group(1) : publishedId;
  }

  @PreDestroy
  public void close() {
    Connection conn = connection;
    if (conn == null) {
      return;
    }
    try {
      conn.close();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
