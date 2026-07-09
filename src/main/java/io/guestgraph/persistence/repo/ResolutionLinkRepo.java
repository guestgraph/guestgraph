package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.ResolutionLinkEntity;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ResolutionLinkRepo extends Repository<ResolutionLinkEntity, UUID> {

    @Query("""
            select l.guestId from ResolutionLinkEntity l
            where l.tenantId = :tenantId and l.sourceRecordId = :sourceRecordId
            """)
    Optional<UUID> guestIdByRecord(@Param("tenantId") UUID tenantId, @Param("sourceRecordId") UUID sourceRecordId);

    @Query("select count(l) from ResolutionLinkEntity l where l.tenantId = :tenantId and l.guestId = :guestId")
    int countByGuest(@Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId);

    /** Re-points all links of {@code fromGuestId} to {@code toGuestId} (merge). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ResolutionLinkEntity l set l.guestId = :toGuestId, l.createdByEventId = :eventId
            where l.tenantId = :tenantId and l.guestId = :fromGuestId
            """)
    int moveGuest(@Param("tenantId") UUID tenantId, @Param("fromGuestId") UUID fromGuestId,
            @Param("toGuestId") UUID toGuestId, @Param("eventId") UUID eventId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ResolutionLinkEntity l
            where l.tenantId = :tenantId and l.guestId = :guestId and l.sourceRecordId in :recordIds
            """)
    int deleteByRecordIds(@Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId,
            @Param("recordIds") Collection<UUID> recordIds);
}
