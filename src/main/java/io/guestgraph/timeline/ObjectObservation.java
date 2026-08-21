package io.guestgraph.timeline;

import io.guestgraph.domain.ObjectRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One source record's statement about a business object: at this version, this person held this
 * role, and resolution linked them to this guest.
 *
 * <p>Observations are never matched to one another across versions (FR-003). Each version stands
 * alone as a complete statement, which is what lets sources with entity-less persons be handled
 * without guessing.
 */
public record ObjectObservation(
    UUID sourceRecordId,
    UUID sourceSystemId,
    String sourceSystemCode,
    String objectType,
    String objectId,
    ObjectRole role,
    Integer position,
    Instant objectVersion,
    Instant businessStart,
    Instant businessEnd,
    UUID guestId,
    Map<String, Object> extracted,
    Instant recordTimestamp,
    boolean needsReview) {

  /** Identity of the object this observation belongs to — the roster grouping key (FR-002). */
  public ObjectKey objectKey() {
    return new ObjectKey(sourceSystemId, objectType, objectId);
  }

  /** Ordering fallback when the submitter supplied no business start (FR-004a). */
  public Instant orderingTime() {
    return businessStart != null ? businessStart : recordTimestamp;
  }

  public record ObjectKey(UUID sourceSystemId, String objectType, String objectId) {}
}
