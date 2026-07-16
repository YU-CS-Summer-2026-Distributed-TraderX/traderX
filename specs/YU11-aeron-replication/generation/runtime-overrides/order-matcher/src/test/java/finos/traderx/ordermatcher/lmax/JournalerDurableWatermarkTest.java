package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JournalerDurableWatermarkTest {
    @TempDir Path tempDir;

    @Test
    void watermarkAdvancesOnlyAfterEndOfBatchForceCompletes() {
        Journaler journaler = new Journaler(true, tempDir, new HotPathMetrics(), 8);
        try {
            journaler.onEvent(event(1L), 10L, false);
            assertThat(journaler.journaledSeq()).isEqualTo(-1L);
            assertThat(journaler.journalForceNanos()).isEqualTo(-1L);

            journaler.onEvent(event(2L), 11L, true);
            assertThat(journaler.journaledSeq()).isEqualTo(11L);
            assertThat(journaler.journalForceNanos()).isPositive();
        } finally {
            journaler.close();
        }
    }

    private static InputEvent event(long seq) {
        InputEvent event = InputEvent.newInstance();
        event.seq = seq;
        event.type = InputEvent.TYPE_ORDER_NEW;
        return event;
    }
}
