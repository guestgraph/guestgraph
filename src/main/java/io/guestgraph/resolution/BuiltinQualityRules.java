package io.guestgraph.resolution;

import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.domain.RuleMatchKind;
import java.util.List;

/**
 * Product-shipped identifier-trust defaults, active for every tenant (FR-014): known OTA
 * relay/masking domains whose addresses are per-booking aliases — sometimes reused across different
 * people — and must never drive a merge on their own. Code constants, not rows: list updates ship
 * with the product, no migration; surfaced read-only by the rules API.
 */
public final class BuiltinQualityRules {

  private static final List<String> OTA_RELAY_DOMAINS =
      List.of(
          "guest.booking.com",
          "mchat.booking.com",
          "guest.airbnbmail.com",
          "reply.airbnb.com",
          "expediapartnercentral.com",
          "guest.expediamail.com",
          "guest.agoda.com",
          "mail.agoda.com",
          "guest.trip.com");

  public static final List<IdentifierQualityRule> RULES =
      OTA_RELAY_DOMAINS.stream()
          .map(
              domain ->
                  new IdentifierQualityRule(
                      null,
                      null,
                      IdentifierType.EMAIL,
                      RuleMatchKind.EMAIL_DOMAIN,
                      domain,
                      RuleEffect.MASKED_ALIAS,
                      "built-in OTA relay domain",
                      true,
                      null))
          .toList();

  private BuiltinQualityRules() {}
}
