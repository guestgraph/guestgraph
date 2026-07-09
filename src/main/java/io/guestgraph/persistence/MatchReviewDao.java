package io.guestgraph.persistence;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.ReviewStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MatchReviewDao {

    private final JdbcClient jdbc;

    public MatchReviewDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(MatchReview review) {
        jdbc.sql("""
                        INSERT INTO match_review (id, tenant_id, status, source_record_id, candidate_guest_id,
                                                  identifier_type, identifier_value, reason, matcher_name, confidence)
                        VALUES (:id, :tenantId, :status, :sourceRecordId, :candidateGuestId,
                                :identifierType, :identifierValue, :reason, :matcherName, :confidence)
                        """)
                .param("id", review.id())
                .param("tenantId", review.tenantId())
                .param("status", review.status().name())
                .param("sourceRecordId", review.sourceRecordId())
                .param("candidateGuestId", review.candidateGuestId())
                .param("identifierType", review.identifierType().name())
                .param("identifierValue", review.identifierValue())
                .param("reason", review.reason())
                .param("matcherName", review.matcherName())
                .param("confidence", review.confidence())
                .update();
    }

    public Optional<MatchReview> findById(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM match_review WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public List<MatchReview> list(UUID tenantId, ReviewStatus status, int limit, int offset) {
        return jdbc.sql("""
                        SELECT * FROM match_review
                        WHERE tenant_id = :tenantId AND status = :status
                        ORDER BY created_at, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", tenantId)
                .param("status", status.name())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapRow)
                .list();
    }

    public int count(UUID tenantId, ReviewStatus status) {
        return jdbc.sql("SELECT count(*) FROM match_review WHERE tenant_id = :tenantId AND status = :status")
                .param("tenantId", tenantId)
                .param("status", status.name())
                .query(Integer.class)
                .single();
    }

    public boolean existsPending(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId) {
        return jdbc.sql("""
                        SELECT count(*) FROM match_review
                        WHERE tenant_id = :tenantId AND source_record_id = :sourceRecordId
                          AND candidate_guest_id = :candidateGuestId AND status = 'PENDING'
                        """)
                .param("tenantId", tenantId)
                .param("sourceRecordId", sourceRecordId)
                .param("candidateGuestId", candidateGuestId)
                .query(Integer.class)
                .single() > 0;
    }

    /** Single PENDING → decided transition; returns 0 when already decided (→ 409). */
    public int decide(UUID tenantId, UUID id, ReviewStatus newStatus, UUID decisionEventId) {
        return jdbc.sql("""
                        UPDATE match_review
                        SET status = :newStatus, decided_at = now(), decision_event_id = :eventId
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                        """)
                .param("newStatus", newStatus.name())
                .param("eventId", decisionEventId)
                .param("tenantId", tenantId)
                .param("id", id)
                .update();
    }

    private MatchReview mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new MatchReview(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                ReviewStatus.valueOf(rs.getString("status")),
                rs.getObject("source_record_id", UUID.class),
                rs.getObject("candidate_guest_id", UUID.class),
                IdentifierType.valueOf(rs.getString("identifier_type")),
                rs.getString("identifier_value"),
                rs.getString("reason"),
                rs.getString("matcher_name"),
                rs.getBigDecimal("confidence"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                decidedAt != null ? decidedAt.toInstant() : null,
                rs.getObject("decision_event_id", UUID.class));
    }
}
