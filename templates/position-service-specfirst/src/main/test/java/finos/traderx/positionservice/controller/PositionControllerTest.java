package finos.traderx.positionservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finos.traderx.positionservice.model.Position;
import finos.traderx.positionservice.service.PositionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller wiring + error mapping via standalone MockMvc — no Spring context, no DB. The 500
 * mapping matters: if the projection store is down, callers must see an error status, not a 200
 * with an empty list that reads as "this account holds nothing".
 */
@ExtendWith(MockitoExtension.class)
class PositionControllerTest {

  @Mock private PositionService positionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new PositionController(positionService)).build();
  }

  @Test
  void getByAccountId_returns200AndBook() throws Exception {
    Position p = new Position();
    p.setAccountId(5);
    p.setSecurity("AAPL");
    p.setQuantity(10);
    when(positionService.getPositionsByAccountID(5)).thenReturn(List.of(p));

    mockMvc.perform(get("/positions/5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].accountId").value(5))
        .andExpect(jsonPath("$[0].quantity").value(10));
  }

  @Test
  void getByAccountId_returns500_whenStoreFails() throws Exception {
    when(positionService.getPositionsByAccountID(5))
        .thenThrow(new RuntimeException("projection store down"));

    mockMvc.perform(get("/positions/5"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string("projection store down"));
  }
}
