package io.guestgraph.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The resolved person; {@code profile} is derived via survivorship rules, never hand-edited. */
public record Guest(UUID id, UUID tenantId, Map<String, Object> profile, Instant createdAt, Instant updatedAt) {
}
