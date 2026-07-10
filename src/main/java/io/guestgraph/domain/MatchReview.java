package io.guestgraph.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A suspicious candidate match parked for human confirm/reject (Constitution IV). */
public record MatchReview(
    UUID id,
    UUID tenantId,
    ReviewStatus status,
    UUID sourceRecordId,
    UUID candidateGuestId,
    IdentifierType identifierType,
    String identifierValue,
    String reason,
    String matcherName,
    BigDecimal confidence,
    Instant createdAt,
    Instant decidedAt,
    UUID decisionEventId) {}
