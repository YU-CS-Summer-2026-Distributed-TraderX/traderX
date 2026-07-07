package finos.traderx.accountservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Exercises {@link AccountOutboxPublisher}'s poll-and-publish logic directly (constructing its
 * own instance with a fake {@link ControlFeedPublisher}) rather than through the real scheduled
 * bean — the real bean's {@code ControlFeedPublisher} is a live JetStream client, and this test
 * environment has no broker. This mirrors the project's existing convention of isolating
 * real-NATS behavior behind a thin, separately-verified adapter rather than mocking the NATS
 * client itself (see {@code ReplicationThroughputBenchmarkTest} in order-matcher, which simulates
 * rather than connects for the same reason).
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:test-application.properties")
class AccountOutboxPublisherTest {

  @Autowired private AccountService accountService;
  @Autowired private AccountControlOutboxRepository outboxRepository;
  @Autowired private SourceEpochRepository epochRepository;
  @Autowired private MeterRegistry meterRegistry;

  private static final class FakePublisher implements ControlFeedPublisher {
    final List<String> msgIds = new ArrayList<>();
    final List<String> payloads = new ArrayList<>();
    int failOnCallNumber = -1;
    int calls = 0;

    @Override
    public void publish(String natsMsgId, String payloadJson) throws Exception {
      calls++;
      if (calls == failOnCallNumber) {
        throw new IllegalStateException("simulated publish failure");
      }
      msgIds.add(natsMsgId);
      payloads.add(payloadJson);
    }
  }

  @Test
  void publishesUnpublishedRowsInOrderAndMarksThemPublished() {
    accountService.upsertAccount(newAccount(940001, "Publisher Test A"));
    accountService.upsertAccount(newAccount(940002, "Publisher Test B"));

    List<OutboxRow> pending = outboxRepository.findUnpublished(1000).stream()
        .filter(row -> row.accountId() == 940001 || row.accountId() == 940002)
        .sorted((a, b) -> Long.compare(a.version(), b.version()))
        .toList();
    assertThat(pending).hasSize(2);

    FakePublisher fake = new FakePublisher();
    AccountOutboxPublisher publisher =
        new AccountOutboxPublisher(outboxRepository, epochRepository, fake, meterRegistry, true);
    publisher.publishPending();

    assertThat(fake.msgIds).contains(
        "account:" + pending.get(0).version(),
        "account:" + pending.get(1).version());
    assertThat(outboxRepository.findUnpublished(1000))
        .noneMatch(row -> row.accountId() == 940001 || row.accountId() == 940002);
  }

  @Test
  void failureStopsAtThatRowWithoutSkippingAheadOrMarkingItPublished() {
    accountService.upsertAccount(newAccount(940011, "Publisher Fail Test A"));
    accountService.upsertAccount(newAccount(940012, "Publisher Fail Test B"));

    FakePublisher fake = new FakePublisher();
    fake.failOnCallNumber = 1; // fail on the very first publish call this poller makes
    AccountOutboxPublisher publisher =
        new AccountOutboxPublisher(outboxRepository, epochRepository, fake, meterRegistry, true);
    publisher.publishPending();

    assertThat(fake.msgIds).isEmpty();
    assertThat(outboxRepository.findUnpublished(1000))
        .anyMatch(row -> row.accountId() == 940011)
        .anyMatch(row -> row.accountId() == 940012);
  }

  private static Account newAccount(int id, String displayName) {
    Account account = new Account();
    account.setId(id);
    account.setDisplayName(displayName);
    return account;
  }
}
