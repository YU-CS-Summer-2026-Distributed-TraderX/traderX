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

  private static final List<String> OPEN_STATUSES = List.of("NEW", "PARTIALLY_FILLED");

  private final OrderRepository orderRepository;

  public OrderController(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @GetMapping("/accounts/{accountId}/orders")
  public ResponseEntity<List<OrderRow>> ordersForAccount(
      @PathVariable Integer accountId,
      @RequestParam(name = "status", required = false) String status) {
    List<OrderRow> orders = "all".equalsIgnoreCase(status)
        ? orderRepository.findByAccountId(accountId)
        : orderRepository.findByAccountIdAndStatusIn(accountId, OPEN_STATUSES);
    return ResponseEntity.ok(orders);
  }
}
