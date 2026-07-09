package io.guestgraph.persistence;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentifierDao {

    private final JdbcClient jdbc;

    public IdentifierDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> guestIdsByIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
        return jdbc.sql("""
                        SELECT DISTINCT guest_id FROM identifier
                        WHERE tenant_id = :tenantId AND type = :type AND value_normalized = :value
                        """)
                .param("tenantId", tenantId)
                .param("type", type.name())
                .param("value", valueNormalized)
                .query(UUID.class)
                .list();
    }

    public List<NormalizedIdentifier> findByGuest(UUID tenantId, UUID guestId) {
        return jdbc.sql("""
                        SELECT type, value_normalized FROM identifier
                        WHERE tenant_id = :tenantId AND guest_id = :guestId
                        ORDER BY type, value_normalized
                        """)
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .query((rs, i) -> new NormalizedIdentifier(
                        IdentifierType.valueOf(rs.getString("type")),
                        rs.getString("value_normalized")))
                .list();
    }

    /** Rebuilds a guest's identifiers from its linked records' contributions (merge/unmerge). */
    public void replaceForGuest(UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers) {
        jdbc.sql("DELETE FROM identifier WHERE tenant_id = :tenantId AND guest_id = :guestId")
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .update();
        for (NormalizedIdentifier identifier : identifiers) {
            jdbc.sql("""
                            INSERT INTO identifier (id, tenant_id, guest_id, type, value_normalized)
                            VALUES (:id, :tenantId, :guestId, :type, :value)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("guestId", guestId)
                    .param("type", identifier.type().name())
                    .param("value", identifier.value())
                    .update();
        }
    }
}
