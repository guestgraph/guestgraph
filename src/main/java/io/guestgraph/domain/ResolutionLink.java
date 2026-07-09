package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

/** Association between a source record and the guest it currently belongs to. */
public record ResolutionLink(
        UUID id,
        UUID tenantId,
        UUID sourceRecordId,
        UUID guestId,
        UUID createdByEventId,
        Instant createdAt) {
}
