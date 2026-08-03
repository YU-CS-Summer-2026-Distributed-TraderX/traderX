package finos.traderx.accountservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.repository.AccountRepository;
import finos.traderx.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the transactional-outbox wiring (ADR-021): an account create/update produces a matching
 * outbox row, and the watermarked snapshot reflects it once published.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:test-application.properties")
class AccountOutboxWiringTest {

  @Autowired private AccountService accountService;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountControlOutboxRepository outboxRepository;
  @Autowired private AccountSnapshotService snapshotService;

  @Test
  void upsertAccountRecordsAMatchingOutboxRow() {
    Account account = new Account();
    account.setId(910001);
    account.setDisplayName("Outbox Wiring Test Account");

    accountService.upsertAccount(account);

    Account persisted = accountRepository.findById(910001).orElseThrow();
    assertThat(persisted.getDisplayName()).isEqualTo("Outbox Wiring Test Account");

    assertThat(outboxRepository.findUnpublished(1000))
        .anyMatch(row -> row.accountId() == 910001 && "Outbox Wiring Test Account".equals(row.displayName()));
  }

  @Test
  void snapshotWatermarkOnlyCountsPublishedRows() {
    long watermarkBefore = outboxRepository.publishedWatermark();

    Account account = new Account();
    account.setId(910002);
    account.setDisplayName("Unpublished Yet");
    accountService.upsertAccount(account);

    // Not yet published (the poller hasn't run) — watermark must not move on an unpublished row.
    assertThat(outboxRepository.publishedWatermark()).isEqualTo(watermarkBefore);

    long newVersion = outboxRepository.findUnpublished(1000).stream()
        .filter(row -> row.accountId() == 910002)
        .findFirst()
        .orElseThrow()
        .version();
    outboxRepository.markPublished(newVersion);

    assertThat(outboxRepository.publishedWatermark()).isEqualTo(newVersion);

    ControlSnapshot snapshot = snapshotService.snapshot();
    assertThat(snapshot.watermark()).isEqualTo(newVersion);
    assertThat(snapshot.records()).anyMatch(a -> a.getId() == 910002);
    assertThat(snapshot.count()).isEqualTo(snapshot.records().size());
  }

  @Test
  void snapshotChecksumIsStableForTheSameRecordSet() {
    ControlSnapshot first = snapshotService.snapshot();
    ControlSnapshot second = snapshotService.snapshot();
    assertThat(first.checksum()).isEqualTo(second.checksum());
    assertThat(first.checksum()).startsWith("sha256:");
  }
}
