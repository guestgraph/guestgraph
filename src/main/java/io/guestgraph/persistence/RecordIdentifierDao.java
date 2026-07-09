package io.guestgraph.persistence;

import io.guestgraph.domain.IdentifierType;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RecordIdentifierDao {

    private final JdbcClient jdbc;

    public RecordIdentifierDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How many source records in the tenant carry this identifier — the review-threshold input (R9). */
    public int countRecordsSharing(UUID tenantId, IdentifierType type, String valueNormalized) {
        return jdbc.sql("""
                        SELECT count(DISTINCT source_record_id) FROM record_identifier
                        WHERE tenant_id = :tenantId AND type = :type AND value_normalized = :value
                        """)
                .param("tenantId", tenantId)
                .param("type", type.name())
                .param("value", valueNormalized)
                .query(Integer.class)
                .single();
    }
}
