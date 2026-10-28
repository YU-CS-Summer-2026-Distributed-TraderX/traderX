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
 /** ADR-093: the order booked under the client's RAW key, scoped by the account as the gateway scopes it. */
 @Test void anOrderIsFoundByTheClientsRawKey() throws Exception {
  var repository=mock(OrderRepository.class);var registry=mock(RunRegistry.class);var controller=new OrderController(repository);
  ReflectionTestUtils.setField(controller,"runRegistry",registry);
  when(registry.activeScope()).thenReturn("run-1");
  var row=new finos.traderx.tradeprocessor.model.OrderRow();row.setId("7-42");row.setAccountId(11);row.setStatus("FILLED");
  when(repository.findByProjectionScopeAndAccountIdAndClientOrderKey("run-1",11,"11752876707446874652")).thenReturn(List.of(row));
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(get("/accounts/11/orders?clientOrderKey=18446744073709551615"))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("7-42"));
  mvc.perform(get("/accounts/11/orders?clientOrderKey=7")).andExpect(status().isOk()).andExpect(content().json("[]"));
  mvc.perform(get("/accounts/11/orders?clientOrderKey=-1")).andExpect(status().isBadRequest());
 }
 /** ADR-093, memory read model (ORDER_FEED_PERSIST=false): the same lookup off OrderView. */
 @Test void anOrderIsFoundByTheClientsRawKeyInTheMemoryView() throws Exception {
  var controller=new OrderController(mock(OrderRepository.class));
  var feed=mock(finos.traderx.tradeprocessor.OrderFeedHandler.class);var view=new finos.traderx.tradeprocessor.OrderView(10);
  when(feed.persists()).thenReturn(false);when(feed.view()).thenReturn(view);
  ReflectionTestUtils.setField(controller,"subscribers",List.of(feed));
  var u=new finos.traderx.tradeprocessor.model.OrderUpdate();u.setId("7-43");u.setAccountId(11);u.setStatus("NEW");
  u.setConsensusSequence(1L);u.setOutputOrdinal(0);u.setClientOrderKey("11752876707446874652");view.apply(u);
  MockMvcBuilders.standaloneSetup(controller).build().perform(get("/accounts/11/orders?clientOrderKey=18446744073709551615"))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("7-43"));
 }
}
