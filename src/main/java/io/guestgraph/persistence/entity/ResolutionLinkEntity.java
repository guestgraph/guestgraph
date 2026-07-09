package io.guestgraph.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Record→guest association. Not {@code @Immutable}: merges re-point links via the
 * explicit bulk update in ResolutionLinkRepo — still no setters, no managed mutation.
 */
@Entity
@Table(name = "resolution_link")
public class ResolutionLinkEntity {

    @Id
    private UUID id;
    private UUID tenantId;
    private UUID sourceRecordId;
    private UUID guestId;
    private UUID createdByEventId;
    private Instant createdAt;

    protected ResolutionLinkEntity() {
    }

    public ResolutionLinkEntity(UUID id, UUID tenantId, UUID sourceRecordId, UUID guestId, UUID createdByEventId,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.sourceRecordId = sourceRecordId;
        this.guestId = guestId;
        this.createdByEventId = createdByEventId;
        this.createdAt = createdAt;
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

    public UUID getGuestId() {
        return guestId;
    }

    public UUID getCreatedByEventId() {
        return createdByEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
