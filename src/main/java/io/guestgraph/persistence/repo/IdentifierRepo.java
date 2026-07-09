package io.guestgraph.persistence.repo;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.persistence.entity.IdentifierEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface IdentifierRepo extends Repository<IdentifierEntity, UUID> {

    @Query("""
            select distinct i.guestId from IdentifierEntity i
            where i.tenantId = :tenantId and i.type = :type and i.valueNormalized = :value
            """)
    List<UUID> guestIdsByIdentifier(@Param("tenantId") UUID tenantId, @Param("type") IdentifierType type,
            @Param("value") String value);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IdentifierEntity i where i.tenantId = :tenantId and i.guestId = :guestId")
    int deleteForGuest(@Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId);
}
