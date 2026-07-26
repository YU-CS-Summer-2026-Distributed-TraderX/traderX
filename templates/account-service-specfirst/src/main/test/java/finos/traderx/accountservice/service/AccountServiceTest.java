package finos.traderx.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.repository.AccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plain unit tests for the account domain — no Spring context, no DB. The repository is mocked,
 * so these run in CI with zero infrastructure. The value test is the not-found path: a missing
 * account must be SIGNALLED (a typed exception carrying the id), never returned as a silent null.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

  @Mock private AccountRepository accountRepository;

  @InjectMocks private AccountService accountService;

  @Test
  void getAccountById_returnsAccount_whenPresent() {
    Account acct = account(7, "Desk 7");
    when(accountRepository.findById(7)).thenReturn(Optional.of(acct));

    assertThat(accountService.getAccountById(7)).isSameAs(acct);
  }

  @Test
  void getAccountById_throwsSignallingId_whenMissing() {
    when(accountRepository.findById(404)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.getAccountById(404))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("404");
  }

  @Test
  void getAllAccount_delegatesToRepository() {
    List<Account> all = List.of(account(1, "A"), account(2, "B"));
    when(accountRepository.findAll()).thenReturn(all);

    assertThat(accountService.getAllAccount()).isEqualTo(all);
  }

  @Test
  void upsertAccount_persistsAndReturnsSaved() {
    Account toSave = account(0, "New Desk");
    Account saved = account(9, "New Desk");
    when(accountRepository.save(toSave)).thenReturn(saved);

    assertThat(accountService.upsertAccount(toSave)).isSameAs(saved);
    verify(accountRepository).save(toSave);
  }

  private static Account account(int id, String displayName) {
    Account a = new Account();
    a.setId(id);
    a.setDisplayName(displayName);
    return a;
  }
}
