package io.guestgraph.persistence.repo;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.persistence.entity.RecordIdentifierEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RecordIdentifierRepo extends Repository<RecordIdentifierEntity, UUID> {

    /** How many source records in the tenant carry this identifier — the review-threshold input (R9). */
    @Query("""
            select count(distinct ri.sourceRecord.id) from RecordIdentifierEntity ri
            where ri.tenantId = :tenantId and ri.type = :type and ri.valueNormalized = :value
            """)
    int countRecordsSharing(@Param("tenantId") UUID tenantId, @Param("type") IdentifierType type,
            @Param("value") String value);
}
