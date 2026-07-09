package io.guestgraph.persistence.entity;

import io.guestgraph.domain.IdentifierType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A record's normalized identifier contribution — frozen at ingest with its record. */
@Entity
@Immutable
@Table(name = "record_identifier")
public class RecordIdentifierEntity {

  @Id private UUID id;
  private UUID tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_record_id")
  private SourceRecordEntity sourceRecord;

  @Enumerated(EnumType.STRING)
  private IdentifierType type;

  private String valueNormalized;

  protected RecordIdentifierEntity() {}

  public RecordIdentifierEntity(
      UUID id,
      UUID tenantId,
      SourceRecordEntity sourceRecord,
      IdentifierType type,
      String valueNormalized) {
    this.id = id;
    this.tenantId = tenantId;
    this.sourceRecord = sourceRecord;
    this.type = type;
    this.valueNormalized = valueNormalized;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public SourceRecordEntity getSourceRecord() {
    return sourceRecord;
  }

  public IdentifierType getType() {
    return type;
  }

  public String getValueNormalized() {
    return valueNormalized;
  }
}
