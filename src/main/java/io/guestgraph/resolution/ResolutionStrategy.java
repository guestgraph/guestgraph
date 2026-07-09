package io.guestgraph.resolution;

import io.guestgraph.domain.SourceRecord;
import java.util.List;

/**
 * Candidate scoring contract (Constitution IV, research R1/plan): candidates in,
 * scored decisions out. The slice-2 probabilistic matcher is a new implementation
 * of this interface — not a redesign.
 */
public interface ResolutionStrategy {

    String name();

    List<MatchDecision> score(SourceRecord record, List<MatchCandidate> candidates, int reviewThreshold);
}
