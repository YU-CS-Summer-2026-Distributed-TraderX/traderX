package finos.traderx.algoengine.api;

import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.service.AlgoOrderService;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** FR-AE01/FR-AE06, research.md Decision 8: REST-only parent-order ingress, no front-end panel. */
@RestController
public class AlgoOrderController {
  private final AlgoOrderService algoOrderService;

  public AlgoOrderController(AlgoOrderService algoOrderService) {
    this.algoOrderService = algoOrderService;
  }

  @PostMapping("/algo/orders")
  @ResponseStatus(HttpStatus.CREATED)
  public ParentOrder createParentOrder(@RequestBody CreateParentOrderRequest request) throws Exception {
    return algoOrderService.create(
        request.getAccountId(),
        request.getSecurity(),
        request.getSide(),
        request.getQuantity(),
        request.getAlgoType(),
        request.getDurationSeconds(),
        request.getBucketSeconds());
  }

  @GetMapping("/algo/orders/{parentOrderId}")
  public ParentOrder getParentOrder(@PathVariable("parentOrderId") String parentOrderId) {
    ParentOrder order = algoOrderService.get(parentOrderId);
    if (order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown parentOrderId " + parentOrderId);
    }
    return order;
  }

  @GetMapping("/algo/orders")
  public Collection<ParentOrder> listParentOrders() {
    return algoOrderService.all();
  }
}
