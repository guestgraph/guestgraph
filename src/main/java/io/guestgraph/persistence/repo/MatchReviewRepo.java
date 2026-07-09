package io.guestgraph.persistence.repo;

import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.persistence.entity.MatchReviewEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MatchReviewRepo extends Repository<MatchReviewEntity, UUID> {

    @Query("""
            select count(mr) > 0 from MatchReviewEntity mr
            where mr.tenantId = :tenantId and mr.sourceRecordId = :sourceRecordId
              and mr.candidateGuestId = :candidateGuestId and mr.status = :status
            """)
    boolean existsByStatus(@Param("tenantId") UUID tenantId, @Param("sourceRecordId") UUID sourceRecordId,
            @Param("candidateGuestId") UUID candidateGuestId, @Param("status") ReviewStatus status);
}
