package io.guestgraph.resolution;

import io.guestgraph.domain.IngestStatus;
import java.util.List;
import java.util.UUID;

/** Result of resolving one source record. */
public record ResolutionOutcome(IngestStatus status, UUID guestId, List<UUID> pendingReviewIds) {
}
