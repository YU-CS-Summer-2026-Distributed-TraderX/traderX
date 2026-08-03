package finos.traderx.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.outbox.AccountSnapshotService;
import finos.traderx.accountservice.outbox.ControlSnapshot;
import finos.traderx.accountservice.service.AccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The account HTTP edge: status mapping, and that create/update share one upsert.
 *
 * <p>The exception handlers are the part worth pinning. A missing account must reach the caller as
 * 404 with a message, while anything unexpected must be 500 — collapsing the two would either hide
 * a real fault behind a "not found" or page someone for a routine miss.
 */
class AccountControllerTest {

  private AccountService accountService;
  private AccountSnapshotService snapshotService;
  private AccountController controller;

  @BeforeEach
  void setUp() {
    accountService = mock(AccountService.class);
    snapshotService = mock(AccountSnapshotService.class);
    controller = new AccountController(accountService, snapshotService);
  }

  private static Account account(Integer id, String displayName) {
    Account a = new Account();
    a.setId(id);
    a.setDisplayName(displayName);
    return a;
  }

  @Test
  void gettingAnAccountReturns200WithTheAccount() {
    when(accountService.getAccountById(44044)).thenReturn(account(44044, "Test Account"));

    ResponseEntity<Account> response = controller.getAccountById(44044);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getDisplayName()).isEqualTo("Test Account");
  }

  @Test
  void listingReturns200WithEveryAccount() {
    when(accountService.getAllAccount()).thenReturn(List.of(account(1, "A"), account(2, "B")));

    assertThat(controller.getAllAccount().getBody()).hasSize(2);
  }

  /** Create and update are the same upsert; both must answer 200 with the persisted account. */
  @Test
  void createAndUpdateBothReturnThePersistedAccount() {
    when(accountService.upsertAccount(any(Account.class))).thenReturn(account(65001, "Saved"));

    assertThat(controller.createAccount(account(null, "New")).getBody().getId()).isEqualTo(65001);
    assertThat(controller.updateAccount(account(65001, "Edited")).getBody().getId()).isEqualTo(65001);
  }

  @Test
  void aMissingAccountIsMappedTo404CarryingTheMessage() {
    ResponseEntity<String> response = controller.resourceNotFoundExceptionMapper(
        new ResourceNotFoundException("Account with id 99999 not found"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("99999");
  }

  /**
   * Anything unexpected is a 500, NOT a 404. If the general handler answered 404 too, a broken
   * database would be indistinguishable from an account that simply is not there — and nobody would
   * be paged for it.
   */
  @Test
  void anUnexpectedFailureIsMappedTo500RatherThan404() {
    ResponseEntity<String> response =
        controller.generalError(new IllegalStateException("connection pool exhausted"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).contains("connection pool exhausted");
  }

  @Test
  void theControlSnapshotIsServedFromTheSnapshotService() {
    ControlSnapshot snapshot = mock(ControlSnapshot.class);
    when(snapshotService.snapshot()).thenReturn(snapshot);

    ResponseEntity<ControlSnapshot> response = controller.getControlSnapshot();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(snapshot);
  }
}
