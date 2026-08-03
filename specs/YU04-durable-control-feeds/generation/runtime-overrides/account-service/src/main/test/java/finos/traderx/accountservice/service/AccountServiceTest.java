package finos.traderx.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.outbox.AccountControlOutboxRepository;
import finos.traderx.accountservice.repository.AccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Account CRUD, and the outbox row that must accompany a write.
 *
 * <p>The interesting assertions here are not "does save get called" — they are about WHICH account
 * the outbox is told about. {@code upsertAccount} records the result of {@code save}, not the
 * argument it was handed, and on a create those differ: the submitted object has no id yet and the
 * database assigns one. Recording the submitted object would put a null id on the control feed,
 * where every consumer keys by it.
 *
 * <p>Atomicity itself is deliberately NOT asserted here. It is a database property, not a Java one,
 * and is covered by {@code AccountOutboxAtomicityIT} against a real MariaDB — a mocked repository
 * would report success whether or not the two writes shared a transaction.
 */
class AccountServiceTest {

  private AccountRepository accounts;
  private AccountControlOutboxRepository outbox;
  private AccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    outbox = mock(AccountControlOutboxRepository.class);
    service = new AccountService(accounts, outbox);
  }

  private static Account account(Integer id, String displayName) {
    Account a = new Account();
    a.setId(id);
    a.setDisplayName(displayName);
    return a;
  }

  @Test
  void getAccountByIdReturnsTheAccountWhenPresent() {
    when(accounts.findById(44044)).thenReturn(Optional.of(account(44044, "Test Account")));

    assertThat(service.getAccountById(44044).getDisplayName()).isEqualTo("Test Account");
  }

  /** The message carries the id, because a 404 body with no id is unactionable in a log. */
  @Test
  void getAccountByIdThrowsNotFoundNamingTheIdWhenAbsent() {
    when(accounts.findById(99999)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getAccountById(99999))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("99999");
  }

  @Test
  void getAllAccountReturnsEveryAccount() {
    when(accounts.findAll()).thenReturn(List.of(account(1, "A"), account(2, "B")));

    assertThat(service.getAllAccount()).hasSize(2);
  }

  @Test
  void anEmptyRepositoryListsNothingRatherThanFailing() {
    when(accounts.findAll()).thenReturn(List.of());

    assertThat(service.getAllAccount()).isEmpty();
  }

  /**
   * The load-bearing one. On a create the submitted account has no id; the database assigns it, so
   * the outbox must be told about the SAVED row. Recording the argument instead would publish a
   * null id onto the control feed that every consumer keys by.
   */
  @Test
  void theOutboxRecordsTheSavedAccountIdNotTheSubmittedOne() {
    Account submitted = account(null, "New Account");
    when(accounts.save(any(Account.class))).thenReturn(account(65001, "New Account"));

    Account returned = service.upsertAccount(submitted);

    ArgumentCaptor<Integer> id = ArgumentCaptor.forClass(Integer.class);
    verify(outbox).recordChange(id.capture(), anyString());
    assertThat(id.getValue()).isEqualTo(65001);
    assertThat(returned.getId()).isEqualTo(65001);
  }

  /** Same reasoning for the name: a rename must reach the feed as the persisted value. */
  @Test
  void theOutboxRecordsThePersistedDisplayName() {
    when(accounts.save(any(Account.class))).thenReturn(account(44044, "Renamed"));

    service.upsertAccount(account(44044, "Submitted Name"));

    verify(outbox).recordChange(anyInt(), org.mockito.ArgumentMatchers.eq("Renamed"));
  }

  /**
   * If the write fails there must be no control-feed row. The transaction would roll one back
   * anyway, but recording BEFORE the save would also emit for an account that never existed.
   */
  @Test
  void aFailedSaveRecordsNothingOnTheOutbox() {
    when(accounts.save(any(Account.class))).thenThrow(new IllegalStateException("constraint violation"));

    assertThatThrownBy(() -> service.upsertAccount(account(1, "Doomed")))
        .isInstanceOf(IllegalStateException.class);

    verify(outbox, never()).recordChange(anyInt(), anyString());
  }
}
