package finos.traderx.algoengine.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticVolumeProfileSourceTest {
  private final SyntheticVolumeProfileSource source = new SyntheticVolumeProfileSource();

  @Test
  void weightsSumToOneAndAreNonNegative() {
    for (int n : new int[] {1, 2, 3, 6, 12}) {
      List<Double> weights = source.bucketWeights("IBM", n);
      assertEquals(n, weights.size());
      double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
      assertEquals(1.0, sum, 1e-9);
      weights.forEach(w -> assertTrue(w >= 0));
    }
  }

  @Test
  void curveIsUShapedHeavierAtOpenAndClose() {
    List<Double> weights = source.bucketWeights("IBM", 5);
    double midpoint = weights.get(2);
    assertTrue(weights.get(0) > midpoint);
    assertTrue(weights.get(4) > midpoint);
  }
}
