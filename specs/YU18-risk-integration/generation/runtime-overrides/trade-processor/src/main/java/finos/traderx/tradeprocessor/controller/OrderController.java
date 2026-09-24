package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.repository.OrderRepository;
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

  public OrderController(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @GetMapping("/accounts/{accountId}/orders")
  public ResponseEntity<List<OrderRow>> ordersForAccount(
      @PathVariable Integer accountId,
      @RequestParam(name = "status", required = false) String status) {
    if (runRegistry!=null) {return scopedOrders(runRegistry.activeScope(),accountId,status);}
    List<OrderRow> orders = "all".equalsIgnoreCase(status)
        ? orderRepository.findByAccountId(accountId)
        : orderRepository.findByAccountIdAndStatusIn(accountId, OPEN_STATUSES);
    return ResponseEntity.ok(orders);
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
