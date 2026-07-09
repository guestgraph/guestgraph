package io.guestgraph.persistence.repo;

import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.persistence.entity.MatchReviewEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MatchReviewRepo extends Repository<MatchReviewEntity, UUID> {

  @Query("select mr from MatchReviewEntity mr where mr.tenantId = :tenantId and mr.id = :id")
  Optional<MatchReviewEntity> findReview(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /** Native for exact LIMIT/OFFSET paging (the API contract exposes raw offsets). */
  @Query(
      nativeQuery = true,
      value =
          """
            SELECT * FROM match_review
            WHERE tenant_id = :tenantId AND status = :status
            ORDER BY created_at, id
            LIMIT :limit OFFSET :offset
            """)
  List<MatchReviewEntity> list(
      @Param("tenantId") UUID tenantId,
      @Param("status") String status,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Query(
      "select count(mr) from MatchReviewEntity mr where mr.tenantId = :tenantId and mr.status = :status")
  int count(@Param("tenantId") UUID tenantId, @Param("status") ReviewStatus status);

  /** The single PENDING → decided transition (FR-018); 0 rows means already decided. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update MatchReviewEntity mr
            set mr.status = :newStatus, mr.decidedAt = :decidedAt, mr.decisionEventId = :eventId
            where mr.tenantId = :tenantId and mr.id = :id
              and mr.status = io.guestgraph.domain.ReviewStatus.PENDING
            """)
  int decide(
      @Param("tenantId") UUID tenantId,
      @Param("id") UUID id,
      @Param("newStatus") ReviewStatus newStatus,
      @Param("decidedAt") Instant decidedAt,
      @Param("eventId") UUID eventId);

  @Query(
      """
            select count(mr) > 0 from MatchReviewEntity mr
            where mr.tenantId = :tenantId and mr.sourceRecordId = :sourceRecordId
              and mr.candidateGuestId = :candidateGuestId and mr.status = :status
            """)
  boolean existsByStatus(
      @Param("tenantId") UUID tenantId,
      @Param("sourceRecordId") UUID sourceRecordId,
      @Param("candidateGuestId") UUID candidateGuestId,
      @Param("status") ReviewStatus status);

  /** Merge follows pending reviews to the survivor — their candidate guest is being deleted. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update MatchReviewEntity mr set mr.candidateGuestId = :toGuestId
            where mr.tenantId = :tenantId and mr.candidateGuestId = :fromGuestId
              and mr.status = io.guestgraph.domain.ReviewStatus.PENDING
            """)
  int repointPending(
      @Param("tenantId") UUID tenantId,
      @Param("fromGuestId") UUID fromGuestId,
      @Param("toGuestId") UUID toGuestId);

  /** Pending reviews are moot once their candidate guest ceased to exist (emptied by unmerge). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            delete from MatchReviewEntity mr
            where mr.tenantId = :tenantId and mr.candidateGuestId = :candidateGuestId
              and mr.status = io.guestgraph.domain.ReviewStatus.PENDING
            """)
  int deletePending(
      @Param("tenantId") UUID tenantId, @Param("candidateGuestId") UUID candidateGuestId);
}
