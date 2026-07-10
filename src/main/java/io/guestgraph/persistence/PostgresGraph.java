package io.guestgraph.persistence;

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
import io.guestgraph.persistence.entity.GuestEntity;
import io.guestgraph.persistence.entity.IdentifierEntity;
import io.guestgraph.persistence.entity.MatchReviewEntity;
import io.guestgraph.persistence.entity.MergeEventEntity;
import io.guestgraph.persistence.entity.NegativeMatchRuleEntity;
import io.guestgraph.persistence.entity.ResolutionLinkEntity;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.GuestRepo;
import io.guestgraph.persistence.repo.IdentifierQualityRuleRepo;
import io.guestgraph.persistence.repo.IdentifierRepo;
import io.guestgraph.persistence.repo.MatchReviewRepo;
import io.guestgraph.persistence.repo.MergeEventRepo;
import io.guestgraph.persistence.repo.NegativeMatchRuleRepo;
import io.guestgraph.persistence.repo.RecordBlockKeyRepo;
import io.guestgraph.persistence.repo.RecordIdentifierRepo;
import io.guestgraph.persistence.repo.ResolutionLinkRepo;
import io.guestgraph.persistence.repo.TenantRepo;
import io.guestgraph.resolution.BuiltinQualityRules;
import io.guestgraph.resolution.GraphPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Postgres-backed GraphPort: the resolution engine's storage adapter. */
@Repository // @Repository (not @Component) for Hibernate→Spring exception translation
public class PostgresGraph implements GraphPort {

  @PersistenceContext private EntityManager em;

  private final TenantRepo tenantRepo;
  private final GuestRepo guestRepo;
  private final IdentifierRepo identifierRepo;
  private final RecordIdentifierRepo recordIdentifierRepo;
  private final ResolutionLinkRepo linkRepo;
  private final MergeEventRepo mergeEventRepo;
  private final MatchReviewRepo matchReviewRepo;
  private final RecordBlockKeyRepo recordBlockKeyRepo;
  private final NegativeMatchRuleRepo negativeMatchRuleRepo;
  private final IdentifierQualityRuleRepo identifierQualityRuleRepo;
  private final SourceRecordStore sourceRecordStore;
  private final DomainMappers mappers;
  private final Jsons jsons;

  public PostgresGraph(
      TenantRepo tenantRepo,
      GuestRepo guestRepo,
      IdentifierRepo identifierRepo,
      RecordIdentifierRepo recordIdentifierRepo,
      ResolutionLinkRepo linkRepo,
      MergeEventRepo mergeEventRepo,
      MatchReviewRepo matchReviewRepo,
      RecordBlockKeyRepo recordBlockKeyRepo,
      NegativeMatchRuleRepo negativeMatchRuleRepo,
      IdentifierQualityRuleRepo identifierQualityRuleRepo,
      SourceRecordStore sourceRecordStore,
      DomainMappers mappers,
      Jsons jsons) {
    this.tenantRepo = tenantRepo;
    this.guestRepo = guestRepo;
    this.identifierRepo = identifierRepo;
    this.recordIdentifierRepo = recordIdentifierRepo;
    this.linkRepo = linkRepo;
    this.mergeEventRepo = mergeEventRepo;
    this.matchReviewRepo = matchReviewRepo;
    this.recordBlockKeyRepo = recordBlockKeyRepo;
    this.negativeMatchRuleRepo = negativeMatchRuleRepo;
    this.identifierQualityRuleRepo = identifierQualityRuleRepo;
    this.sourceRecordStore = sourceRecordStore;
    this.mappers = mappers;
    this.jsons = jsons;
  }

  @Override
  public int reviewThreshold(UUID tenantId) {
    return tenantRepo
        .reviewThreshold(tenantId)
        .orElseThrow(() -> new IllegalStateException("Unknown tenant " + tenantId));
  }

  @Override
  public List<UUID> guestIdsByIdentifier(
      UUID tenantId, IdentifierType type, String valueNormalized) {
    return identifierRepo.guestIdsByIdentifier(tenantId, type, valueNormalized);
  }

  @Override
  public int recordsSharingIdentifier(UUID tenantId, IdentifierType type, String valueNormalized) {
    return recordIdentifierRepo.countRecordsSharing(tenantId, type, valueNormalized);
  }

  @Override
  public Guest createGuest(UUID tenantId) {
    Instant now = Instant.now();
    GuestEntity entity = new GuestEntity(UUID.randomUUID(), tenantId, Map.of(), now, now);
    em.persist(entity);
    return mappers.toDomain(entity);
  }

  @Override
  public void deleteGuest(UUID tenantId, UUID guestId) {
    guestRepo.deleteGuest(tenantId, guestId);
  }

  @Override
  public void linkRecord(UUID tenantId, UUID sourceRecordId, UUID guestId, UUID eventId) {
    em.persist(
        new ResolutionLinkEntity(
            UUID.randomUUID(), tenantId, sourceRecordId, guestId, eventId, Instant.now()));
  }

  @Override
  public void moveLinks(UUID tenantId, UUID fromGuestId, UUID toGuestId, UUID eventId) {
    linkRepo.moveGuest(tenantId, fromGuestId, toGuestId, eventId);
  }

  @Override
  public int unlinkRecords(UUID tenantId, UUID guestId, Collection<UUID> sourceRecordIds) {
    if (sourceRecordIds.isEmpty()) {
      return 0;
    }
    return linkRepo.deleteByRecordIds(tenantId, guestId, sourceRecordIds);
  }

  @Override
  public int linkCount(UUID tenantId, UUID guestId) {
    return linkRepo.countByGuest(tenantId, guestId);
  }

  @Override
  public Optional<UUID> guestOfRecord(UUID tenantId, UUID sourceRecordId) {
    return linkRepo.guestIdByRecord(tenantId, sourceRecordId);
  }

  @Override
  public List<SourceRecord> recordsOfGuest(UUID tenantId, UUID guestId) {
    return sourceRecordStore.findByGuestId(tenantId, guestId);
  }

  @Override
  public void saveEvent(MergeEvent event) {
    em.persist(
        new MergeEventEntity(
            event.id(),
            event.tenantId(),
            event.kind(),
            event.guestId(),
            event.absorbedGuestIds(),
            event.sourceRecordIds(),
            event.matcherName(),
            event.confidence(),
            event.evidence(),
            event.excludedGuestIds(),
            event.createdAt()));
  }

  @Override
  public List<MergeEvent> eventsForGuests(UUID tenantId, Collection<UUID> guestIds) {
    if (guestIds.isEmpty()) {
      return List.of();
    }
    return mergeEventRepo.findByGuestIds(tenantId, guestIds).stream()
        .map(mappers::toDomain)
        .toList();
  }

  @Override
  public void replaceGuestIdentifiers(
      UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers) {
    identifierRepo.deleteForGuest(tenantId, guestId);
    for (NormalizedIdentifier identifier : new LinkedHashSet<>(identifiers)) {
      em.persist(
          new IdentifierEntity(
              UUID.randomUUID(), tenantId, guestId, identifier.type(), identifier.value()));
    }
  }

  @Override
  public void updateGuestProfile(UUID tenantId, UUID guestId, Map<String, Object> profile) {
    guestRepo.updateProfile(tenantId, guestId, jsons.write(profile));
  }

  @Override
  public void saveReview(MatchReview review) {
    em.persist(
        new MatchReviewEntity(
            review.id(),
            review.tenantId(),
            review.status(),
            review.sourceRecordId(),
            review.candidateGuestId(),
            review.identifierType(),
            review.identifierValue(),
            review.reason(),
            review.matcherName(),
            review.confidence(),
            review.createdAt(),
            review.decidedAt(),
            review.decisionEventId()));
  }

  @Override
  public boolean pendingReviewExists(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId) {
    // Hibernate AUTO-flushes pending persists before this JPQL query, so reviews
    // saved earlier in the same resolution are visible.
    return matchReviewRepo.existsByStatus(
        tenantId, sourceRecordId, candidateGuestId, ReviewStatus.PENDING);
  }

  @Override
  public void repointPendingReviews(UUID tenantId, UUID fromGuestId, UUID toGuestId) {
    matchReviewRepo.repointPending(tenantId, fromGuestId, toGuestId);
  }

  @Override
  public void cancelPendingReviews(UUID tenantId, UUID candidateGuestId) {
    matchReviewRepo.deletePending(tenantId, candidateGuestId);
  }

  @Override
  public Optional<MatchReview> findReview(UUID tenantId, UUID reviewId) {
    return matchReviewRepo.findReview(tenantId, reviewId).map(mappers::toDomain);
  }

  @Override
  public int decideReview(UUID tenantId, UUID reviewId, ReviewStatus newStatus, UUID eventId) {
    return matchReviewRepo.decide(tenantId, reviewId, newStatus, Instant.now(), eventId);
  }

  @Override
  public List<UUID> guestIdsByBlockKey(UUID tenantId, BlockKeyType type, String valueNormalized) {
    return recordBlockKeyRepo.guestIdsByBlockKey(tenantId, type, valueNormalized);
  }

  @Override
  public List<BlockKey> blockKeysOfRecord(UUID tenantId, UUID sourceRecordId) {
    // Deterministic, strongest-signal-first order (enum ordinal puts EMAIL_MASKED last):
    // "first block key wins" in candidate dedupe must never let row order decide whether
    // a candidate gets the masked review-only cap.
    return recordBlockKeyRepo.keysOfRecord(tenantId, sourceRecordId).stream()
        .map(e -> new BlockKey(e.getType(), e.getValueNormalized()))
        .sorted(Comparator.comparing(BlockKey::type).thenComparing(BlockKey::value))
        .toList();
  }

  @Override
  public Map<String, Object> guestProfile(UUID tenantId, UUID guestId) {
    return guestRepo
        .findGuest(tenantId, guestId)
        .map(entity -> mappers.toDomain(entity).profile())
        .orElse(Map.of());
  }

  @Override
  public MatchingConfig matchingConfig(UUID tenantId) {
    return tenantRepo
        .matchingConfig(tenantId)
        .orElseThrow(() -> new IllegalStateException("Unknown tenant " + tenantId));
  }

  @Override
  public List<UUID> recordIdsOfGuest(UUID tenantId, UUID guestId) {
    return linkRepo.recordIdsByGuest(tenantId, guestId);
  }

  @Override
  public boolean negativeRuleBetween(
      UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB) {
    if (recordsA.isEmpty() || recordsB.isEmpty()) {
      return false;
    }
    return negativeMatchRuleRepo.existsBetween(tenantId, recordsA, recordsB);
  }

  @Override
  public void saveNegativeRule(NegativeMatchRule rule) {
    // Hibernate AUTO-flush makes same-transaction rules visible; duplicates are absorbed
    // by the unique (tenant, record_a, record_b) constraint at the API level upstream.
    if (!negativeMatchRuleRepo.existsBetween(
        rule.tenantId(), List.of(rule.recordA()), List.of(rule.recordB()))) {
      em.persist(
          new NegativeMatchRuleEntity(
              rule.id(),
              rule.tenantId(),
              rule.recordA(),
              rule.recordB(),
              rule.origin(),
              rule.createdAt()));
    }
  }

  @Override
  public void liftNegativeRulesBetween(
      UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB) {
    if (!recordsA.isEmpty() && !recordsB.isEmpty()) {
      negativeMatchRuleRepo.liftBetween(tenantId, recordsA, recordsB);
    }
  }

  @Override
  public List<IdentifierQualityRule> qualityRules(UUID tenantId) {
    List<IdentifierQualityRule> all = new ArrayList<>(BuiltinQualityRules.RULES);
    all.addAll(mappers.toDomainQualityRules(identifierQualityRuleRepo.listByTenant(tenantId)));
    return all;
  }
}
