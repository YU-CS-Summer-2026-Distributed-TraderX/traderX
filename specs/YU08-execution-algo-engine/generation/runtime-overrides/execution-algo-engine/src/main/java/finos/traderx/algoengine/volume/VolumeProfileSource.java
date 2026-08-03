package finos.traderx.algoengine.volume;

import java.util.List;

/** research.md Decision 7 / ADR-031: pluggable volume weighting for VWAP scheduling. Weights sum
 * to 1 (within floating-point tolerance) and have exactly {@code bucketCount} entries. */
public interface VolumeProfileSource {
  List<Double> bucketWeights(String security, int bucketCount);
}
