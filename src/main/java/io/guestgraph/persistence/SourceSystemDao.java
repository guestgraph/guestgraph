package io.guestgraph.persistence;

import io.guestgraph.domain.SourceSystem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceSystemDao {

    private static final RowMapper<SourceSystem> MAPPER = (rs, i) -> new SourceSystem(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public SourceSystemDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws DuplicateKeyException when the code is already registered in the tenant */
    public SourceSystem insert(UUID tenantId, String code, String name) {
        SourceSystem sourceSystem = new SourceSystem(UUID.randomUUID(), tenantId, code, name, Instant.now());
        jdbc.sql("""
                        INSERT INTO source_system (id, tenant_id, code, name)
                        VALUES (:id, :tenantId, :code, :name)
                        """)
                .param("id", sourceSystem.id())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("name", name)
                .update();
        return sourceSystem;
    }

    public Optional<SourceSystem> findByCode(UUID tenantId, String code) {
        return jdbc.sql("SELECT * FROM source_system WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId)
                .param("code", code)
                .query(MAPPER)
                .optional();
    }
}
