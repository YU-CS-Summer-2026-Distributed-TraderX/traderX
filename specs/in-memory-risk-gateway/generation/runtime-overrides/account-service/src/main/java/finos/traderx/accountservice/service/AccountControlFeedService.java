package finos.traderx.accountservice.service;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.model.AccountControlEvent;
import finos.traderx.accountservice.model.AccountUser;
import finos.traderx.accountservice.repository.AccountControlEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Watermarked snapshot plus retained deltas used to bootstrap admission replicas. */
@Service
public class AccountControlFeedService {
  public record AccountImage(int accountId, boolean enabled, long version) {}
  public record EntitlementImage(String principal, int accountId, boolean enabled, long version) {}
  public record Snapshot(long sourceEpoch, long watermark, long highWatermark,
                         List<AccountImage> accounts, List<EntitlementImage> entitlements) {}

  private static final long SOURCE_EPOCH = 1L;
  private final AccountService accounts;
  private final AccountControlEventRepository outbox;
  private final AccountUserService accountUsers;

  public AccountControlFeedService(AccountService accounts, AccountControlEventRepository outbox,
                                   AccountUserService accountUsers) {
    this.accounts = accounts;
    this.outbox = outbox;
    this.accountUsers = accountUsers;
  }

  public void record(Account account) {
    outbox.append(SOURCE_EPOCH, "ACCOUNT", account.getId(), null, true, System.currentTimeMillis());
  }

  public void record(AccountUser accountUser) {
    outbox.append(SOURCE_EPOCH, "ENTITLEMENT", accountUser.getAccountId(), accountUser.getUsername(),
        true, System.currentTimeMillis());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot snapshot() {
    long watermark = outbox.watermark();
    List<AccountImage> image = accounts.getAllAccount().stream()
        .map(account -> new AccountImage(account.getId(), true, watermark)).toList();
    List<EntitlementImage> entitlements = accountUsers.getAllAccountUsers().stream()
        .map(item -> new EntitlementImage(item.getUsername(), item.getAccountId(), true, watermark)).toList();
    return new Snapshot(SOURCE_EPOCH, watermark, watermark, image, entitlements);
  }

  public List<AccountControlEvent> deltasAfter(long version) {
    return outbox.after(version);
  }
}
