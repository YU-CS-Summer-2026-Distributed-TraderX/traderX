package finos.traderx.algoengine.volume;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** research.md Decision 7: deterministic U-shaped intraday curve, no external data — VWAP's
 * default source and the fallback {@link DuckDbVolumeProfileSource} uses when a security has no
 * matching historical rows. */
@Component
public class SyntheticVolumeProfileSource implements VolumeProfileSource {

  @Override
  public List<Double> bucketWeights(String security, int bucketCount) {
    if (bucketCount == 1) {
      return List.of(1.0);
    }
    List<Double> raw = new ArrayList<>(bucketCount);
    double sum = 0;
    for (int i = 0; i < bucketCount; i++) {
      double x = (double) i / (bucketCount - 1) - 0.5; // -0.5 .. 0.5
      double w = 1 + 4 * x * x; // 1.0 at the midpoint, up to 2.0 at the open/close
      raw.add(w);
      sum += w;
    }
    List<Double> normalized = new ArrayList<>(bucketCount);
    for (double w : raw) {
      normalized.add(w / sum);
    }
    return normalized;
  }
}
