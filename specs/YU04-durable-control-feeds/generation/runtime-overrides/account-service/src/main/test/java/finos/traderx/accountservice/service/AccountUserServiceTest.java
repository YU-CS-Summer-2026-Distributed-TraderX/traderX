package finos.traderx.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.AccountUser;
import finos.traderx.accountservice.repository.AccountRepository;
import finos.traderx.accountservice.repository.AccountUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Assigning a user to an account.
 *
 * <p>The rule worth pinning is a referential one the database does not enforce here: an assignment
 * may only be written for an account that exists. Every case below asserts on whether the SAVE
 * happened, not just on the exception, because a rejection that still wrote the row would leave an
 * assignment pointing at nothing and would satisfy a test that only checked the throw.
 */
class AccountUserServiceTest {

  private AccountUserRepository accountUsers;
  private AccountRepository accounts;
  private AccountUserService service;

  @BeforeEach
  void setUp() {
    accountUsers = mock(AccountUserRepository.class);
    accounts = mock(AccountRepository.class);
    service = new AccountUserService(accountUsers, accounts);
  }

  private static AccountUser assignment(Integer accountId, String username) {
    AccountUser u = new AccountUser();
    u.setAccountId(accountId);
    u.setUsername(username);
    return u;
  }

  @Test
  void anAssignmentIsSavedWhenTheAccountExists() {
    AccountUser incoming = assignment(44044, "rick");
    when(accounts.existsById(44044)).thenReturn(true);
    when(accountUsers.save(any(AccountUser.class))).thenReturn(incoming);

    assertThat(service.upsertAccountUser(incoming).getUsername()).isEqualTo("rick");
    verify(accountUsers).save(incoming);
  }

  @Test
  void anAssignmentToAnUnknownAccountIsRejectedAndNotSaved() {
    when(accounts.existsById(99999)).thenReturn(false);

    assertThatThrownBy(() -> service.upsertAccountUser(assignment(99999, "rick")))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("99999");

    verify(accountUsers, never()).save(any());
  }

  /**
   * A null account id must be rejected WITHOUT a repository lookup. The null check is short-circuited
   * ahead of {@code existsById} deliberately — passing null into the repository is at best a wasted
   * query and at worst a provider-specific exception that surfaces as a 500 instead of a 404.
   */
  @Test
  void aNullAccountIdIsRejectedWithoutQueryingTheRepository() {
    assertThatThrownBy(() -> service.upsertAccountUser(assignment(null, "rick")))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(accounts, never()).existsById(anyInt());
    verify(accountUsers, never()).save(any());
  }

  @Test
  void lookupByAccountIdReturnsTheAssignmentWhenPresent() {
    when(accountUsers.findByAccountId(44044)).thenReturn(Optional.of(assignment(44044, "rick")));

    assertThat(service.getAccountUserById(44044).getUsername()).isEqualTo("rick");
  }

  @Test
  void lookupByAccountIdThrowsNotFoundWhenTheAssignmentIsAbsent() {
    when(accountUsers.findByAccountId(44044)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getAccountUserById(44044))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("44044");
  }

  @Test
  void listingReturnsEveryAssignment() {
    when(accountUsers.findAll()).thenReturn(List.of(assignment(1, "a"), assignment(2, "b")));

    assertThat(service.getAllAccountUsers()).hasSize(2);
  }
}
