package io.guestgraph.resolution;

import io.guestgraph.domain.NormalizedIdentifier;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A scored decision about one candidate: merge it, or park it for human review. Carries matcher
 * name + confidence so every executed decision is auditable (Constitution IV) and probabilistic
 * matchers plug in without redesign.
 */
public record MatchDecision(
    UUID guestId,
    NormalizedIdentifier identifier,
    Disposition disposition,
    String matcherName,
    BigDecimal confidence,
    int recordsSharingIdentifier,
    String reason) {

  public enum Disposition {
    MATCH,
    REVIEW
  }
}
