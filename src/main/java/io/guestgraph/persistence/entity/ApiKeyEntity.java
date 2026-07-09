package io.guestgraph.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read-only within the service; keys are provisioned by the operator (or seeder). */
@Entity
@Table(name = "api_key")
public class ApiKeyEntity {

    @Id
    private UUID id;
    private UUID tenantId;
    private String keyHash;
    private String label;
    private Instant createdAt;
    private Instant revokedAt;

    protected ApiKeyEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
