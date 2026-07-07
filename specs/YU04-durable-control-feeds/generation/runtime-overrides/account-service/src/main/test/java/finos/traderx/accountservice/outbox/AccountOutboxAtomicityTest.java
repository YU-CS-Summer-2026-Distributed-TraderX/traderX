package finos.traderx.accountservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.repository.AccountRepository;
import finos.traderx.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the outbox insert and the {@code accounts} write share one local transaction (ADR-021):
 * if the outbox insert fails, the accounts write must roll back too — there is no window where
 * one commits without the other.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:test-application.properties")
class AccountOutboxAtomicityTest {

  @Autowired private AccountService accountService;
  @Autowired private AccountRepository accountRepository;
  @MockitoBean private AccountControlOutboxRepository outboxRepository;

  @Test
  void outboxFailureRollsBackTheAccountsWriteToo() {
    when(outboxRepository.recordChange(anyInt(), anyString()))
        .thenThrow(new RuntimeException("simulated outbox failure"));

    Account account = new Account();
    account.setId(920001);
    account.setDisplayName("Should Not Persist");

    assertThatThrownBy(() -> accountService.upsertAccount(account))
        .hasMessageContaining("simulated outbox failure");

    assertThat(accountRepository.findById(920001)).isEmpty();
  }
}
