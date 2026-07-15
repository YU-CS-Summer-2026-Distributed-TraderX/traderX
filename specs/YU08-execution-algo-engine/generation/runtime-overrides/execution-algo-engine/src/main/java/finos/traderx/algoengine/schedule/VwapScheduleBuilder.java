package finos.traderx.algoengine.schedule;

import finos.traderx.algoengine.model.Bucket;
import java.util.ArrayList;
import java.util.List;

/** FR-AE03 / research.md Decision 7: buckets weighted by a {@link
 * finos.traderx.algoengine.volume.VolumeProfileSource} instead of TWAP's equal split. Any
 * remainder from rounding each weighted share down to an integer is added to the last bucket, so
 * every share of the parent quantity is scheduled exactly once — same invariant as TWAP. */
public final class VwapScheduleBuilder {

  private VwapScheduleBuilder() {}

  public static List<Bucket> build(int quantity, List<Double> weights, int bucketSeconds, long startEpochMs) {
    int bucketCount = weights.size();
    List<Bucket> buckets = new ArrayList<>(bucketCount);
    if (bucketCount == 0) {
      return buckets;
    }
    double totalWeight = weights.stream().mapToDouble(w -> Math.max(0.0, w)).sum();
    boolean evenFallback = totalWeight == 0.0;
    int allocated = 0;
    for (int i = 0; i < bucketCount; i++) {
      int qty = (i == bucketCount - 1)
          ? quantity - allocated
          : (int) Math.floor(quantity * (evenFallback
              ? 1.0 / bucketCount
              : Math.max(0.0, weights.get(i)) / totalWeight));
      allocated += qty;
      buckets.add(new Bucket(i, startEpochMs + (long) i * bucketSeconds * 1000L, qty));
    }
    return buckets;
  }
}
