package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.MergeEventEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MergeEventRepo extends Repository<MergeEventEntity, UUID> {

    /** All events whose surviving guest is one of {@code guestIds}, oldest first. */
    @Query("""
            select e from MergeEventEntity e
            where e.tenantId = :tenantId and e.guestId in :guestIds
            order by e.createdAt, e.id
            """)
    List<MergeEventEntity> findByGuestIds(@Param("tenantId") UUID tenantId,
            @Param("guestIds") Collection<UUID> guestIds);
}
