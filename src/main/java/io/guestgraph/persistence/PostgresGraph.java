package io.guestgraph.persistence;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.resolution.GraphPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Postgres-backed GraphPort: the resolution engine's storage adapter. */
@Component
public class PostgresGraph implements GraphPort {

    private final TenantDao tenantDao;
    private final GuestDao guestDao;
    private final IdentifierDao identifierDao;
    private final RecordIdentifierDao recordIdentifierDao;
    private final ResolutionLinkDao linkDao;
    private final SourceRecordDao sourceRecordDao;
    private final MergeEventDao mergeEventDao;
    private final MatchReviewDao matchReviewDao;

    public PostgresGraph(TenantDao tenantDao, GuestDao guestDao, IdentifierDao identifierDao,
            RecordIdentifierDao recordIdentifierDao, ResolutionLinkDao linkDao, SourceRecordDao sourceRecordDao,
            MergeEventDao mergeEventDao, MatchReviewDao matchReviewDao) {
        this.tenantDao = tenantDao;
        this.guestDao = guestDao;
        this.identifierDao = identifierDao;
        this.recordIdentifierDao = recordIdentifierDao;
        this.linkDao = linkDao;
        this.sourceRecordDao = sourceRecordDao;
        this.mergeEventDao = mergeEventDao;
        this.matchReviewDao = matchReviewDao;
    }

    @Override
    public int reviewThreshold(UUID tenantId) {
        return tenantDao.reviewThreshold(tenantId);
    }

    @Override
    public List<UUID> guestIdsByIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
        return identifierDao.guestIdsByIdentifier(tenantId, type, valueNormalized);
    }

    @Override
    public int recordsSharingIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
        return recordIdentifierDao.countRecordsSharing(tenantId, type, valueNormalized);
    }

    @Override
    public Guest createGuest(UUID tenantId) {
        return guestDao.insert(tenantId);
    }

    @Override
    public void deleteGuest(UUID tenantId, UUID guestId) {
        guestDao.delete(tenantId, guestId);
    }

    @Override
    public void linkRecord(UUID tenantId, UUID sourceRecordId, UUID guestId, UUID eventId) {
        linkDao.insert(tenantId, sourceRecordId, guestId, eventId);
    }

    @Override
    public void moveLinks(UUID tenantId, UUID fromGuestId, UUID toGuestId, UUID eventId) {
        linkDao.moveGuest(tenantId, fromGuestId, toGuestId, eventId);
    }

    @Override
    public int unlinkRecords(UUID tenantId, UUID guestId, Collection<UUID> sourceRecordIds) {
        return linkDao.deleteByRecordIds(tenantId, guestId, sourceRecordIds);
    }

    @Override
    public int linkCount(UUID tenantId, UUID guestId) {
        return linkDao.countByGuest(tenantId, guestId);
    }

    @Override
    public Optional<UUID> guestOfRecord(UUID tenantId, UUID sourceRecordId) {
        return linkDao.guestIdByRecord(tenantId, sourceRecordId);
    }

    @Override
    public List<SourceRecord> recordsOfGuest(UUID tenantId, UUID guestId) {
        return sourceRecordDao.findByGuestId(tenantId, guestId);
    }

    @Override
    public void saveEvent(MergeEvent event) {
        mergeEventDao.insert(event);
    }

    @Override
    public List<MergeEvent> eventsForGuests(UUID tenantId, Collection<UUID> guestIds) {
        return mergeEventDao.findByGuestIds(tenantId, guestIds);
    }

    @Override
    public void replaceGuestIdentifiers(UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers) {
        identifierDao.replaceForGuest(tenantId, guestId, identifiers);
    }

    @Override
    public void updateGuestProfile(UUID tenantId, UUID guestId, Map<String, Object> profile) {
        guestDao.updateProfile(tenantId, guestId, profile);
    }

    @Override
    public void saveReview(MatchReview review) {
        matchReviewDao.insert(review);
    }

    @Override
    public boolean pendingReviewExists(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId) {
        return matchReviewDao.existsPending(tenantId, sourceRecordId, candidateGuestId);
    }
}
