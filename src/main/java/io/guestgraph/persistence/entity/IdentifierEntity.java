package io.guestgraph.persistence.entity;

import io.guestgraph.domain.IdentifierType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A guest's current normalized identifiers — rebuilt (delete + insert) from its
 * linked records on every merge/unmerge, never mutated in place.
 */
@Entity
@Table(name = "identifier")
public class IdentifierEntity {

    @Id
    private UUID id;
    private UUID tenantId;
    private UUID guestId;

    @Enumerated(EnumType.STRING)
    private IdentifierType type;

    private String valueNormalized;

    protected IdentifierEntity() {
    }

    public IdentifierEntity(UUID id, UUID tenantId, UUID guestId, IdentifierType type, String valueNormalized) {
        this.id = id;
        this.tenantId = tenantId;
        this.guestId = guestId;
        this.type = type;
        this.valueNormalized = valueNormalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getGuestId() {
        return guestId;
    }

    public IdentifierType getType() {
        return type;
    }

    public String getValueNormalized() {
        return valueNormalized;
    }
}
