package io.guestgraph.resolution;

import io.guestgraph.domain.MergeEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
    Deque<UUID> frontier = new ArrayDeque<>();
    frontier.add(guestId);
    List<MergeEvent> chain = new ArrayList<>();
    while (!frontier.isEmpty()) {
      UUID current = frontier.poll();
      if (!visited.add(current)) {
        continue;
      }
      for (MergeEvent event : graph.eventsForGuests(tenantId, List.of(current))) {
        chain.add(event);
        frontier.addAll(event.absorbedGuestIds());
      }
    }
    chain.sort(Comparator.comparing(MergeEvent::createdAt));
    return chain;
  }
}
