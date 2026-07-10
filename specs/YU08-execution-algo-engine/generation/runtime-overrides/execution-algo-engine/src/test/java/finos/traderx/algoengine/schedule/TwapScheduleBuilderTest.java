package finos.traderx.algoengine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import finos.traderx.algoengine.model.Bucket;
import java.util.List;
import org.junit.jupiter.api.Test;

class TwapScheduleBuilderTest {

  @Test
  void splitsEvenlyWhenDivisible() {
    List<Bucket> buckets = TwapScheduleBuilder.build(1000, 100, 10, 0L);
    assertEquals(10, buckets.size());
    buckets.forEach(b -> assertEquals(100, b.getTargetQuantity()));
  }

  @Test
  void remainderLandsOnLastBucket() {
    List<Bucket> buckets = TwapScheduleBuilder.build(1001, 100, 10, 0L);
    assertEquals(10, buckets.size());
    for (int i = 0; i < 9; i++) {
      assertEquals(100, buckets.get(i).getTargetQuantity());
    }
    assertEquals(101, buckets.get(9).getTargetQuantity());
  }

  @Test
  void everyShareIsScheduledExactlyOnce() {
    List<Bucket> buckets = TwapScheduleBuilder.build(777, 65, 10, 0L);
    int total = buckets.stream().mapToInt(Bucket::getTargetQuantity).sum();
    assertEquals(777, total);
  }

  @Test
  void bucketStartTimesAreEquallySpaced() {
    List<Bucket> buckets = TwapScheduleBuilder.build(100, 50, 10, 1_000L);
    assertEquals(1_000L, buckets.get(0).getStartEpochMs());
    assertEquals(11_000L, buckets.get(1).getStartEpochMs());
    assertEquals(21_000L, buckets.get(2).getStartEpochMs());
  }

  @Test
  void partialFinalDurationStillProducesAtLeastOneBucket() {
    List<Bucket> buckets = TwapScheduleBuilder.build(50, 5, 10, 0L);
    assertEquals(1, buckets.size());
    assertEquals(50, buckets.get(0).getTargetQuantity());
  }
}
