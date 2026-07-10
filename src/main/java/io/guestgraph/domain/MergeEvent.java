package io.guestgraph.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit record of one resolution decision (Constitution IV): the basis for explain and
 * unmerge. Deterministic decisions carry confidence 1.0.
 */
public record MergeEvent(
    UUID id,
    UUID tenantId,
    MergeEventKind kind,
    UUID guestId,
    List<UUID> absorbedGuestIds,
    List<UUID> sourceRecordIds,
    String matcherName,
    BigDecimal confidence,
    Map<String, Object> evidence,
    List<UUID> excludedGuestIds,
    Instant createdAt) {}
