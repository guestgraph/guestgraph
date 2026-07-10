package io.guestgraph.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_system")
public class SourceSystemEntity {

  @Id private UUID id;
  private UUID tenantId;
  private String code;
  private String name;
  private Instant createdAt;

  protected SourceSystemEntity() {}

  public SourceSystemEntity(UUID id, UUID tenantId, String code, String name, Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.code = code;
    this.name = name;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
