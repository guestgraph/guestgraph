package io.guestgraph.resolution;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.domain.SourceRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure in-memory GraphPort so engine scenario tests run without Spring or a database. */
public class InMemoryGraph implements GraphPort {

    private final Map<UUID, Guest> guests = new LinkedHashMap<>();
    private final Map<UUID, SourceRecord> records = new LinkedHashMap<>();
    private final Map<UUID, UUID> linkByRecord = new LinkedHashMap<>();
    private final Map<UUID, Set<NormalizedIdentifier>> identifiersByGuest = new HashMap<>();
    private final List<MergeEvent> events = new ArrayList<>();
    private final List<MatchReview> reviews = new ArrayList<>();
    private int reviewThreshold = 10;

    public void register(SourceRecord record) {
        records.put(record.id(), record);
    }

    public void setReviewThreshold(int threshold) {
        this.reviewThreshold = threshold;
    }

    public List<MergeEvent> events() {
        return List.copyOf(events);
    }

    public List<MatchReview> reviews() {
        return List.copyOf(reviews);
    }

    public Map<UUID, Set<UUID>> clusters() {
        Map<UUID, Set<UUID>> byGuest = new HashMap<>();
        linkByRecord.forEach((recordId, guestId) ->
                byGuest.computeIfAbsent(guestId, g -> new HashSet<>()).add(recordId));
        return byGuest;
    }

    public Map<String, Object> profileOf(UUID guestId) {
        return guests.get(guestId).profile();
    }

    public Set<NormalizedIdentifier> identifiersOf(UUID guestId) {
        return identifiersByGuest.getOrDefault(guestId, Set.of());
    }

    @Override
    public int reviewThreshold(UUID tenantId) {
        return reviewThreshold;
    }

    @Override
    public List<UUID> guestIdsByIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
        NormalizedIdentifier wanted = new NormalizedIdentifier(type, valueNormalized);
        return identifiersByGuest.entrySet().stream()
                .filter(e -> guests.containsKey(e.getKey()) && e.getValue().contains(wanted))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public int recordsSharingIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
        NormalizedIdentifier wanted = new NormalizedIdentifier(type, valueNormalized);
        return (int) records.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.identifiers().contains(wanted))
                .count();
    }

    @Override
    public Guest createGuest(UUID tenantId) {
        Guest guest = new Guest(UUID.randomUUID(), tenantId, Map.of(), Instant.now(), Instant.now());
        guests.put(guest.id(), guest);
        return guest;
    }

    @Override
    public void deleteGuest(UUID tenantId, UUID guestId) {
        guests.remove(guestId);
        identifiersByGuest.remove(guestId);
    }

    @Override
    public void linkRecord(UUID tenantId, UUID sourceRecordId, UUID guestId, UUID eventId) {
        linkByRecord.put(sourceRecordId, guestId);
    }

    @Override
    public void moveLinks(UUID tenantId, UUID fromGuestId, UUID toGuestId, UUID eventId) {
        linkByRecord.replaceAll((recordId, guestId) -> guestId.equals(fromGuestId) ? toGuestId : guestId);
    }

    @Override
    public int unlinkRecords(UUID tenantId, UUID guestId, Collection<UUID> sourceRecordIds) {
        int removed = 0;
        for (UUID recordId : sourceRecordIds) {
            if (guestId.equals(linkByRecord.get(recordId))) {
                linkByRecord.remove(recordId);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int linkCount(UUID tenantId, UUID guestId) {
        return (int) linkByRecord.values().stream().filter(guestId::equals).count();
    }

    @Override
    public Optional<UUID> guestOfRecord(UUID tenantId, UUID sourceRecordId) {
        return Optional.ofNullable(linkByRecord.get(sourceRecordId));
    }

    @Override
    public List<SourceRecord> recordsOfGuest(UUID tenantId, UUID guestId) {
        return linkByRecord.entrySet().stream()
                .filter(e -> e.getValue().equals(guestId))
                .map(e -> records.get(e.getKey()))
                .toList();
    }

    @Override
    public void saveEvent(MergeEvent event) {
        events.add(event);
    }

    @Override
    public List<MergeEvent> eventsForGuests(UUID tenantId, Collection<UUID> guestIds) {
        return events.stream().filter(e -> guestIds.contains(e.guestId())).toList();
    }

    @Override
    public void replaceGuestIdentifiers(UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers) {
        identifiersByGuest.put(guestId, new HashSet<>(identifiers));
    }

    @Override
    public void updateGuestProfile(UUID tenantId, UUID guestId, Map<String, Object> profile) {
        Guest existing = guests.get(guestId);
        if (existing != null) {
            guests.put(guestId, new Guest(existing.id(), existing.tenantId(), profile, existing.createdAt(), Instant.now()));
        }
    }

    @Override
    public void saveReview(MatchReview review) {
        reviews.add(review);
    }

    @Override
    public boolean pendingReviewExists(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId) {
        return reviews.stream().anyMatch(r -> r.status() == ReviewStatus.PENDING
                && r.sourceRecordId().equals(sourceRecordId)
                && r.candidateGuestId().equals(candidateGuestId));
    }
}
