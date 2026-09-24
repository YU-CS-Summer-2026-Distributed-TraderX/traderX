package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.repository.OrderRepository;
import finos.traderx.tradeprocessor.service.RunRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScopedOrderControllerTest {
 @Test void unknownScopeReturns404WithoutReadingOrders() throws Exception {
  var repository=mock(OrderRepository.class);var registry=mock(RunRegistry.class);var controller=new OrderController(repository);
  ReflectionTestUtils.setField(controller,"runRegistry",registry);
  when(registry.scope("unknown")).thenThrow(new IllegalArgumentException("RUN_SCOPE_UNREGISTERED"));
  MockMvcBuilders.standaloneSetup(controller).build().perform(get("/v2/projections/unknown/accounts/11/orders"))
    .andExpect(status().isNotFound());verifyNoInteractions(repository);
 }
 @Test void registeredEmptyScopeRemains200WithEmptyList() throws Exception {
  var repository=mock(OrderRepository.class);var registry=mock(RunRegistry.class);var controller=new OrderController(repository);
  ReflectionTestUtils.setField(controller,"runRegistry",registry);
  when(registry.scope("known")).thenReturn(Map.of("projection_scope","known"));
  when(repository.findByProjectionScopeAndAccountId("known",11)).thenReturn(List.of());
  MockMvcBuilders.standaloneSetup(controller).build().perform(get("/v2/projections/known/accounts/11/orders?status=all"))
    .andExpect(status().isOk()).andExpect(content().json("[]"));
 }
}
