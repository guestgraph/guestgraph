package io.guestgraph.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The golden profile row. {@code profile} is derived by survivorship and only ever rewritten via
 * the explicit bulk update in GuestRepo — no managed-entity mutation.
 */
@Entity
@Table(name = "guest")
public class GuestEntity {

  @Id private UUID id;
  private UUID tenantId;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> profile;

  private Instant createdAt;
  private Instant updatedAt;

  protected GuestEntity() {}

  public GuestEntity(
      UUID id, UUID tenantId, Map<String, Object> profile, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.profile = profile;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public Map<String, Object> getProfile() {
    return profile;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
