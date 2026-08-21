package io.guestgraph.timeline;

import io.guestgraph.domain.ObjectRole;
import io.guestgraph.timeline.ObjectObservation.ObjectKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns observations into a guest's timeline. Pure: no Spring, no JPA, no clock — everything it
 * decides follows from the observations handed to it, which is what makes association state
 * recomputable by construction (FR-010).
 *
 * <p>The object <em>version</em> is the unit of supersession. Each version's observations form a
 * roster — a complete statement of who was on the object then — and the newest roster alone decides
 * who is on it now (FR-002). Persons are deliberately never matched from one version to the next:
 * sources such as PMS reservations carry entity-less persons with no id to follow across edits, so
 * any attempt to track them would have to guess, and would report a reassignment every time a
 * booking's guest list shrank.
 */
public final class AssociationDeriver {

  /** The one ordering the sort, the cursor, and the keyset seek all agree on (FR-004a). */
  public static final Comparator<Association> ORDER =
      Comparator.comparing(Association::orderingTime).thenComparing(Association::orderingKey);

  private AssociationDeriver() {}

  /**
   * @param observations every observation of every object this guest appears on — including those
   *     belonging to other guests, because a newer version naming someone else is precisely what
   *     removes this guest from a booking
   * @param includeEnded whether associations the guest no longer holds are returned, marked ENDED
   */
  public static List<Association> derive(
      UUID guestId, Collection<ObjectObservation> observations, boolean includeEnded) {
    Map<ObjectKey, List<ObjectObservation>> byObject =
        observations.stream().collect(Collectors.groupingBy(ObjectObservation::objectKey));

    List<Association> associations = new ArrayList<>();
    byObject.values().forEach(o -> associations.addAll(forObject(guestId, o, includeEnded)));
    // Total ordering: the tiebreaker must distinguish every pair, or a page boundary between
    // two equal-comparing entries drops one of them.
    associations.sort(ORDER);
    return List.copyOf(associations);
  }

  private static List<Association> forObject(
      UUID guestId, List<ObjectObservation> observations, boolean includeEnded) {
    Instant currentVersion =
        observations.stream()
            .map(ObjectObservation::objectVersion)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    List<ObjectObservation> currentRoster =
        observations.stream().filter(o -> o.objectVersion().equals(currentVersion)).toList();

    List<Association> result = new ArrayList<>();
    Set<ObjectRole> rolesHeldNow = new LinkedHashSet<>();

    for (ObjectRole role : rolesOf(currentRoster)) {
      List<ObjectObservation> mine =
          currentRoster.stream()
              .filter(o -> o.role() == role && guestId.equals(o.guestId()))
              .toList();
      if (mine.isEmpty()) {
        continue;
      }
      rolesHeldNow.add(role);
      // One entry per (object, role) even when the same human was entered twice.
      result.add(
          association(
              guestId,
              mine.getFirst(),
              AssociationStatus.CURRENT,
              null,
              countOf(observations, role)));
    }

    if (includeEnded) {
      for (ObjectRole role : rolesOf(observations)) {
        if (rolesHeldNow.contains(role)) {
          continue;
        }
        ObjectObservation last = lastHeldBy(guestId, observations, role);
        if (last == null) {
          continue;
        }
        result.add(
            association(
                guestId,
                last,
                AssociationStatus.ENDED,
                successor(guestId, observations, currentRoster, role, last.objectVersion()),
                countOf(observations, role)));
      }
    }
    return result;
  }

  /**
   * A successor is named only for a genuine one-to-one handover: the role had a single occupant in
   * the version where this guest last held it, has a single occupant now, and they differ (FR-007).
   *
   * <p>Anything else names nobody. When a booking drops one of two additional guests, the guest who
   * remains did not take the other's place — saying so would invent exactly the transfer that
   * positional slot-tracking gets wrong, which is the failure this whole model exists to avoid.
   */
  private static UUID successor(
      UUID guestId,
      List<ObjectObservation> observations,
      List<ObjectObservation> currentRoster,
      ObjectRole role,
      Instant lastHeldVersion) {
    Set<UUID> heldThen =
        observations.stream()
            .filter(o -> o.role() == role && o.objectVersion().equals(lastHeldVersion))
            .map(ObjectObservation::guestId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<UUID> heldNow =
        currentRoster.stream()
            .filter(o -> o.role() == role)
            .map(ObjectObservation::guestId)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (heldThen.size() != 1 || heldNow.size() != 1) {
      return null;
    }
    UUID now = heldNow.iterator().next();
    return now.equals(guestId) ? null : now;
  }

  private static ObjectObservation lastHeldBy(
      UUID guestId, List<ObjectObservation> observations, ObjectRole role) {
    return observations.stream()
        .filter(o -> o.role() == role && guestId.equals(o.guestId()))
        .max(Comparator.comparing(ObjectObservation::objectVersion))
        .orElse(null);
  }

  private static Set<ObjectRole> rolesOf(List<ObjectObservation> observations) {
    return observations.stream()
        .map(ObjectObservation::role)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static int countOf(List<ObjectObservation> observations, ObjectRole role) {
    return (int) observations.stream().filter(o -> o.role() == role).count();
  }

  private static Association association(
      UUID guestId,
      ObjectObservation observation,
      AssociationStatus status,
      UUID successorGuestId,
      int observationCount) {
    return new Association(
        guestId,
        observation.sourceSystemCode(),
        observation.objectType(),
        observation.objectId(),
        observation.role(),
        observation.position(),
        status,
        successorGuestId,
        observation.businessStart(),
        observation.businessEnd(),
        observationCount,
        observation);
  }
}
