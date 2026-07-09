package io.guestgraph.resolution;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NormalizedIdentifier;
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
}
