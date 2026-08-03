package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AeronPublicationSequenceMapTest {
    @Test
    void resolvesTheExactCommittedFrameRatherThanTheLatestPublicationPosition() {
        AeronPublicationSequenceMap map = new AeronPublicationSequenceMap(4);
        FollowerSequenceMap.Entry entry = new FollowerSequenceMap.Entry();

        map.put(10L, 3L, 1388L, 101_472L, 0x1111L, 77);
        map.put(11L, 3L, 1389L, 101_568L, 0x2222L, 77);

        assertThat(map.read(10L, entry)).isTrue();
        assertThat(entry.localSeq).isEqualTo(10L);
        assertThat(entry.epoch).isEqualTo(3L);
        assertThat(entry.inputSeq).isEqualTo(1388L);
        assertThat(entry.recordingPosition).isEqualTo(101_472L);
        assertThat(entry.checksum).isEqualTo(0x1111L);
        assertThat(entry.dataSessionId).isEqualTo(77);
    }

    @Test
    void rejectsAnOverwrittenRingSlot() {
        AeronPublicationSequenceMap map = new AeronPublicationSequenceMap(2);
        FollowerSequenceMap.Entry entry = new FollowerSequenceMap.Entry();
        map.put(0L, 1L, 0L, 96L, 1L, 7);
        map.put(2L, 1L, 2L, 288L, 3L, 7);

        assertThat(map.read(0L, entry)).isFalse();
        assertThat(map.read(2L, entry)).isTrue();
    }
}
