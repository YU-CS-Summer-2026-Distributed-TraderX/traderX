package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationPolicyTest {
    @Test
    void defaultsRemainNatsOnRingAndDegradedSolo() {
        assertThat(ReplicationTransport.parse(null)).isEqualTo(ReplicationTransport.NATS);
        assertThat(ReplicationAckMode.parse(null)).isEqualTo(ReplicationAckMode.ON_RING);
        assertThat(ReplicationFailurePolicy.parse(null))
            .isEqualTo(ReplicationFailurePolicy.DEGRADED_SOLO);
    }

    @Test
    void strictRequiresDurableAndDurableRequiresJournal() {
        assertThatThrownBy(() -> ReplicationFailurePolicy.validate(
            ReplicationAckMode.ON_RING, ReplicationFailurePolicy.STRICT, true))
            .hasMessageContaining("strict replication requires");
        assertThatThrownBy(() -> ReplicationFailurePolicy.validate(
            ReplicationAckMode.DURABLE, ReplicationFailurePolicy.DEGRADED_SOLO, false))
            .hasMessageContaining("requires ORDER_MATCHER_JOURNAL_ENABLED=true");
        ReplicationFailurePolicy.validate(
            ReplicationAckMode.DURABLE, ReplicationFailurePolicy.STRICT, true);
    }
}
