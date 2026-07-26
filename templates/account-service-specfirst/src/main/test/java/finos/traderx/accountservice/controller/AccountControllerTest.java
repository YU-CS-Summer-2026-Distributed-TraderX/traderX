package finos.traderx.accountservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller error-path mapping via standalone MockMvc — no Spring context, no DB. The point of
 * these is the @ExceptionHandler contract: a not-found must surface as HTTP 404 (not a 200 with an
 * empty body), and an unexpected failure as 500. Silent-200-on-failure is a bug class this project
 * has actually shipped, so these paths carry more weight than the happy path.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

  @Mock private AccountService accountService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new AccountController(accountService)).build();
  }

  @Test
  void getAccountById_returns200AndBody_whenFound() throws Exception {
    Account acct = new Account();
    acct.setId(3);
    acct.setDisplayName("Desk 3");
    when(accountService.getAccountById(3)).thenReturn(acct);

    mockMvc.perform(get("/account/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(3))
        .andExpect(jsonPath("$.displayName").value("Desk 3"));
  }

  @Test
  void getAccountById_returns404_whenServiceSignalsNotFound() throws Exception {
    when(accountService.getAccountById(404))
        .thenThrow(new ResourceNotFoundException("Account with id 404 not found"));

    mockMvc.perform(get("/account/404"))
        .andExpect(status().isNotFound())
        .andExpect(content().string("Account with id 404 not found"));
  }

  @Test
  void createAccount_returns500_whenServiceThrowsUnexpectedly() throws Exception {
    when(accountService.upsertAccount(any()))
        .thenThrow(new RuntimeException("datasource down"));

    mockMvc.perform(
            post("/account/")
                .contentType("application/json")
                .content("{\"displayName\":\"x\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string("datasource down"));
  }
}
