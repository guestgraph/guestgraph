package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

public record SourceSystem(UUID id, UUID tenantId, String code, String name, Instant createdAt) {
}
