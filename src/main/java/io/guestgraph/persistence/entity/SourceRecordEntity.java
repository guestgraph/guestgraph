package io.guestgraph.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable at every level (Constitution II): Hibernate refuses updates via {@link Immutable}, and
 * the {@code source_record_immutable} trigger enforces the same at the database.
 */
@Entity
@Immutable
@Table(name = "source_record")
public class SourceRecordEntity {

  @Id private UUID id;
  private UUID tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_system_id")
  private SourceSystemEntity sourceSystem;

  private String externalKey;

  /**
   * The original payload — parsed once at ingest and stored semantically equal (R2); never mutated.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> extracted;

  private Instant recordTimestamp;
  private boolean needsReview;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> needsReviewReasons;

  private Instant receivedAt;

  @OneToMany(mappedBy = "sourceRecord", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
  private List<RecordIdentifierEntity> identifiers = new ArrayList<>();

  protected SourceRecordEntity() {}

  public SourceRecordEntity(
      UUID id,
      UUID tenantId,
      SourceSystemEntity sourceSystem,
      String externalKey,
      String payload,
      Map<String, Object> extracted,
      Instant recordTimestamp,
      boolean needsReview,
      List<String> needsReviewReasons,
      Instant receivedAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.sourceSystem = sourceSystem;
    this.externalKey = externalKey;
    this.payload = payload;
    this.extracted = extracted;
    this.recordTimestamp = recordTimestamp;
    this.needsReview = needsReview;
    this.needsReviewReasons = needsReviewReasons;
    this.receivedAt = receivedAt;
  }

  /** Wires the bidirectional association at construction time; never called on a loaded entity. */
  public void addIdentifier(RecordIdentifierEntity identifier) {
    identifiers.add(identifier);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public SourceSystemEntity getSourceSystem() {
    return sourceSystem;
  }

  public String getExternalKey() {
    return externalKey;
  }

  public String getPayload() {
    return payload;
  }

  public Map<String, Object> getExtracted() {
    return extracted;
  }

  public Instant getRecordTimestamp() {
    return recordTimestamp;
  }

  public boolean isNeedsReview() {
    return needsReview;
  }

  public List<String> getNeedsReviewReasons() {
    return needsReviewReasons;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public List<RecordIdentifierEntity> getIdentifiers() {
    return identifiers;
  }
}
