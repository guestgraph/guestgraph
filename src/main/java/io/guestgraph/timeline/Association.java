package io.guestgraph.timeline;

import io.guestgraph.domain.ObjectRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A guest's link to a business object in one role — one timeline entry (FR-003, FR-004).
 *
 * <p>Derived on read and never persisted, so it is recomputable from the immutable observations and
 * their resolution links by construction (FR-010).
 *
 * <p>{@code successorGuestId} is set only for {@link AssociationStatus#ENDED} entries, and only
 * when exactly one guest holds the role in the current roster. A guest simply dropped from an
 * object has no successor, and saying otherwise would invent a transfer that never happened
 * (FR-007).
 */
public record Association(
    UUID guestId,
    String sourceSystem,
    String objectType,
    String objectId,
    ObjectRole role,
    Integer position,
    AssociationStatus status,
    UUID successorGuestId,
    Instant businessStart,
    Instant businessEnd,
    int observationCount,
    ObjectObservation currentObservation) {

  /** Unit separator: cannot occur in a source system code, object type, or role name. */
  private static final String SEP = "\u001f";

  /** Primary sort: business start, falling back to the observation timestamp (FR-004a). */
  public Instant orderingTime() {
    return currentObservation.orderingTime();
  }

  /**
   * The tiebreaker that makes the ordering total, and the half of the cursor that is not the time.
   *
   * <p>Every field that distinguishes two associations is here, so no two can compare equal — one
   * object may legitimately produce two entries (a booker who is also the primary guest), and two
   * source systems may use the same object id. Without all of them a page boundary falling between
   * two equal-comparing entries drops one of them silently.
   *
   * <p>{@code objectId} comes last because it is source-supplied and may contain anything,
   * including the separator; everything before it is drawn from a controlled vocabulary.
   */
  public String orderingKey() {
    return sourceSystem + SEP + objectType + SEP + role.name() + SEP + objectId;
  }
}
