package io.guestgraph.resolution;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.NormalizedIdentifier;
import java.util.Map;
import java.util.UUID;

/**
 * A guest that might be the record's person. Two origins: an exact shared identifier (deterministic
 * path, slice 1) or a shared block key (fuzzy path, slice 2 — carries the guest's golden-profile
 * snapshot for scoring). Exactly one of {@code identifier} / {@code blockKey} is set.
 */
public record MatchCandidate(
    UUID guestId,
    NormalizedIdentifier identifier,
    int recordsSharingIdentifier,
    BlockKey blockKey,
    Map<String, Object> guestProfile) {

  public static MatchCandidate exact(
      UUID guestId, NormalizedIdentifier identifier, int recordsSharingIdentifier) {
    return new MatchCandidate(guestId, identifier, recordsSharingIdentifier, null, null);
  }

  public static MatchCandidate fuzzy(
      UUID guestId, BlockKey blockKey, Map<String, Object> guestProfile) {
    return new MatchCandidate(guestId, null, 0, blockKey, guestProfile);
  }

  public boolean isExact() {
    return identifier != null;
  }
}
