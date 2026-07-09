package io.guestgraph.persistence;

import io.guestgraph.domain.Tenant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantDao {

    static final RowMapper<Tenant> MAPPER = (rs, i) -> new Tenant(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getInt("review_threshold"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public TenantDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findByApiKeyHash(String keyHash) {
        return jdbc.sql("""
                        SELECT t.* FROM tenant t
                        JOIN api_key k ON k.tenant_id = t.id
                        WHERE k.key_hash = :keyHash AND k.revoked_at IS NULL
                        """)
                .param("keyHash", keyHash)
                .query(MAPPER)
                .optional();
    }

    public int reviewThreshold(UUID tenantId) {
        return jdbc.sql("SELECT review_threshold FROM tenant WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }
}
