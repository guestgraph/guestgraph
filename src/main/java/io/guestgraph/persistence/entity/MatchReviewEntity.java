package io.guestgraph.persistence.entity;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.ReviewStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Review-queue row. The only state change is the single PENDING→decided transition,
 * done via the explicit conditional bulk update in MatchReviewRepo — no setters.
 */
@Entity
@Table(name = "match_review")
public class MatchReviewEntity {

    @Id
    private UUID id;
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    private ReviewStatus status;

    private UUID sourceRecordId;
    private UUID candidateGuestId;

    @Enumerated(EnumType.STRING)
    private IdentifierType identifierType;

    private String identifierValue;
    private String reason;
    private String matcherName;
    private BigDecimal confidence;
    private Instant createdAt;
    private Instant decidedAt;
    private UUID decisionEventId;

    protected MatchReviewEntity() {
    }

    public MatchReviewEntity(UUID id, UUID tenantId, ReviewStatus status, UUID sourceRecordId, UUID candidateGuestId,
            IdentifierType identifierType, String identifierValue, String reason, String matcherName,
            BigDecimal confidence, Instant createdAt, Instant decidedAt, UUID decisionEventId) {
        this.id = id;
        this.tenantId = tenantId;
        this.status = status;
        this.sourceRecordId = sourceRecordId;
        this.candidateGuestId = candidateGuestId;
        this.identifierType = identifierType;
        this.identifierValue = identifierValue;
        this.reason = reason;
        this.matcherName = matcherName;
        this.confidence = confidence;
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
        this.decisionEventId = decisionEventId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public UUID getSourceRecordId() {
        return sourceRecordId;
    }

    public UUID getCandidateGuestId() {
        return candidateGuestId;
    }

    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public String getReason() {
        return reason;
    }

    public String getMatcherName() {
        return matcherName;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecisionEventId() {
        return decisionEventId;
    }
}
