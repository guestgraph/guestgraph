package io.guestgraph.persistence;

import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.persistence.entity.RecordIdentifierEntity;
import io.guestgraph.persistence.entity.SourceRecordEntity;
import io.guestgraph.persistence.entity.SourceSystemEntity;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.SourceRecordRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository // @Repository (not @Component) for Hibernate→Spring exception translation
public class SourceRecordStore {

    @PersistenceContext
    private EntityManager em;

    private final SourceRecordRepo repo;
    private final DomainMappers mappers;

    public SourceRecordStore(SourceRecordRepo repo, DomainMappers mappers) {
        this.repo = repo;
        this.mappers = mappers;
    }

    /** Stores the record and its identifier contributions; flushes so the row (and any
     * constraint violation) is visible to the resolution queries that follow in the
     * same transaction — the old code's statement ordering, made explicit. */
    public void insert(SourceRecord record) {
        SourceSystemEntity sourceSystem = em.getReference(SourceSystemEntity.class, record.sourceSystemId());
        SourceRecordEntity entity = new SourceRecordEntity(record.id(), record.tenantId(), sourceSystem,
                record.externalKey(), record.payloadJson(), record.extracted(), record.recordTimestamp(),
                record.needsReview(), record.needsReviewReasons(), record.receivedAt());
        for (NormalizedIdentifier identifier : new LinkedHashSet<>(record.identifiers())) {
            entity.addIdentifier(new RecordIdentifierEntity(UUID.randomUUID(), record.tenantId(), entity,
                    identifier.type(), identifier.value()));
        }
        em.persist(entity);
        em.flush();
    }

    public Optional<UUID> findIdByExternalKey(UUID tenantId, UUID sourceSystemId, String externalKey) {
        return repo.findIdByExternalKey(tenantId, sourceSystemId, externalKey);
    }

    public List<SourceRecord> findByGuestId(UUID tenantId, UUID guestId) {
        return mappers.toDomainRecords(repo.findByGuestId(tenantId, guestId));
    }
}
