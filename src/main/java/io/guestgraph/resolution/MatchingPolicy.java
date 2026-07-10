package io.guestgraph.resolution;

import io.guestgraph.domain.MatchSignals;
import io.guestgraph.domain.MatchingConfig;

/**
 * Routes a fuzzy score into the tenant's three bands (FR-005): ≥ autoMergeThreshold → AUTO_MERGE, ≥
 * reviewFloor → REVIEW, else DISCARD. At-threshold belongs to the higher band. With the shipped
 * threshold of 1.0 and fuzzy scores capped below 1.0, the auto-merge band is empty until a tenant
 * opts in (FR-006).
 */
public class MatchingPolicy {

  public enum Band {
    AUTO_MERGE,
    REVIEW,
    DISCARD
  }

  public Band route(MatchingConfig config, MatchSignals signals) {
    if (config.autoMergeEnabledFor(signals.score())) {
      return Band.AUTO_MERGE;
    }
    if (config.reviewableFor(signals.score())) {
      return Band.REVIEW;
    }
    return Band.DISCARD;
  }
}
