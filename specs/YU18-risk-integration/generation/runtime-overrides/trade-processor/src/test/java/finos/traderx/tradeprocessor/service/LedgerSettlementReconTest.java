package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import finos.traderx.tradeprocessor.service.ReconciliationService.BlotterEntry;
import finos.traderx.tradeprocessor.service.ReconciliationService.LedgerSide;
import finos.traderx.tradeprocessor.service.ReconciliationService.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** YU18 trade store (downstream-at-400k.md §7.6): settlement and recon read the ledger, not the SQL window. */
class LedgerSettlementReconTest {

  @Test void tPlusOneSkipsTheWeekend() {
    assertEquals(Instant.parse("2026-10-23T00:00:00Z"), TradeSnapshotStore.settlesOn(LocalDate.parse("2026-10-22"), 1));  // Thu -> Fri
    assertEquals(Instant.parse("2026-10-26T00:00:00Z"), TradeSnapshotStore.settlesOn(LocalDate.parse("2026-10-23"), 1));  // Fri -> Mon
    assertEquals(Instant.parse("2026-10-26T00:00:00Z"), TradeSnapshotStore.settlesOn(LocalDate.parse("2026-10-26"), 0));  // T+0
  }

  private static final BlotterEntry E = new BlotterEntry("e1-b1-41-B", 41, 7, "AAPL", "Buy", 100, new BigDecimal("10.5"), 0);

  private static LedgerSide side(long streamSeq, int qty) {
    return new LedgerSide("e1-b1-41-B", 7, "AAPL", "Buy", qty, new BigDecimal("10.500000"), "run-1", streamSeq);
  }

  @Test void aSideIsMatchedOnlyOnceACommittedSnapshotCoversIt() {
    assertEquals(Verdict.WAIT, ReconciliationService.classifyOnLedger(E, side(900, 100), 50, 899, "run-1"));
    assertEquals(Verdict.MATCHED, ReconciliationService.classifyOnLedger(E, side(900, 100), 50, 900, "run-1"));
    assertEquals(Verdict.FIELD_MISMATCH, ReconciliationService.classifyOnLedger(E, side(900, 99), 50, 900, "run-1"));
    assertEquals(Verdict.FIELD_MISMATCH, ReconciliationService.classifyOnLedger(E, side(900, 100), 50, 900, "run-2"));
  }

  @Test void aLegacySideMatchesTheLegacyScope() {
    LedgerSide legacy = new LedgerSide("41-B", 7, "AAPL", "Buy", 100, new BigDecimal("10.5"), RunRegistry.UNKNOWN, 5);
    BlotterEntry e = new BlotterEntry("41-B", 41, 7, "AAPL", "Buy", 100, new BigDecimal("10.5"), 0);
    assertEquals(Verdict.MATCHED, ReconciliationService.classifyOnLedger(e, legacy, 41, 5, RunRegistry.UNKNOWN));
  }

  @Test void absentIsMissingOnlyWhenTheStreamHasMovedPastIt() {
    assertEquals(Verdict.WAIT, ReconciliationService.classifyOnLedger(E, null, 41, 10_000, "run-1"));
    assertEquals(Verdict.MISSING, ReconciliationService.classifyOnLedger(E, null, 42, 10_000, "run-1"));
  }

  @Test void tradeSeqIsReadFromEitherIdScheme() {
    assertEquals(41, ReconciliationService.tradeSeqOf("e1-b1-41-B"));
    assertEquals(7, ReconciliationService.tradeSeqOf("7-S"));
  }
}
