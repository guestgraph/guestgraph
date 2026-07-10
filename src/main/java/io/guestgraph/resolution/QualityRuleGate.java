package io.guestgraph.resolution;

import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.normalize.NameNormalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies identifier quality rules at matching time (R2-4): rules added today silence identifiers
 * written last year, no backfill. IGNORE and MASKED_ALIAS remove the identifier from candidate
 * generation; PERFECT_MATCH downgrades merges without exact (diacritic-folded) name agreement to
 * review.
 */
public class QualityRuleGate {

  /**
   * The effect governing this identifier, if any rule matches. First match wins and built-ins are
   * listed before tenant rules — tenants can add rules but cannot override the shipped OTA
   * relay-domain defaults in this slice (spec assumption).
   */
  public Optional<RuleEffect> effectFor(
      List<IdentifierQualityRule> rules, NormalizedIdentifier identifier) {
    for (IdentifierQualityRule rule : rules) {
      if (rule.matchesExact(identifier.type(), identifier.value())
          || rule.matchesEmailDomain(
              identifier.type() == IdentifierType.EMAIL ? identifier.value() : null)) {
        return Optional.of(rule.rule());
      }
    }
    return Optional.empty();
  }

  /** PERFECT_MATCH: exact folded-name agreement between the record and the guest profile. */
  public boolean namesAgreeExactly(Map<String, Object> recordFields, Map<String, Object> profile) {
    String firstA = NameNormalizer.fold(text(recordFields, "firstName"));
    String lastA = NameNormalizer.fold(text(recordFields, "lastName"));
    String firstB = NameNormalizer.fold(text(profile, "firstName"));
    String lastB = NameNormalizer.fold(text(profile, "lastName"));
    if (firstA == null || lastA == null || firstB == null || lastB == null) {
      return false; // cannot verify agreement → not a perfect match
    }
    return firstA.equals(firstB) && lastA.equals(lastB);
  }

  private String text(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof String s && !s.isBlank() ? s : null;
  }
}
