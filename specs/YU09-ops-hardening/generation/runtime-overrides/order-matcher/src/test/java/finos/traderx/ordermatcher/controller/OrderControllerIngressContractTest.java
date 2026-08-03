package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerIngressContractTest {
    @Test
    void singleOrderKeepsCreatedStatus() throws Exception {
        OrderMatcherService service = mock(OrderMatcherService.class);
        when(service.createOrder(any(), isNull())).thenReturn(mock(OrderResponse.class));
        var mvc = MockMvcBuilders.standaloneSetup(new OrderController(service)).build();

        mvc.perform(post("/orders")
                .contentType("application/json")
                .content("{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":10,\"limitPrice\":200}"))
            .andExpect(status().isCreated());
    }

    @Test
    void productionBatchContractKeepsCreatedStatus() throws Exception {
        OrderMatcherService service = mock(OrderMatcherService.class);
        when(service.createOrderBatch(anyList(), isNull())).thenReturn(List.of(mock(OrderResponse.class)));
        var mvc = MockMvcBuilders.standaloneSetup(new OrderController(service)).build();

        mvc.perform(post("/orders/batch")
                .contentType("application/json")
                .content("[{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":10,\"limitPrice\":200}]"))
            .andExpect(status().isCreated());
        verify(service).createOrderBatch(anyList(), isNull());
    }
}
