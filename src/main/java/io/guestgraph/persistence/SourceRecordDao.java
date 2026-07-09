package io.guestgraph.persistence;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRecordDao {

    private static final String SELECT = """
            SELECT r.*, s.code AS source_system_code,
                   COALESCE(ri.identifiers, '[]'::json) AS identifiers_json
            FROM source_record r
            JOIN source_system s ON s.id = r.source_system_id
            LEFT JOIN LATERAL (
                SELECT json_agg(json_build_object('type', type, 'value', value_normalized)) AS identifiers
                FROM record_identifier WHERE source_record_id = r.id
            ) ri ON true
            """;

    private final JdbcClient jdbc;
    private final Jsons jsons;

    public SourceRecordDao(JdbcClient jdbc, Jsons jsons) {
        this.jdbc = jdbc;
        this.jsons = jsons;
    }

    public void insert(SourceRecord record) {
        jdbc.sql("""
                        INSERT INTO source_record (id, tenant_id, source_system_id, external_key, payload,
                                                   extracted, record_timestamp, needs_review, needs_review_reasons, received_at)
                        VALUES (:id, :tenantId, :sourceSystemId, :externalKey, CAST(:payload AS jsonb),
                                CAST(:extracted AS jsonb), :recordTimestamp, :needsReview, CAST(:reasons AS jsonb), :receivedAt)
                        """)
                .param("id", record.id())
                .param("tenantId", record.tenantId())
                .param("sourceSystemId", record.sourceSystemId())
                .param("externalKey", record.externalKey())
                .param("payload", record.payloadJson())
                .param("extracted", jsons.write(record.extracted()))
                .param("recordTimestamp", record.recordTimestamp() != null
                        ? OffsetDateTime.ofInstant(record.recordTimestamp(), java.time.ZoneOffset.UTC) : null)
                .param("needsReview", record.needsReview())
                .param("reasons", jsons.write(record.needsReviewReasons()))
                .param("receivedAt", OffsetDateTime.ofInstant(record.receivedAt(), java.time.ZoneOffset.UTC))
                .update();
        for (NormalizedIdentifier identifier : record.identifiers()) {
            jdbc.sql("""
                            INSERT INTO record_identifier (id, tenant_id, source_record_id, type, value_normalized)
                            VALUES (:id, :tenantId, :recordId, :type, :value)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", record.tenantId())
                    .param("recordId", record.id())
                    .param("type", identifier.type().name())
                    .param("value", identifier.value())
                    .update();
        }
    }

    public Optional<UUID> findIdByExternalKey(UUID tenantId, UUID sourceSystemId, String externalKey) {
        return jdbc.sql("""
                        SELECT id FROM source_record
                        WHERE tenant_id = :tenantId AND source_system_id = :sourceSystemId AND external_key = :externalKey
                        """)
                .param("tenantId", tenantId)
                .param("sourceSystemId", sourceSystemId)
                .param("externalKey", externalKey)
                .query(UUID.class)
                .optional();
    }

    public Optional<SourceRecord> findById(UUID tenantId, UUID id) {
        return jdbc.sql(SELECT + " WHERE r.tenant_id = :tenantId AND r.id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public List<SourceRecord> findByIds(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE r.tenant_id = :tenantId AND r.id IN (:ids) ORDER BY r.received_at")
                .param("tenantId", tenantId)
                .param("ids", new ArrayList<>(ids))
                .query(this::mapRow)
                .list();
    }

    public List<SourceRecord> findByGuestId(UUID tenantId, UUID guestId) {
        return jdbc.sql(SELECT + """
                         JOIN resolution_link l ON l.source_record_id = r.id
                         WHERE r.tenant_id = :tenantId AND l.guest_id = :guestId
                         ORDER BY r.received_at
                        """)
                .param("tenantId", tenantId)
                .param("guestId", guestId)
                .query(this::mapRow)
                .list();
    }

    private SourceRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        List<NormalizedIdentifier> identifiers = mapIdentifiers(rs.getString("identifiers_json"));
        OffsetDateTime recordTs = rs.getObject("record_timestamp", OffsetDateTime.class);
        return new SourceRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("source_system_id", UUID.class),
                rs.getString("source_system_code"),
                rs.getString("external_key"),
                rs.getString("payload"),
                jsons.map(rs.getString("extracted")),
                identifiers,
                recordTs != null ? recordTs.toInstant() : null,
                rs.getBoolean("needs_review"),
                jsons.stringList(rs.getString("needs_review_reasons")),
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }

    private List<NormalizedIdentifier> mapIdentifiers(String json) {
        return jsons.mapList(json).stream()
                .map(m -> new NormalizedIdentifier(
                        IdentifierType.valueOf((String) m.get("type")),
                        (String) m.get("value")))
                .toList();
    }
}
