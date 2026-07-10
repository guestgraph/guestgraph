package io.guestgraph.resolution;

import io.guestgraph.domain.BlockKeyType;
import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.domain.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Deterministic first (unchanged, confidence 1.0), fuzzy on what determinism left undecided (R2-2).
 * The ResolutionStrategy interface is untouched — this is the second implementation slice 1
 * promised.
 */
public class CompositeStrategy implements ResolutionStrategy {

  private final DeterministicMatcher deterministic;
  private final FuzzyMatcher fuzzy;
  private final MatchingPolicy policy;
  private final Function<UUID, MatchingConfig> configByTenant;

  public CompositeStrategy(
      DeterministicMatcher deterministic,
      FuzzyMatcher fuzzy,
      MatchingPolicy policy,
      Function<UUID, MatchingConfig> configByTenant) {
    this.deterministic = deterministic;
    this.fuzzy = fuzzy;
    this.policy = policy;
    this.configByTenant = configByTenant;
  }

  @Override
  public String name() {
    return "composite(" + deterministic.name() + "," + FuzzyMatcher.NAME + ")";
  }

  @Override
  public List<MatchDecision> score(
      SourceRecord record, List<MatchCandidate> candidates, int reviewThreshold) {
    List<MatchCandidate> exact = candidates.stream().filter(MatchCandidate::isExact).toList();
    List<MatchDecision> decisions =
        new ArrayList<>(deterministic.score(record, exact, reviewThreshold));

    // Guests determinism already decided (either way) are out of fuzzy's scope.
    Set<UUID> decidedGuests = new LinkedHashSet<>();
    for (MatchDecision decision : decisions) {
      decidedGuests.add(decision.guestId());
    }

    MatchingConfig config = configByTenant.apply(record.tenantId());
    Set<UUID> scoredGuests = new LinkedHashSet<>();
    for (MatchCandidate candidate : candidates) {
      if (candidate.isExact()
          || decidedGuests.contains(candidate.guestId())
          || !scoredGuests.add(candidate.guestId())) {
        continue; // one score per candidate guest, first block key wins
      }
      fuzzy
          .score(record, candidate)
          .ifPresent(
              signals -> {
                MatchingPolicy.Band band = policy.route(config, signals);
                if (band == MatchingPolicy.Band.AUTO_MERGE
                    && candidate.blockKey().type() == BlockKeyType.EMAIL_MASKED) {
                  band = MatchingPolicy.Band.REVIEW; // masked aliases are review-only (US3)
                }
                switch (band) {
                  case AUTO_MERGE ->
                      decisions.add(
                          MatchDecision.fuzzy(
                              candidate.guestId(),
                              candidate.blockKey(),
                              MatchDecision.Disposition.MATCH,
                              FuzzyMatcher.NAME,
                              signals,
                              signals.summary()));
                  case REVIEW ->
                      decisions.add(
                          MatchDecision.fuzzy(
                              candidate.guestId(),
                              candidate.blockKey(),
                              MatchDecision.Disposition.REVIEW,
                              FuzzyMatcher.NAME,
                              signals,
                              signals.summary()));
                  case DISCARD -> {
                    // below the floor: not evidence enough to bother a steward
                  }
                }
              });
    }
    return decisions;
  }
}
