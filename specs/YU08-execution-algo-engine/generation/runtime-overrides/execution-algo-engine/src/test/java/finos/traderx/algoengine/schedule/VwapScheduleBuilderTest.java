package finos.traderx.algoengine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.volume.SyntheticVolumeProfileSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class VwapScheduleBuilderTest {

  @Test
  void allocatesQuantityProportionallyToExplicitWeights() {
    List<Bucket> buckets = VwapScheduleBuilder.build(1000, List.of(0.5, 0.3, 0.2), 10, 0L);
    assertEquals(List.of(500, 300, 200),
        buckets.stream().map(Bucket::getTargetQuantity).toList());
  }

  @Test
  void remainderLandsDeterministicallyInLastBucket() {
    List<Bucket> buckets = VwapScheduleBuilder.build(1001, List.of(0.5, 0.3, 0.2), 10, 0L);
    assertEquals(List.of(500, 300, 201),
        buckets.stream().map(Bucket::getTargetQuantity).toList());
    assertEquals(1001, buckets.stream().mapToInt(Bucket::getTargetQuantity).sum());
  }

  @Test
  void singleBucketReceivesTheWholeParent() {
    assertEquals(73, VwapScheduleBuilder.build(73, List.of(1.0), 10, 0L)
        .get(0).getTargetQuantity());
  }

  @Test
  void allZeroWeightsFallBackToEvenSplit() {
    List<Bucket> buckets = VwapScheduleBuilder.build(10, List.of(0.0, 0.0, 0.0), 10, 0L);
    assertEquals(List.of(3, 3, 4), buckets.stream().map(Bucket::getTargetQuantity).toList());
  }

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
