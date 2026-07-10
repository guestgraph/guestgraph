package io.guestgraph.persistence.entity;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.domain.RuleMatchKind;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A tenant identifier-trust declaration; created whole, removed whole — never mutated. */
@Entity
@Immutable
@Table(name = "identifier_quality_rule")
public class IdentifierQualityRuleEntity {

  @Id private UUID id;
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  private IdentifierType identifierType;

  @Enumerated(EnumType.STRING)
  private RuleMatchKind matchKind;

  private String valueNormalized;

  @Enumerated(EnumType.STRING)
  private RuleEffect rule;

  private String note;
  private Instant createdAt;

  protected IdentifierQualityRuleEntity() {}

  public IdentifierQualityRuleEntity(
      UUID id,
      UUID tenantId,
      IdentifierType identifierType,
      RuleMatchKind matchKind,
      String valueNormalized,
      RuleEffect rule,
      String note,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.identifierType = identifierType;
    this.matchKind = matchKind;
    this.valueNormalized = valueNormalized;
    this.rule = rule;
    this.note = note;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public IdentifierType getIdentifierType() {
    return identifierType;
  }

  public RuleMatchKind getMatchKind() {
    return matchKind;
  }

  public String getValueNormalized() {
    return valueNormalized;
  }

  public RuleEffect getRule() {
    return rule;
  }

  public String getNote() {
    return note;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
