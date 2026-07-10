package io.guestgraph.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The per-signal breakdown behind a fuzzy score (FR-003) — what explain and the review queue show a
 * steward. The contributions ARE the arithmetic: score is the weighted average of signal values
 * over the weights of the signals that were present.
 */
public record MatchSignals(List<Contribution> contributions, BigDecimal score) {

  public record Contribution(String signal, double value, double weight) {}

  public static MatchSignals of(List<Contribution> contributions, double rawScore) {
    return new MatchSignals(
        List.copyOf(contributions), BigDecimal.valueOf(rawScore).setScale(3, RoundingMode.HALF_UP));
  }

  /** Evidence shape persisted on merge events and reviews. */
  public Map<String, Object> asEvidence() {
    Map<String, Object> signals = new LinkedHashMap<>();
    for (Contribution c : contributions) {
      signals.put(c.signal(), Map.of("value", round3(c.value()), "weight", c.weight()));
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("score", score);
    evidence.put("signals", signals);
    return evidence;
  }

  /** Human-readable one-liner for review reasons. */
  public String summary() {
    return "score "
        + score
        + " — "
        + contributions.stream()
            .map(c -> c.signal() + " " + round3(c.value()) + "×" + c.weight())
            .collect(Collectors.joining(", "));
  }

  private static double round3(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }
}
