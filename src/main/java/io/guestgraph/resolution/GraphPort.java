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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the resolution engine needs from storage. The engine itself is storage-agnostic so the
 * table-driven scenario tests run on a pure in-memory implementation; production wires a
 * Postgres-backed adapter.
 */
public interface GraphPort {

  int reviewThreshold(UUID tenantId);

  List<UUID> guestIdsByIdentifier(UUID tenantId, IdentifierType type, String valueNormalized);

  /**
   * Distinct source records in the tenant carrying this identifier (including the one being
   * resolved).
   */
  int recordsSharingIdentifier(UUID tenantId, IdentifierType type, String valueNormalized);

  Guest createGuest(UUID tenantId);

  void deleteGuest(UUID tenantId, UUID guestId);

  void linkRecord(UUID tenantId, UUID sourceRecordId, UUID guestId, UUID eventId);

  void moveLinks(UUID tenantId, UUID fromGuestId, UUID toGuestId, UUID eventId);

  int unlinkRecords(UUID tenantId, UUID guestId, Collection<UUID> sourceRecordIds);

  int linkCount(UUID tenantId, UUID guestId);

  Optional<UUID> guestOfRecord(UUID tenantId, UUID sourceRecordId);

  List<SourceRecord> recordsOfGuest(UUID tenantId, UUID guestId);

  void saveEvent(MergeEvent event);

  List<MergeEvent> eventsForGuests(UUID tenantId, Collection<UUID> guestIds);

  void replaceGuestIdentifiers(
      UUID tenantId, UUID guestId, Collection<NormalizedIdentifier> identifiers);

  void updateGuestProfile(UUID tenantId, UUID guestId, Map<String, Object> profile);

  void saveReview(MatchReview review);

  boolean pendingReviewExists(UUID tenantId, UUID sourceRecordId, UUID candidateGuestId);

  /** Re-points PENDING reviews naming {@code fromGuestId} to {@code toGuestId} (merge survivor). */
  void repointPendingReviews(UUID tenantId, UUID fromGuestId, UUID toGuestId);

  /** Drops PENDING reviews naming a guest that ceased to exist (unmerge emptied it). */
  void cancelPendingReviews(UUID tenantId, UUID candidateGuestId);

  Optional<MatchReview> findReview(UUID tenantId, UUID reviewId);

  /** Single PENDING → decided transition; returns 0 when the review was already decided. */
  int decideReview(UUID tenantId, UUID reviewId, ReviewStatus newStatus, UUID decisionEventId);

  // --- probabilistic matching (slice 2) ---

  /** Fuzzy candidates: guests whose linked records share this blocking key. */
  List<UUID> guestIdsByBlockKey(UUID tenantId, BlockKeyType type, String valueNormalized);

  /** The blocking keys stored for a record at ingest. */
  List<BlockKey> blockKeysOfRecord(UUID tenantId, UUID sourceRecordId);

  /** The guest's golden profile, or an empty map if the guest is unknown. */
  Map<String, Object> guestProfile(UUID tenantId, UUID guestId);

  /** Per-tenant score bands + sharing threshold. */
  MatchingConfig matchingConfig(UUID tenantId);

  List<UUID> recordIdsOfGuest(UUID tenantId, UUID guestId);

  /** Does a do-not-merge rule span the two record sets? */
  boolean negativeRuleBetween(UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB);

  void saveNegativeRule(NegativeMatchRule rule);

  /** Confirm across a rule lifts every spanning rule (FR-011). */
  void liftNegativeRulesBetween(
      UUID tenantId, Collection<UUID> recordsA, Collection<UUID> recordsB);

  /** Tenant quality rules merged with the built-in defaults. */
  List<IdentifierQualityRule> qualityRules(UUID tenantId);
}
