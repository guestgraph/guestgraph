package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A steward split that sticks (R2-1): the clusters containing {@code recordA} and {@code recordB}
 * must never be silently merged, by any matcher. Record ids are immutable, so the rule survives
 * merges and unmerges; {@code recordA < recordB}.
 */
public record NegativeMatchRule(
    UUID id,
    UUID tenantId,
    UUID recordA,
    UUID recordB,
    NegativeRuleOrigin origin,
    Instant createdAt) {

  public static NegativeMatchRule of(
      UUID tenantId, UUID first, UUID second, NegativeRuleOrigin origin) {
    if (first.equals(second)) {
      throw new IllegalArgumentException("A do-not-merge rule needs two distinct records");
    }
    // Order by canonical string: Java's UUID.compareTo uses SIGNED longs and disagrees
    // with Postgres's byte-wise uuid ordering — the DB CHECK (record_a < record_b)
    // follows Postgres semantics.
    boolean firstIsLower = first.toString().compareTo(second.toString()) < 0;
    UUID a = firstIsLower ? first : second;
    UUID b = firstIsLower ? second : first;
    return new NegativeMatchRule(UUID.randomUUID(), tenantId, a, b, origin, Instant.now());
  }
}
