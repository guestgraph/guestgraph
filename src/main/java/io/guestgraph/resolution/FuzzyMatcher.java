package io.guestgraph.resolution;

import io.guestgraph.domain.BlockKeyType;
import io.guestgraph.domain.MatchSignals;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.normalize.NameNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Rule-based fuzzy scoring (fuzzy-rules-v1): feature vector → weighted score (R2-3).
 *
 * <p>Score = weighted average over the signals both sides actually have, damped by evidence
 * coverage (sparse agreement is good evidence, not certainty), hard-penalized on birthdate conflict
 * (different-person evidence outweighs name similarity), and capped strictly below 1.0 — certainty
 * is reserved for deterministic identifiers, which also makes auto_merge_threshold = 1.0 genuinely
 * mean "off" (FR-006).
 */
public class FuzzyMatcher {

  public static final String NAME = "fuzzy-rules-v1";

  static final double WEIGHT_NAME = 0.45;
  static final double WEIGHT_BIRTHDATE = 0.25;
  static final double WEIGHT_PHONE = 0.15;
  static final double WEIGHT_EMAIL = 0.10;
  static final double WEIGHT_ADDRESS = 0.05;
  static final double BIRTHDATE_CONFLICT_PENALTY = 0.4;
  static final double COVERAGE_BASE = 0.85;
  static final double MAX_FUZZY_SCORE = 0.999;

  private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

  /** Empty when the pair lacks the minimum evidence (a name plus one other signal). */
  public Optional<MatchSignals> score(SourceRecord record, MatchCandidate candidate) {
    Map<String, Object> a = record.extracted();
    Map<String, Object> b = candidate.guestProfile();
    if (b == null) {
      return Optional.empty();
    }

    List<MatchSignals.Contribution> contributions = new ArrayList<>();
    boolean birthdateConflict = false;

    Double name = nameSimilarity(a, b);
    if (name == null) {
      return Optional.empty(); // no candidate without a name signal
    }
    contributions.add(new MatchSignals.Contribution("name", name, WEIGHT_NAME));

    String birthdateA = text(a, "birthdate");
    String birthdateB = text(b, "birthdate");
    if (birthdateA != null && birthdateB != null) {
      boolean equal = birthdateA.equals(birthdateB);
      contributions.add(
          new MatchSignals.Contribution("birthdate", equal ? 1.0 : 0.0, WEIGHT_BIRTHDATE));
      birthdateConflict = !equal;
    }

    String phoneA = digitsSuffix(text(a, "phone"));
    String phoneB = digitsSuffix(text(b, "phone"));
    if (phoneA != null && phoneB != null) {
      contributions.add(
          new MatchSignals.Contribution(
              "phoneSuffix", phoneA.equals(phoneB) ? 1.0 : 0.0, WEIGHT_PHONE));
    }

    String emailA = realEmail(a);
    String emailB = realEmail(b);
    if (emailA != null && emailB != null) {
      contributions.add(
          new MatchSignals.Contribution("email", JARO_WINKLER.apply(emailA, emailB), WEIGHT_EMAIL));
    }

    String cityA = NameNormalizer.fold(text(a, "city"));
    String cityB = NameNormalizer.fold(text(b, "city"));
    if (cityA != null && cityB != null) {
      contributions.add(
          new MatchSignals.Contribution(
              "address", cityA.equals(cityB) ? 1.0 : 0.0, WEIGHT_ADDRESS));
    }

    boolean sharedMaskedAlias =
        candidate.blockKey() != null && candidate.blockKey().type() == BlockKeyType.EMAIL_MASKED;
    if (sharedMaskedAlias) {
      // The shared alias is itself the corroborating signal — weight 0: candidacy, not score.
      contributions.add(new MatchSignals.Contribution("maskedAlias", 1.0, 0.0));
    }
    if (contributions.size() < 2) {
      return Optional.empty(); // name alone is not evidence enough
    }

    double weightSum = contributions.stream().mapToDouble(MatchSignals.Contribution::weight).sum();
    double weightedAvg =
        contributions.stream().mapToDouble(c -> c.value() * c.weight()).sum() / weightSum;
    double coverageFactor = COVERAGE_BASE + (1.0 - COVERAGE_BASE) * weightSum;
    double score = weightedAvg * coverageFactor;
    if (birthdateConflict) {
      score *= BIRTHDATE_CONFLICT_PENALTY;
    }
    return Optional.of(MatchSignals.of(contributions, Math.min(score, MAX_FUZZY_SCORE)));
  }

  private Double nameSimilarity(Map<String, Object> a, Map<String, Object> b) {
    String firstA = NameNormalizer.fold(text(a, "firstName"));
    String lastA = NameNormalizer.fold(text(a, "lastName"));
    String firstB = NameNormalizer.fold(text(b, "firstName"));
    String lastB = NameNormalizer.fold(text(b, "lastName"));
    if ((firstA == null && lastA == null) || (firstB == null && lastB == null)) {
      return null;
    }
    String fullA = join(firstA, lastA);
    String fullB = join(firstB, lastB);
    String fullBSwapped = join(lastB, firstB);
    return Math.max(JARO_WINKLER.apply(fullA, fullB), JARO_WINKLER.apply(fullA, fullBSwapped));
  }

  private String join(String first, String last) {
    if (first == null) {
      return last;
    }
    if (last == null) {
      return first;
    }
    return first + " " + last;
  }

  private String digitsSuffix(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    return digits.length() >= 7 ? digits.substring(digits.length() - 7) : null;
  }

  private String realEmail(Map<String, Object> fields) {
    if (Boolean.TRUE.equals(fields.get("emailMasked"))) {
      return null;
    }
    return text(fields, "email");
  }

  private String text(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof String s && !s.isBlank() ? s : null;
  }
}
