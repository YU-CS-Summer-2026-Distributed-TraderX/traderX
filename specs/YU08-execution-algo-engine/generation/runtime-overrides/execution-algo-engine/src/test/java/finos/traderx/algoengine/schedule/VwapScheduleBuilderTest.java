package finos.traderx.algoengine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.volume.SyntheticVolumeProfileSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class VwapScheduleBuilderTest {

  @Test
  void everyShareIsScheduledExactlyOnce() {
    List<Double> weights = new SyntheticVolumeProfileSource().bucketWeights("IBM", 6);
    List<Bucket> buckets = VwapScheduleBuilder.build(1000, weights, 10, 0L);
    int total = buckets.stream().mapToInt(Bucket::getTargetQuantity).sum();
    assertEquals(1000, total);
  }

  @Test
  void weightingProducesUnequalBucketsUnlikeTwap() {
    List<Double> weights = new SyntheticVolumeProfileSource().bucketWeights("IBM", 6);
    List<Bucket> buckets = VwapScheduleBuilder.build(1000, weights, 10, 0L);
    boolean allEqual = buckets.stream().mapToInt(Bucket::getTargetQuantity).distinct().count() == 1;
    assertFalse(allEqual);
  }
}
