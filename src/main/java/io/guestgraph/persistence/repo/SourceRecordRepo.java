package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.SourceRecordEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SourceRecordRepo extends Repository<SourceRecordEntity, UUID> {

  @Query(
      """
            select r.needsReview from SourceRecordEntity r
            where r.tenantId = :tenantId and r.id = :id
            """)
  Optional<Boolean> needsReview(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  @Query(
      """
            select r.id from SourceRecordEntity r
            where r.tenantId = :tenantId and r.sourceSystem.id = :sourceSystemId and r.externalKey = :externalKey
            """)
  Optional<UUID> findIdByExternalKey(
      @Param("tenantId") UUID tenantId,
      @Param("sourceSystemId") UUID sourceSystemId,
      @Param("externalKey") String externalKey);

  @Query(
      """
            select distinct r from SourceRecordEntity r
            join fetch r.sourceSystem left join fetch r.identifiers
            where r.tenantId = :tenantId and r.id in
                (select l.sourceRecordId from ResolutionLinkEntity l
                 where l.tenantId = :tenantId and l.guestId = :guestId)
            order by r.receivedAt
            """)
  List<SourceRecordEntity> findByGuestId(
      @Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId);
}
