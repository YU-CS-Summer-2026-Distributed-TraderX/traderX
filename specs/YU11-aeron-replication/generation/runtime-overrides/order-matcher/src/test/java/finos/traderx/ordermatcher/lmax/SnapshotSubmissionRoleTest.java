package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotSubmissionRoleTest {
    @Test
    void replicatedPairOnlyLetsPrimaryCreateTheMarker() {
        assertThat(LmaxEngine.localSnapshotSubmissionAllowed(
            true, ReplicationRole.Role.PRIMARY)).isTrue();
        assertThat(LmaxEngine.localSnapshotSubmissionAllowed(
            true, ReplicationRole.Role.FOLLOWER)).isFalse();
        assertThat(LmaxEngine.localSnapshotSubmissionAllowed(
            true, ReplicationRole.Role.UNKNOWN)).isFalse();
    }

    @Test
    void standaloneEngineStillCreatesLocalSnapshots() {
        assertThat(LmaxEngine.localSnapshotSubmissionAllowed(
            false, ReplicationRole.Role.UNKNOWN)).isTrue();
    }
}
