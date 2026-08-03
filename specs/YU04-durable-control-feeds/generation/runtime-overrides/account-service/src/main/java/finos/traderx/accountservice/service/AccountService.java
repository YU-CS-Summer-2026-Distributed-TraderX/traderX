package finos.traderx.accountservice.service;

import finos.traderx.accountservice.exceptions.ResourceNotFoundException;
import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.outbox.AccountControlOutboxRepository;
import finos.traderx.accountservice.repository.AccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

  private final AccountRepository accountRepository;
  private final AccountControlOutboxRepository outboxRepository;

  public AccountService(AccountRepository accountRepository, AccountControlOutboxRepository outboxRepository) {
    this.accountRepository = accountRepository;
    this.outboxRepository = outboxRepository;
  }

  public List<Account> getAllAccount() {
    return accountRepository.findAll();
  }

  public Account getAccountById(int id) {
    return accountRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Account with id " + id + " not found"));
  }

  /**
   * Upserts the account and records its existence/identity in the durable control outbox
   * (ADR-021) in the SAME local transaction — the two rows can never diverge, since either both
   * commit or neither does (no distributed/two-phase commit involved).
   */
  @Transactional
  public Account upsertAccount(Account account) {
    Account saved = accountRepository.save(account);
    outboxRepository.recordChange(saved.getId(), saved.getDisplayName());
    return saved;
  }
}
