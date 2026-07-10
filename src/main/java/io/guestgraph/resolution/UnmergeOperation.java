package io.guestgraph.resolution;

import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import io.guestgraph.domain.SourceRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Splits a wrong merge (Constitution IV, research R8): detaches the given records, records an
 * UNMERGE event whose exclusion prevents the replay from silently recreating the identical wrong
 * merge, re-resolves the detached records, and recomputes (or removes) the remaining guest. Source
 * records are never altered.
 *
 * <p>Pure JVM behind {@link GraphPort}; transaction + tenant lock are the caller's concern.
 */
public class UnmergeOperation {

  public static final String MATCHER_NAME = "manual-unmerge";

  /** {@code remainingGuestId} is null when every record was detached and the guest removed. */
  public record UnmergeResult(
      UUID unmergeEventId, UUID remainingGuestId, Map<UUID, UUID> detachedRecordToGuest) {}

  private final GraphPort graph;
  private final ResolutionEngine engine;

  public UnmergeOperation(GraphPort graph, ResolutionEngine engine) {
    this.graph = graph;
    this.engine = engine;
  }

  public UnmergeResult unmerge(UUID tenantId, UUID guestId, List<UUID> requestedRecordIds) {
    // Dedupe + null-guard: a repeated id must not replay (and re-link) the same record twice.
    List<UUID> sourceRecordIds =
        requestedRecordIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (sourceRecordIds.isEmpty()) {
      throw new InvalidUnmergeException("No source record ids given to detach");
    }
    if (graph.linkCount(tenantId, guestId) <= 1) {
      throw new InvalidUnmergeException(
          "Guest " + guestId + " has a single source record — nothing to split");
    }
    List<SourceRecord> linkedRecords = graph.recordsOfGuest(tenantId, guestId);
    Map<UUID, SourceRecord> byId = new LinkedHashMap<>();
    for (SourceRecord record : linkedRecords) {
      byId.put(record.id(), record);
    }
    for (UUID recordId : sourceRecordIds) {
      if (!byId.containsKey(recordId)) {
        throw new InvalidUnmergeException(
            "Source record " + recordId + " is not linked to guest " + guestId);
      }
    }

    graph.unlinkRecords(tenantId, guestId, sourceRecordIds);
    MergeEvent unmergeEvent =
        new MergeEvent(
            UUID.randomUUID(),
            tenantId,
            MergeEventKind.UNMERGE,
            guestId,
            List.of(),
            List.copyOf(sourceRecordIds),
            MATCHER_NAME,
            BigDecimal.ONE,
            Map.of("reason", "operator unmerge — detached records replay excluding this guest"),
            List.of(guestId),
            Instant.now());
    graph.saveEvent(unmergeEvent);

    // Replay: detached records re-resolve among themselves and the rest of the tenant,
    // but never back onto the guest they were just detached from (R8).
    Map<UUID, UUID> detachedToGuest = new LinkedHashMap<>();
    for (UUID recordId : sourceRecordIds) {
      ResolutionOutcome outcome = engine.resolve(byId.get(recordId), Set.of(guestId));
      detachedToGuest.put(recordId, outcome.guestId());
    }

    UUID remainingGuestId = guestId;
    if (graph.linkCount(tenantId, guestId) == 0) {
      // Everything left: the guest ceases to exist; reviews naming it are moot.
      graph.cancelPendingReviews(tenantId, guestId);
      graph.deleteGuest(tenantId, guestId);
      remainingGuestId = null;
    } else {
      engine.rebuildGuest(tenantId, guestId);
    }
    return new UnmergeResult(unmergeEvent.id(), remainingGuestId, detachedToGuest);
  }
}
