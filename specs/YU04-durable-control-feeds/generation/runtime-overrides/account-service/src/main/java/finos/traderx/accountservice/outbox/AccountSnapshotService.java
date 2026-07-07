package finos.traderx.accountservice.outbox;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the watermarked snapshot (ADR-019 step 2/3): the current account universe plus enough
 * metadata (schema version, source epoch, watermark, checksum) for a consumer to verify it before
 * atomic install.
 */
@Service
public class AccountSnapshotService {
  private static final int SCHEMA_VERSION = 1;

  private final AccountRepository accountRepository;
  private final AccountControlOutboxRepository outboxRepository;
  private final SourceEpochRepository epochRepository;

  public AccountSnapshotService(
      AccountRepository accountRepository,
      AccountControlOutboxRepository outboxRepository,
      SourceEpochRepository epochRepository) {
    this.accountRepository = accountRepository;
    this.outboxRepository = outboxRepository;
    this.epochRepository = epochRepository;
  }

  public ControlSnapshot snapshot() {
    List<Account> accounts = accountRepository.findAll().stream()
        .sorted(Comparator.comparing(Account::getId))
        .toList();
    return new ControlSnapshot(
        SCHEMA_VERSION,
        epochRepository.currentEpoch(),
        outboxRepository.publishedWatermark(),
        accounts.size(),
        checksum(accounts),
        accounts);
  }

  /** SHA-256 over the canonical (id-sorted) record set, so a consumer can verify it independently. */
  private static String checksum(List<Account> sortedAccounts) {
    StringBuilder canonical = new StringBuilder();
    for (Account account : sortedAccounts) {
      canonical.append(account.getId()).append(':').append(account.getDisplayName()).append(';');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(hash);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
