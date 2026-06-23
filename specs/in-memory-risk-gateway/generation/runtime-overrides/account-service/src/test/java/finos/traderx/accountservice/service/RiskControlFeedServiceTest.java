package finos.traderx.accountservice.service;

import finos.traderx.accountservice.repository.RiskControlEventRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class RiskControlFeedServiceTest {
  @Test
  void mutationUsesOptimisticAggregateVersionAndPersistsProvenance() {
    RiskControlEventRepository repository = mock(RiskControlEventRepository.class);
    when(repository.currentVersion("RESTRICTION", "IBM")).thenReturn(7L);
    when(repository.append(eq(1L), eq("RESTRICTION"), eq("IBM"), eq(true), eq(0L), eq(0), eq(0L),
        eq("alice"), anyLong())).thenReturn(8L);
    RiskControlFeedService service = new RiskControlFeedService(repository);

    // Timestamp is intentionally verified by value properties rather than mocked clock.
    var result = service.mutate(new RiskControlFeedService.Mutation(
        "restriction", "ibm", 7L, true, 0L, 0, 0L), "alice");
    assertEquals("RESTRICTION", result.eventType());
    assertEquals("IBM", result.aggregateKey());
    assertEquals("alice", result.operator());
  }

  @Test
  void staleMutationIsRejectedBeforeOutboxAppend() {
    RiskControlEventRepository repository = mock(RiskControlEventRepository.class);
    when(repository.currentVersion("KILL_SWITCH", "GLOBAL")).thenReturn(3L);
    RiskControlFeedService service = new RiskControlFeedService(repository);
    assertThrows(RiskControlFeedService.StaleControlVersionException.class,
        () -> service.mutate(new RiskControlFeedService.Mutation(
            "kill_switch", "global", 2L, true, 4L, 0, 0L), "alice"));
    verify(repository).currentVersion("KILL_SWITCH", "GLOBAL");
  }
}
