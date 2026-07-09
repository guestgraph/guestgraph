package io.guestgraph.persistence;

import io.guestgraph.domain.Guest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GuestDao {

    private final JdbcClient jdbc;
    private final Jsons jsons;

    public GuestDao(JdbcClient jdbc, Jsons jsons) {
        this.jdbc = jdbc;
        this.jsons = jsons;
    }

    public Guest insert(UUID tenantId) {
        Guest guest = new Guest(UUID.randomUUID(), tenantId, Map.of(), Instant.now(), Instant.now());
        jdbc.sql("INSERT INTO guest (id, tenant_id) VALUES (:id, :tenantId)")
                .param("id", guest.id())
                .param("tenantId", tenantId)
                .update();
        return guest;
    }

    public Optional<Guest> findById(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM guest WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public void updateProfile(UUID tenantId, UUID guestId, Map<String, Object> profile) {
        jdbc.sql("""
                        UPDATE guest SET profile = CAST(:profile AS jsonb), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :guestId
                        """)
                .param("profile", jsons.write(profile))
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .update();
    }

    public void delete(UUID tenantId, UUID guestId) {
        jdbc.sql("DELETE FROM guest WHERE tenant_id = :tenantId AND id = :guestId")
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .update();
    }

    private Guest mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Guest(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                jsons.map(rs.getString("profile")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
