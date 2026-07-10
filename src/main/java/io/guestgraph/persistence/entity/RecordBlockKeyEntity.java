package io.guestgraph.persistence.entity;

import io.guestgraph.domain.BlockKeyType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A record's blocking-key contribution (fuzzy candidate discovery) — frozen at ingest. */
@Entity
@Immutable
@Table(name = "record_block_key")
public class RecordBlockKeyEntity {

  @Id private UUID id;
  private UUID tenantId;
  private UUID sourceRecordId;

  @Enumerated(EnumType.STRING)
  private BlockKeyType type;

  private String valueNormalized;

  protected RecordBlockKeyEntity() {}

  public RecordBlockKeyEntity(
      UUID id, UUID tenantId, UUID sourceRecordId, BlockKeyType type, String valueNormalized) {
    this.id = id;
    this.tenantId = tenantId;
    this.sourceRecordId = sourceRecordId;
    this.type = type;
    this.valueNormalized = valueNormalized;
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

  public BlockKeyType getType() {
    return type;
  }

  public String getValueNormalized() {
    return valueNormalized;
  }
}
