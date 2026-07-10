package io.guestgraph.persistence.entity;

import io.guestgraph.domain.MergeEventKind;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record (Constitution IV) — the basis for explain and unmerge. */
@Entity
@Immutable
@Table(name = "merge_event")
public class MergeEventEntity {

  @Id private UUID id;
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  private MergeEventKind kind;

  private UUID guestId;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<UUID> absorbedGuestIds;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<UUID> sourceRecordIds;

  private String matcherName;
  private BigDecimal confidence;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> evidence;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<UUID> excludedGuestIds;

  private Instant createdAt;

  protected MergeEventEntity() {}

  public MergeEventEntity(
      UUID id,
      UUID tenantId,
      MergeEventKind kind,
      UUID guestId,
      List<UUID> absorbedGuestIds,
      List<UUID> sourceRecordIds,
      String matcherName,
      BigDecimal confidence,
      Map<String, Object> evidence,
      List<UUID> excludedGuestIds,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.kind = kind;
    this.guestId = guestId;
    this.absorbedGuestIds = absorbedGuestIds;
    this.sourceRecordIds = sourceRecordIds;
    this.matcherName = matcherName;
    this.confidence = confidence;
    this.evidence = evidence;
    this.excludedGuestIds = excludedGuestIds;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public MergeEventKind getKind() {
    return kind;
  }

  public UUID getGuestId() {
    return guestId;
  }

  public List<UUID> getAbsorbedGuestIds() {
    return absorbedGuestIds;
  }

  public List<UUID> getSourceRecordIds() {
    return sourceRecordIds;
  }

  public String getMatcherName() {
    return matcherName;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public Map<String, Object> getEvidence() {
    return evidence;
  }

  public List<UUID> getExcludedGuestIds() {
    return excludedGuestIds;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
