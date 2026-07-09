package io.guestgraph.persistence;

import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MergeEventDao {

    private final JdbcClient jdbc;
    private final Jsons jsons;

    public MergeEventDao(JdbcClient jdbc, Jsons jsons) {
        this.jdbc = jdbc;
        this.jsons = jsons;
    }

    public void insert(MergeEvent event) {
        jdbc.sql("""
                        INSERT INTO merge_event (id, tenant_id, kind, guest_id, absorbed_guest_ids, source_record_ids,
                                                 matcher_name, confidence, evidence, excluded_guest_ids)
                        VALUES (:id, :tenantId, :kind, :guestId, CAST(:absorbed AS jsonb), CAST(:recordIds AS jsonb),
                                :matcherName, :confidence, CAST(:evidence AS jsonb), CAST(:excluded AS jsonb))
                        """)
                .param("id", event.id())
                .param("tenantId", event.tenantId())
                .param("kind", event.kind().name())
                .param("guestId", event.guestId())
                .param("absorbed", jsons.write(event.absorbedGuestIds()))
                .param("recordIds", jsons.write(event.sourceRecordIds()))
                .param("matcherName", event.matcherName())
                .param("confidence", event.confidence())
                .param("evidence", jsons.write(event.evidence()))
                .param("excluded", jsons.write(event.excludedGuestIds()))
                .update();
    }

    /** All events whose surviving guest is one of {@code guestIds}, oldest first. */
    public List<MergeEvent> findByGuestIds(UUID tenantId, Collection<UUID> guestIds) {
        if (guestIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT * FROM merge_event
                        WHERE tenant_id = :tenantId AND guest_id IN (:guestIds)
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId)
                .param("guestIds", new ArrayList<>(guestIds))
                .query(this::mapRow)
                .list();
    }

    private MergeEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MergeEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                MergeEventKind.valueOf(rs.getString("kind")),
                rs.getObject("guest_id", UUID.class),
                jsons.uuidList(rs.getString("absorbed_guest_ids")),
                jsons.uuidList(rs.getString("source_record_ids")),
                rs.getString("matcher_name"),
                rs.getBigDecimal("confidence"),
                jsons.map(rs.getString("evidence")),
                jsons.uuidList(rs.getString("excluded_guest_ids")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
