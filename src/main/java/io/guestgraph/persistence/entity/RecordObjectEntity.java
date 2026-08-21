package io.guestgraph.persistence.entity;

import io.guestgraph.domain.ObjectRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The business object a record describes — an optional companion of {@code source_record}, frozen
 * at ingest like {@link RecordBlockKeyEntity} and {@link RecordIdentifierEntity}.
 *
 * <p>{@code sourceSystemId} is denormalised from the parent record: the object namespace is
 * (tenant, source system, object type, object id), so keeping it here makes the roster lookup a
 * single-table index scan.
 */
@Entity
@Immutable
@Table(name = "record_object")
public class RecordObjectEntity {

  @Id private UUID id;
  private UUID tenantId;
  private UUID sourceRecordId;
  private UUID sourceSystemId;
  private String objectType;
  private String objectId;

  @Enumerated(EnumType.STRING)
  private ObjectRole objectRole;

  private Integer objectPosition;
  private Instant objectVersion;
  private Instant businessStart;
  private Instant businessEnd;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected RecordObjectEntity() {}

  public RecordObjectEntity(
      UUID id,
      UUID tenantId,
      UUID sourceRecordId,
      UUID sourceSystemId,
      String objectType,
      String objectId,
      ObjectRole objectRole,
      Integer objectPosition,
      Instant objectVersion,
      Instant businessStart,
      Instant businessEnd) {
    this.id = id;
    this.tenantId = tenantId;
    this.sourceRecordId = sourceRecordId;
    this.sourceSystemId = sourceSystemId;
    this.objectType = objectType;
    this.objectId = objectId;
    this.objectRole = objectRole;
    this.objectPosition = objectPosition;
    this.objectVersion = objectVersion;
    this.businessStart = businessStart;
    this.businessEnd = businessEnd;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getSourceRecordId() {
    return sourceRecordId;
  }

  public UUID getSourceSystemId() {
    return sourceSystemId;
  }

  public String getObjectType() {
    return objectType;
  }

  public String getObjectId() {
    return objectId;
  }

  public ObjectRole getObjectRole() {
    return objectRole;
  }

  public Integer getObjectPosition() {
    return objectPosition;
  }

  public Instant getObjectVersion() {
    return objectVersion;
  }

  public Instant getBusinessStart() {
    return businessStart;
  }

  public Instant getBusinessEnd() {
    return businessEnd;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
