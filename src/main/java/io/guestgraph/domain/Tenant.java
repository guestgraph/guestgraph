package io.guestgraph.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Tenant(
    UUID id,
    String slug,
    String name,
    int reviewThreshold,
    BigDecimal autoMergeThreshold,
    BigDecimal reviewFloor,
    Instant createdAt) {}
