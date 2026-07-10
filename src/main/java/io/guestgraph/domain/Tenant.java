package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

public record Tenant(UUID id, String slug, String name, int reviewThreshold, Instant createdAt) {}
