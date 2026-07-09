package io.guestgraph.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResolutionLinkDao {

    private final JdbcClient jdbc;

    public ResolutionLinkDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tenantId, UUID sourceRecordId, UUID guestId, UUID createdByEventId) {
        jdbc.sql("""
                        INSERT INTO resolution_link (id, tenant_id, source_record_id, guest_id, created_by_event_id)
                        VALUES (:id, :tenantId, :sourceRecordId, :guestId, :eventId)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("sourceRecordId", sourceRecordId)
                .param("guestId", guestId)
                .param("eventId", createdByEventId)
                .update();
    }

    public Optional<UUID> guestIdByRecord(UUID tenantId, UUID sourceRecordId) {
        return jdbc.sql("""
                        SELECT guest_id FROM resolution_link
                        WHERE tenant_id = :tenantId AND source_record_id = :sourceRecordId
                        """)
                .param("tenantId", tenantId)
                .param("sourceRecordId", sourceRecordId)
                .query(UUID.class)
                .optional();
    }

    public List<UUID> recordIdsByGuest(UUID tenantId, UUID guestId) {
        return jdbc.sql("""
                        SELECT source_record_id FROM resolution_link
                        WHERE tenant_id = :tenantId AND guest_id = :guestId
                        """)
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .query(UUID.class)
                .list();
    }

    public int countByGuest(UUID tenantId, UUID guestId) {
        return jdbc.sql("SELECT count(*) FROM resolution_link WHERE tenant_id = :tenantId AND guest_id = :guestId")
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .query(Integer.class)
                .single();
    }

    /** Re-points all links of {@code fromGuestId} to {@code toGuestId} (merge). */
    public void moveGuest(UUID tenantId, UUID fromGuestId, UUID toGuestId, UUID eventId) {
        jdbc.sql("""
                        UPDATE resolution_link SET guest_id = :toGuestId, created_by_event_id = :eventId
                        WHERE tenant_id = :tenantId AND guest_id = :fromGuestId
                        """)
                .param("toGuestId", toGuestId)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .param("fromGuestId", fromGuestId)
                .update();
    }

    public int deleteByRecordIds(UUID tenantId, UUID guestId, Collection<UUID> sourceRecordIds) {
        if (sourceRecordIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        DELETE FROM resolution_link
                        WHERE tenant_id = :tenantId AND guest_id = :guestId AND source_record_id IN (:recordIds)
                        """)
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .param("recordIds", new ArrayList<>(sourceRecordIds))
                .update();
    }
}
