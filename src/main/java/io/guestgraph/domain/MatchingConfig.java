package io.guestgraph.domain;

import java.math.BigDecimal;

/**
 * Per-tenant score bands (FR-005): score ≥ autoMergeThreshold → merge automatically (ships at 1.0 =
 * off); ≥ reviewFloor → review queue; below → discard. At-threshold belongs to the higher band.
 * reviewThreshold is slice-1's identifier-sharing count.
 */
public record MatchingConfig(
    BigDecimal autoMergeThreshold, BigDecimal reviewFloor, int reviewThreshold) {

  public boolean autoMergeEnabledFor(BigDecimal score) {
    return score.compareTo(autoMergeThreshold) >= 0;
  }

  public boolean reviewableFor(BigDecimal score) {
    return score.compareTo(reviewFloor) >= 0;
  }
}
