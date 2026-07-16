package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/")
public class OrderController {
    private final OrderMatcherService orderMatcherService;

    public OrderController(OrderMatcherService orderMatcherService) {
        this.orderMatcherService = orderMatcherService;
    }

    @GetMapping("/orders")
    public List<OrderResponse> listOrders(
        @RequestParam(value = "status", required = false, defaultValue = "open") String status,
        @RequestParam(value = "accountId", required = false) Integer accountId
    ) {
        return orderMatcherService.listOrders(status, accountId);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@PathVariable("orderId") String orderId) {
        return orderMatcherService.getOrder(orderId);
    }

    @GetMapping("/positions")
    public List<finos.traderx.ordermatcher.lmax.PositionUpdate> listPositions(
        @RequestParam(value = "accountId", required = false) Integer accountId
    ) {
        return orderMatcherService.listPositions(accountId);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create one order",
        description = "Low-latency single-order contract. For sustained production ingress, "
            + "use POST /orders/batch so HTTP, JSON, ring publication, and acknowledgement costs "
            + "are amortised across orders."
    )
    public OrderResponse createOrder(
        @RequestBody OrderCreateRequest request,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        OrderResponse response = orderMatcherService.createOrder(request, authorization);
        orderMatcherService.publishOrderUpdate(response);
        return response;
    }

    @PostMapping("/orders/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create an order batch (recommended for sustained throughput)",
        description = "Production high-throughput ingress. Accepts up to 1,024 orders and "
            + "amortises one HTTP request, one JSON envelope, one contiguous input-ring publish, "
            + "and one acknowledgement wait across the batch. Responses remain in request order."
    )
    public List<OrderResponse> createOrderBatch(
        @RequestBody List<OrderCreateRequest> requests,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return orderMatcherService.createOrderBatch(requests, authorization);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderMatcherService.cancelOrder(orderId);
        orderMatcherService.publishOrderUpdate(response);
        return response;
    }

    @PostMapping("/orders/{orderId}/force-fill")
    public OrderResponse forceFillOrder(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderMatcherService.forceFillOrder(orderId);
        orderMatcherService.publishOrderUpdate(response);
        return response;
    }
}
