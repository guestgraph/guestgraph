package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant identifier trust (FR-013): IGNORE never connects guests; PERFECT_MATCH connects only
 * with exact name agreement; MASKED_ALIAS (email domains) demotes relay addresses to a weak
 * review-only signal and guards the golden profile. Built-in OTA defaults carry {@code builtin =
 * true} and a null id/tenant.
 */
public record IdentifierQualityRule(
    UUID id,
    UUID tenantId,
    IdentifierType identifierType,
    RuleMatchKind matchKind,
    String valueNormalized,
    RuleEffect rule,
    String note,
    boolean builtin,
    Instant createdAt) {

  public boolean matchesEmailDomain(String emailNormalized) {
    return matchKind == RuleMatchKind.EMAIL_DOMAIN
        && identifierType == IdentifierType.EMAIL
        && emailNormalized != null
        && emailNormalized.endsWith("@" + valueNormalized);
  }

  public boolean matchesExact(IdentifierType type, String valueNorm) {
    return matchKind == RuleMatchKind.EXACT
        && identifierType == type
        && valueNormalized.equals(valueNorm);
  }
}
