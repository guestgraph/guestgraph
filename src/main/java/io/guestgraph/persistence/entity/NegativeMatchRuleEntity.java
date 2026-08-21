package io.guestgraph.persistence.entity;

import io.guestgraph.domain.ActorType;
import io.guestgraph.domain.NegativeRuleOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A steward split that sticks (R2-1). Created whole; the only permitted change is being lifted,
 * which stamps who overrode the split and when (FR-016a) — so unlike the other companions this one
 * cannot be {@code @Immutable}.
 */
@Entity
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

  @Enumerated(EnumType.STRING)
  private ActorType actorType;

  private String actorId;

  private Instant createdAt;

  private Instant liftedAt;

  @Enumerated(EnumType.STRING)
  private ActorType liftedActorType;

  private String liftedActorId;

  protected NegativeMatchRuleEntity() {}

  public NegativeMatchRuleEntity(
      UUID id,
      UUID tenantId,
      UUID recordA,
      UUID recordB,
      NegativeRuleOrigin origin,
      ActorType actorType,
      String actorId,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.recordA = recordA;
    this.recordB = recordB;
    this.origin = origin;
    this.actorType = actorType;
    this.actorId = actorId;
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

  public ActorType getActorType() {
    return actorType;
  }

  public String getActorId() {
    return actorId;
  }

  public Instant getLiftedAt() {
    return liftedAt;
  }

  public ActorType getLiftedActorType() {
    return liftedActorType;
  }

  public String getLiftedActorId() {
    return liftedActorId;
  }

  public NegativeRuleOrigin getOrigin() {
    return origin;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
