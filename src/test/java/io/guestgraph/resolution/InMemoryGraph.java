package io.guestgraph.resolution;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.BlockKeyType;
import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.domain.SourceRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    linkByRecord.forEach(
        (recordId, guestId) ->
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
  public List<UUID> guestIdsByIdentifier(
      UUID tenantId, IdentifierType type, String valueNormalized) {
    NormalizedIdentifier wanted = new NormalizedIdentifier(type, valueNormalized);
    return identifiersByGuest.entrySet().stream()
        .filter(e -> guests.containsKey(e.getKey()) && e.getValue().contains(wanted))
        .map(Map.Entry::getKey)
        .toList();
  }

  @Override
  public int recordsSharingIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
    NormalizedIdentifier wanted = new NormalizedIdentifier(type, valueNormalized);
    return (int)
        records.values().stream()
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
    linkByRecord.replaceAll(
        (recordId, guestId) -> guestId.equals(fromGuestId) ? toGuestId : guestId);
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
    // Mirrors MergeEventRepo.findByGuestIds ordering: oldest first (stable on ties).
    return events.stream()
        .filter(e -> guestIds.contains(e.guestId()))
        .sorted(Comparator.comparing(MergeEvent::createdAt))
        .toList();
  }

  @Override
  public void replaceGuestIdentifiers(
      UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers) {
    identifiersByGuest.put(guestId, new HashSet<>(identifiers));
  }

  @Override
  public void updateGuestProfile(UUID tenantId, UUID guestId, Map<String, Object> profile) {
    Guest existing = guests.get(guestId);
    if (existing != null) {
      guests.put(
          guestId,
          new Guest(
              existing.id(), existing.tenantId(), profile, existing.createdAt(), Instant.now()));
    }
  }

  @Override
  public void saveReview(MatchReview review) {
    reviews.add(review);
  }

  @Override
  public void repointPendingReviews(UUID tenantId, UUID fromGuestId, UUID toGuestId) {
    reviews.replaceAll(
        r ->
            r.status() == ReviewStatus.PENDING && r.candidateGuestId().equals(fromGuestId)
                ? new MatchReview(
                    r.id(),
                    r.tenantId(),
                    r.status(),
                    r.sourceRecordId(),
                    toGuestId,
                    r.identifierType(),
                    r.identifierValue(),
                    r.reason(),
                    r.matcherName(),
                    r.confidence(),
                    r.createdAt(),
                    r.decidedAt(),
                    r.decisionEventId())
                : r);
  }

  @Override
  public void cancelPendingReviews(UUID tenantId, UUID candidateGuestId) {
    reviews.removeIf(
        r -> r.status() == ReviewStatus.PENDING && r.candidateGuestId().equals(candidateGuestId));
  }

  @Override
  public Optional<MatchReview> findReview(UUID tenantId, UUID reviewId) {
    return reviews.stream().filter(r -> r.id().equals(reviewId)).findFirst();
  }

  private final Map<UUID, List<BlockKey>> blockKeysByRecord = new LinkedHashMap<>();
  private final List<NegativeMatchRule> negativeRules = new ArrayList<>();
  private final List<IdentifierQualityRule> tenantQualityRules = new ArrayList<>();
  private BigDecimal autoMergeThreshold = new BigDecimal("1.000");
  private BigDecimal reviewFloor = new BigDecimal("0.750");

  public void registerBlockKeys(UUID recordId, List<BlockKey> keys) {
    blockKeysByRecord.put(recordId, List.copyOf(keys));
  }

  public void setBands(String autoMergeThreshold, String reviewFloor) {
    this.autoMergeThreshold = new BigDecimal(autoMergeThreshold);
    this.reviewFloor = new BigDecimal(reviewFloor);
  }

  public void addQualityRule(IdentifierQualityRule rule) {
    tenantQualityRules.add(rule);
  }

  public List<NegativeMatchRule> negativeRules() {
    return List.copyOf(negativeRules);
  }

  @Override
  public List<UUID> guestIdsByBlockKey(UUID tenantId, BlockKeyType type, String valueNormalized) {
    BlockKey wanted = new BlockKey(type, valueNormalized);
    return blockKeysByRecord.entrySet().stream()
        .filter(e -> e.getValue().contains(wanted))
        .map(e -> linkByRecord.get(e.getKey()))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  @Override
  public List<BlockKey> blockKeysOfRecord(UUID tenantId, UUID sourceRecordId) {
    return blockKeysByRecord.getOrDefault(sourceRecordId, List.of());
  }

  @Override
  public Map<String, Object> guestProfile(UUID tenantId, UUID guestId) {
    Guest guest = guests.get(guestId);
    return guest != null ? guest.profile() : Map.of();
  }

  @Override
  public MatchingConfig matchingConfig(UUID tenantId) {
    return new MatchingConfig(autoMergeThreshold, reviewFloor, reviewThreshold);
  }

  @Override
  public List<UUID> recordIdsOfGuest(UUID tenantId, UUID guestId) {
    return linkByRecord.entrySet().stream()
        .filter(e -> e.getValue().equals(guestId))
        .map(Map.Entry::getKey)
        .toList();
  }

  @Override
  public boolean negativeRuleBetween(
      UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB) {
    return negativeRules.stream()
        .anyMatch(
            r ->
                (recordsA.contains(r.recordA()) && recordsB.contains(r.recordB()))
                    || (recordsB.contains(r.recordA()) && recordsA.contains(r.recordB())));
  }

  @Override
  public void saveNegativeRule(NegativeMatchRule rule) {
    boolean duplicate =
        negativeRules.stream()
            .anyMatch(
                r -> r.recordA().equals(rule.recordA()) && r.recordB().equals(rule.recordB()));
    if (!duplicate) {
      negativeRules.add(rule);
    }
  }

  @Override
  public void liftNegativeRulesBetween(
      UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB) {
    negativeRules.removeIf(
        r ->
            (recordsA.contains(r.recordA()) && recordsB.contains(r.recordB()))
                || (recordsB.contains(r.recordA()) && recordsA.contains(r.recordB())));
  }

  @Override
  public List<IdentifierQualityRule> qualityRules(UUID tenantId) {
    List<IdentifierQualityRule> all = new ArrayList<>(BuiltinQualityRules.RULES);
    all.addAll(tenantQualityRules);
    return all;
  }

  @Override
  public int decideReview(UUID tenantId, UUID reviewId, ReviewStatus newStatus, UUID eventId) {
    for (int i = 0; i < reviews.size(); i++) {
      MatchReview r = reviews.get(i);
      if (r.id().equals(reviewId) && r.status() == ReviewStatus.PENDING) {
        reviews.set(
            i,
            new MatchReview(
                r.id(),
                r.tenantId(),
                newStatus,
                r.sourceRecordId(),
                r.candidateGuestId(),
                r.identifierType(),
                r.identifierValue(),
                r.reason(),
                r.matcherName(),
                r.confidence(),
                r.createdAt(),
                Instant.now(),
                eventId));
        return 1;
      }
    }
    return 0;
  }

  @Override
  public boolean pendingReviewExists(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId) {
    return reviews.stream()
        .anyMatch(
            r ->
                r.status() == ReviewStatus.PENDING
                    && r.sourceRecordId().equals(sourceRecordId)
                    && r.candidateGuestId().equals(candidateGuestId));
  }
}
