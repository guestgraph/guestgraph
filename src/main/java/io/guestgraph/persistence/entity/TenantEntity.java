package io.guestgraph.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Tenants are provisioned by the operator (or seeder), never mutated by the service. */
@Entity
@Immutable
@Table(name = "tenant")
public class TenantEntity {

  @Id private UUID id;
  private String slug;
  private String name;
  private int reviewThreshold;
  private BigDecimal autoMergeThreshold;
  private BigDecimal reviewFloor;
  private Instant createdAt;

  protected TenantEntity() {}

  public UUID getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public int getReviewThreshold() {
    return reviewThreshold;
  }

  public BigDecimal getAutoMergeThreshold() {
    return autoMergeThreshold;
  }

  public BigDecimal getReviewFloor() {
    return reviewFloor;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
