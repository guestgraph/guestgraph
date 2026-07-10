package io.guestgraph.persistence.entity;

import io.guestgraph.domain.NegativeRuleOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A steward split that sticks (R2-1); created whole, removed whole — never mutated. */
@Entity
@Immutable
@Table(name = "negative_match_rule")
public class NegativeMatchRuleEntity {

  @Id private UUID id;
  private UUID tenantId;

  // Trailing single capitals defeat the camel-case naming strategy — name explicitly.
  @Column(name = "record_a")
  private UUID recordA;

  @Column(name = "record_b")
  private UUID recordB;

  @Enumerated(EnumType.STRING)
  private NegativeRuleOrigin origin;

  private Instant createdAt;

  protected NegativeMatchRuleEntity() {}

  public NegativeMatchRuleEntity(
      UUID id,
      UUID tenantId,
      UUID recordA,
      UUID recordB,
      NegativeRuleOrigin origin,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.recordA = recordA;
    this.recordB = recordB;
    this.origin = origin;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getRecordA() {
    return recordA;
  }

  public UUID getRecordB() {
    return recordB;
  }

  public NegativeRuleOrigin getOrigin() {
    return origin;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
