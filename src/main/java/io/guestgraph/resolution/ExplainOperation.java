package io.guestgraph.resolution;

import io.guestgraph.domain.MergeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Why are these records one guest? Collects the full MergeEvent chain for a guest, transitively
 * including the events of every absorbed guest (Constitution IV). Pure JVM behind {@link
 * GraphPort}; existence of the guest is the caller's concern.
 */
public class ExplainOperation {

  private final GraphPort graph;

  public ExplainOperation(GraphPort graph) {
    this.graph = graph;
  }

  public List<MergeEvent> explain(UUID tenantId, UUID guestId) {
    Set<UUID> visited = new LinkedHashSet<>();
    List<UUID> frontier = List.of(guestId);
    List<MergeEvent> chain = new ArrayList<>();
    // One batched query per merge-chain level, not one per absorbed guest.
    while (!frontier.isEmpty()) {
      List<UUID> level = frontier.stream().filter(visited::add).toList();
      if (level.isEmpty()) {
        break;
      }
      List<UUID> next = new ArrayList<>();
      for (MergeEvent event : graph.eventsForGuests(tenantId, level)) {
        chain.add(event);
        next.addAll(event.absorbedGuestIds());
      }
      frontier = next;
    }
    chain.sort(Comparator.comparing(MergeEvent::createdAt));
    return chain;
  }
}
