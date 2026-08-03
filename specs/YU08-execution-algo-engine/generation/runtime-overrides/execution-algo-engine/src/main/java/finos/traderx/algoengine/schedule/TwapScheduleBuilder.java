package finos.traderx.algoengine.schedule;

import finos.traderx.algoengine.model.Bucket;
import java.util.ArrayList;
import java.util.List;

/** FR-AE02 / research.md Decision 6: equal-quantity time buckets, remainder on the last bucket. */
public final class TwapScheduleBuilder {

  private TwapScheduleBuilder() {}

  public static List<Bucket> build(int quantity, int durationSeconds, int bucketSeconds, long startEpochMs) {
    int bucketCount = Math.max(1, (int) Math.ceil((double) durationSeconds / bucketSeconds));
    int base = quantity / bucketCount;
    int remainder = quantity - base * bucketCount;

    List<Bucket> buckets = new ArrayList<>(bucketCount);
    for (int i = 0; i < bucketCount; i++) {
      int qty = base + (i == bucketCount - 1 ? remainder : 0);
      buckets.add(new Bucket(i, startEpochMs + (long) i * bucketSeconds * 1000L, qty));
    }
    return buckets;
  }
}
