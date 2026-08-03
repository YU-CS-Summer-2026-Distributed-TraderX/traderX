package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowSequenceMapTest {
    @Test
    void exactSequenceTagPreventsComparingAnOverwrittenSlot() {
        ShadowSequenceMap map = new ShadowSequenceMap(4);
        map.put(1L, 11L);
        assertThat(map.contains(1L)).isTrue();
        assertThat(map.checksum(1L)).isEqualTo(11L);

        map.put(5L, 55L);
        assertThat(map.contains(1L)).isFalse();
        assertThat(map.contains(5L)).isTrue();
        assertThat(map.checksum(5L)).isEqualTo(55L);
    }
}
