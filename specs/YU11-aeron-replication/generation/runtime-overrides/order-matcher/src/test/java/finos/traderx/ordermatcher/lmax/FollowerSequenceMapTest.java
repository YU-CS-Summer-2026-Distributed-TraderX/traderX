package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FollowerSequenceMapTest {
    @Test
    void mapsExactLocalDurabilitySequenceWithoutDroppingWatermark() {
        FollowerSequenceMap map = new FollowerSequenceMap(4);
        FollowerSequenceMap.Entry entry = new FollowerSequenceMap.Entry();
        assertThat(map.put(0, 7, 100, 1000, 11)).isTrue();
        assertThat(map.put(1, 7, 101, 1064, 12)).isTrue();
        assertThat(map.read(1, entry)).isTrue();
        assertThat(entry.localSeq).isEqualTo(1);
        assertThat(entry.epoch).isEqualTo(7);
        assertThat(entry.inputSeq).isEqualTo(101);
        assertThat(entry.recordingPosition).isEqualTo(1064);
        assertThat(entry.checksum).isEqualTo(12);
        map.consumed(1);
        assertThat(map.put(5, 8, 200, 2000, 13)).isTrue();
    }

    @Test
    void refusesOverwriteWhenAckAgentFallsMoreThanCapacityBehind() {
        FollowerSequenceMap map = new FollowerSequenceMap(2);
        assertThat(map.put(0, 1, 0, 64, 1)).isTrue();
        assertThat(map.put(1, 1, 1, 128, 2)).isTrue();
        assertThat(map.put(2, 1, 2, 192, 3)).isFalse();
    }
}
