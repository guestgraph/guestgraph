package io.guestgraph.resolution;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.MatchSignals;
import io.guestgraph.domain.NormalizedIdentifier;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A scored decision about one candidate: merge it, or park it for human review. Carries matcher
 * name + confidence so every executed decision is auditable (Constitution IV). Deterministic
 * decisions reference the shared {@code identifier}; fuzzy decisions reference the {@code blockKey}
 * that produced the candidate and the per-signal {@code signals} breakdown behind the score.
 */
public record MatchDecision(
    UUID guestId,
    NormalizedIdentifier identifier,
    BlockKey blockKey,
    Disposition disposition,
    String matcherName,
    BigDecimal confidence,
    int recordsSharingIdentifier,
    String reason,
    MatchSignals signals) {

  public enum Disposition {
    MATCH,
    REVIEW
  }

  public static MatchDecision exact(
      UUID guestId,
      NormalizedIdentifier identifier,
      Disposition disposition,
      String matcherName,
      BigDecimal confidence,
      int recordsSharingIdentifier,
      String reason) {
    return new MatchDecision(
        guestId,
        identifier,
        null,
        disposition,
        matcherName,
        confidence,
        recordsSharingIdentifier,
        reason,
        null);
  }

  public static MatchDecision fuzzy(
      UUID guestId,
      BlockKey blockKey,
      Disposition disposition,
      String matcherName,
      MatchSignals signals,
      String reason) {
    return new MatchDecision(
        guestId, null, blockKey, disposition, matcherName, signals.score(), 0, reason, signals);
  }

  /** The identifier-or-block-key type name, for review rows and evidence. */
  public String originType() {
    return identifier != null ? identifier.type().name() : blockKey.type().name();
  }

  public String originValue() {
    return identifier != null ? identifier.value() : blockKey.value();
  }
}
