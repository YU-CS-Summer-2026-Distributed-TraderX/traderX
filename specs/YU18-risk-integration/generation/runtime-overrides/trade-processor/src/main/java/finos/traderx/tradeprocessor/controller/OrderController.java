package finos.traderx.tradeprocessor.controller;

import finos.traderx.messaging.Subscriber;
import finos.traderx.tradeprocessor.OrderFeedHandler;
import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order enumeration over the {@code orderbook} read model (YU13). This is the client-restart /
 * blotter query the project was missing: {@code GET /accounts/{id}/orders} lists an account's OPEN
 * orders (NEW + PARTIALLY_FILLED), which is what lets a client that lost its own records find and
 * cancel what it left resting (the gap that stranded the 107k book). {@code ?status=all} returns
 * every terminal state too, so a cancel/replace/STP proof can assert the row went CANCELED rather
 * than merely vanishing from the open list.
 *
 * <p>YU18 downstream redesign: when the order feed does not persist ({@code
 * traderx.order-feed.persist=false}) the same route answers from the in-memory
 * {@link finos.traderx.tradeprocessor.OrderView}, rendered as the same {@link OrderRow} shape, so
 * the gateway's FIX status source and the console see no difference. The explicit scoped route is
 * SQL-only: it is the historical namespace.
 */
@RestController
public class OrderController {

  // QUEUED (YU17, ADR-069 decision g): a pre-open queued order is OPEN -- accepted, holding its
  // order id, cancellable, and about to be released into the book. Omitting it here would make
  // the console's default view show nothing at all for a halted venue, which is the "reads as
  // missing" half of exactly what STATUS_QUEUED was put in scope to prevent.
  // YU18 (FR-OT27): an untriggered stop and a suspended peg are live orders.
  private static final List<String> OPEN_STATUSES =
      List.of("NEW", "PARTIALLY_FILLED", "QUEUED", "PENDING_TRIGGER", "SUSPENDED");

  private final OrderRepository orderRepository;
  @org.springframework.beans.factory.annotation.Autowired
  private finos.traderx.tradeprocessor.service.RunRegistry runRegistry;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private List<Subscriber<?>> subscribers;

  public OrderController(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  private OrderFeedHandler memoryOnlyFeed() {
    if (subscribers == null) { return null; }
    for (Subscriber<?> s : subscribers) {
      if (s instanceof OrderFeedHandler o && !o.persists()) { return o; }
    }
    return null;
  }

  public ResponseEntity<List<OrderRow>> ordersForAccount(Integer accountId, String status) {
    return ordersForAccount(accountId, status, null);
  }

  @GetMapping("/accounts/{accountId}/orders")
  public ResponseEntity<List<OrderRow>> ordersForAccount(
      @PathVariable Integer accountId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "clientOrderKey", required = false) String clientOrderKey) {
    OrderFeedHandler memory = memoryOnlyFeed();
    if (clientOrderKey != null) {
      return ResponseEntity.ok(byClientKey(memory, accountId, clientOrderKey));
    }
    if (memory != null) {
      boolean all = "all".equalsIgnoreCase(status);
      List<OrderUpdate> found = all ? memory.view().all(accountId) : memory.view().open(accountId);
      List<OrderRow> rows = new ArrayList<>(found.size());
      for (OrderUpdate u : found) { rows.add(OrderFeedHandler.toRow(u)); }
      return ResponseEntity.ok(rows);
    }
    if (runRegistry!=null) {return scopedOrders(runRegistry.activeScope(),accountId,status);}
    List<OrderRow> orders = "all".equalsIgnoreCase(status)
        ? orderRepository.findByAccountId(accountId)
        : orderRepository.findByAccountIdAndStatusIn(accountId, OPEN_STATUSES);
    return ResponseEntity.ok(orders);
  }

  /**
   * ADR-093: the order (any state) booked under the client's RAW key: its own u64 (binary; a sequenced stream's
   * {@code streamId:u32 | seq:u32}) or the gateway's hash of {@code CompID SOH ClOrdID} (FIX), as an unsigned decimal.
   * The account is mixed in here as the gateway mixes it in. Empty means no order this read model holds was booked
   * under it; that is the answer a ref-less DUPLICATE (ADR-090 FR-XOD-03) is reconciled with.
   */
  private List<OrderRow> byClientKey(OrderFeedHandler memory, Integer accountId, String rawKey) {
    final String scoped;
    try {
      scoped = Long.toUnsignedString(finos.traderx.tradeprocessor.OrderView.scopeKey(Long.parseUnsignedLong(rawKey), accountId));
    } catch (NumberFormatException ex) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "clientOrderKey must be an unsigned 64-bit decimal");
    }
    if (memory != null) {
      OrderUpdate u = memory.view().byClientKey(accountId, scoped);
      return u == null ? List.of() : List.of(OrderFeedHandler.toRow(u));
    }
    return runRegistry != null
        ? orderRepository.findByProjectionScopeAndAccountIdAndClientOrderKey(runRegistry.activeScope(), accountId, scoped)
        : orderRepository.findByAccountIdAndClientOrderKey(accountId, scoped);
  }

  @GetMapping("/v2/projections/{scope}/accounts/{accountId}/orders")
  public ResponseEntity<List<OrderRow>> scopedOrders(@PathVariable String scope,
      @PathVariable Integer accountId,@RequestParam(name="status",required=false) String status) {
    try {runRegistry.scope(scope);}
    catch(IllegalArgumentException ex) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"unknown projection scope");
    }
    return ResponseEntity.ok("all".equalsIgnoreCase(status)
        ? orderRepository.findByProjectionScopeAndAccountId(scope,accountId)
        : orderRepository.findByProjectionScopeAndAccountIdAndStatusIn(scope,accountId,OPEN_STATUSES));
  }
}
