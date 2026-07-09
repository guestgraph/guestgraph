package io.guestgraph.resolution;

import io.guestgraph.domain.SourceRecord;
import java.math.BigDecimal;
import java.util.List;

/**
 * Exact matching on shared strong identifiers; every decision has confidence 1.0. An identifier
 * shared by more records than the tenant's review threshold is a classic shared-address signal
 * (family, agency, front desk) — those candidates are parked for human review instead of merged
 * (research R9).
 */
public class DeterministicMatcher implements ResolutionStrategy {

  public static final String NAME = "deterministic-identifier-v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<MatchDecision> score(
      SourceRecord record, List<MatchCandidate> candidates, int reviewThreshold) {
    return candidates.stream()
        .map(
            candidate -> {
              boolean suspicious = candidate.recordsSharingIdentifier() > reviewThreshold;
              return new MatchDecision(
                  candidate.guestId(),
                  candidate.identifier(),
                  suspicious ? MatchDecision.Disposition.REVIEW : MatchDecision.Disposition.MATCH,
                  NAME,
                  BigDecimal.ONE,
                  candidate.recordsSharingIdentifier(),
                  suspicious
                      ? "identifier shared by %d records (threshold %d)"
                          .formatted(candidate.recordsSharingIdentifier(), reviewThreshold)
                      : "exact identifier match");
            })
        .toList();
  }
}
