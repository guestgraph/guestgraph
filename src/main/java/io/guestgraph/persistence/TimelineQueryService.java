package io.guestgraph.persistence;

import io.guestgraph.api.Cursor;
import io.guestgraph.persistence.repo.RecordObjectRepo;
import io.guestgraph.timeline.Association;
import io.guestgraph.timeline.AssociationDeriver;
import io.guestgraph.timeline.ObjectObservation;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a guest's timeline and a business object's roster. Purely a read model: it persists
 * nothing, so association state cannot drift from the records it summarises (FR-010), and a roster
 * read while a version is still being delivered simply reflects what has landed (FR-010a).
 */
@Service
public class TimelineQueryService {

  private final RecordObjectRepo repo;

  public TimelineQueryService(RecordObjectRepo repo) {
    this.repo = repo;
  }

  public record Page(List<Association> items, String nextCursor) {}

  public record SourceObject(
      String sourceSystem,
      String objectType,
      String objectId,
      Instant currentVersion,
      Instant businessStart,
      Instant businessEnd,
      List<ObjectObservation> roster,
      List<ObjectObservation> observations) {}

  @Transactional(readOnly = true)
  public Page timeline(UUID tenantId, UUID guestId, boolean includePast, int limit, String cursor) {
    List<ObjectObservation> observations = repo.observationsForGuestObjects(tenantId, guestId);
    List<Association> all = AssociationDeriver.derive(guestId, observations, includePast);

    List<Association> after = cursor == null ? all : skipPast(all, Cursor.decode(cursor));
    List<Association> page = after.stream().limit(limit).toList();
    String next =
        after.size() > limit
            ? Cursor.encode(page.getLast().orderingTime(), page.getLast().orderingKey())
            : null;
    return new Page(page, next);
  }

  @Transactional(readOnly = true)
  public SourceObject sourceObject(
      UUID tenantId, String sourceSystem, String objectType, String objectId) {
    List<ObjectObservation> observations =
        repo.observationsOfObject(tenantId, sourceSystem, objectType, objectId);
    if (observations.isEmpty()) {
      return null;
    }
    Instant currentVersion =
        observations.stream()
            .map(ObjectObservation::objectVersion)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    List<ObjectObservation> roster =
        observations.stream().filter(o -> o.objectVersion().equals(currentVersion)).toList();
    ObjectObservation first = roster.getFirst();
    return new SourceObject(
        first.sourceSystemCode(),
        objectType,
        objectId,
        currentVersion,
        first.businessStart(),
        first.businessEnd(),
        roster,
        observations);
  }

  /**
   * Keyset seek: drop everything up to and including the cursor's key, comparing against the key
   * directly rather than locating the row it came from. The row may no longer be there — an
   * association can end between page requests — and a seek that depended on finding it would then
   * fall back to a coarser comparison and skip its equal-timed neighbours.
   */
  private static List<Association> skipPast(List<Association> all, Cursor.Key key) {
    return all.stream().filter(a -> compareToKey(a, key) > 0).toList();
  }

  private static int compareToKey(Association a, Cursor.Key key) {
    int byTime = a.orderingTime().compareTo(key.time());
    return byTime != 0 ? byTime : a.orderingKey().compareTo(key.id());
  }
}
