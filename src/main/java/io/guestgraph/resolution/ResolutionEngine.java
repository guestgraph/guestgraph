package io.guestgraph.resolution;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.survivorship.GoldenProfileDeriver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Storage-agnostic resolution orchestrator: builds candidates from shared strong identifiers, lets
 * the {@link ResolutionStrategy} score them, executes the create/attach/merge outcome, records
 * MergeEvents, parks suspicious matches for review, and recomputes the affected guest's identifiers
 * and golden profile.
 *
 * <p>Callers are responsible for transaction + per-tenant lock (TenantLock).
 */
public class ResolutionEngine {

  private final GraphPort graph;
  private final ResolutionStrategy strategy;
  private final GoldenProfileDeriver profileDeriver;

  public ResolutionEngine(
      GraphPort graph, ResolutionStrategy strategy, GoldenProfileDeriver profileDeriver) {
    this.graph = graph;
    this.strategy = strategy;
    this.profileDeriver = profileDeriver;
  }

  public ResolutionOutcome resolve(SourceRecord record) {
    return resolve(record, Set.of());
  }

  /** {@code excludedGuestIds}: guests this record must not rejoin (unmerge replay, R8). */
  public ResolutionOutcome resolve(SourceRecord record, Set<UUID> excludedGuestIds) {
    UUID tenantId = record.tenantId();

    List<MatchCandidate> candidates = findCandidates(record, excludedGuestIds);
    List<MatchDecision> decisions =
        strategy.score(record, candidates, graph.reviewThreshold(tenantId));

    List<MatchDecision> matchDecisions = new ArrayList<>();
    Set<UUID> matchedGuests = new LinkedHashSet<>();
    List<MatchDecision> reviewDecisions = new ArrayList<>();
    for (MatchDecision decision : decisions) {
      switch (decision.disposition()) {
        case MATCH -> {
          matchDecisions.add(decision);
          matchedGuests.add(decision.guestId());
        }
        case REVIEW -> reviewDecisions.add(decision);
      }
    }
    // A guest safely matched via another identifier needs no parallel review entry.
    reviewDecisions.removeIf(d -> matchedGuests.contains(d.guestId()));

    Executed executed = execute(record, matchedGuests, matchDecisions);
    List<UUID> reviewIds = queueReviews(record, reviewDecisions);
    rebuildGuest(tenantId, executed.guestId());

    return new ResolutionOutcome(executed.status(), executed.guestId(), reviewIds);
  }

  private List<MatchCandidate> findCandidates(SourceRecord record, Set<UUID> excludedGuestIds) {
    List<MatchCandidate> candidates = new ArrayList<>();
    for (NormalizedIdentifier identifier : record.identifiers()) {
      int sharing =
          graph.recordsSharingIdentifier(record.tenantId(), identifier.type(), identifier.value());
      for (UUID guestId :
          graph.guestIdsByIdentifier(record.tenantId(), identifier.type(), identifier.value())) {
        if (!excludedGuestIds.contains(guestId)) {
          candidates.add(new MatchCandidate(guestId, identifier, sharing));
        }
      }
    }
    return candidates;
  }

  private Executed execute(
      SourceRecord record, Set<UUID> matchedGuests, List<MatchDecision> matchDecisions) {
    UUID tenantId = record.tenantId();
    if (matchedGuests.isEmpty()) {
      Guest guest = graph.createGuest(tenantId);
      // Creation is not a match decision, so the event carries the strategy's own name
      // and full confidence; evidence is what the record itself contributed.
      MergeEvent event =
          new MergeEvent(
              UUID.randomUUID(),
              tenantId,
              MergeEventKind.CREATE,
              guest.id(),
              List.of(),
              List.of(record.id()),
              strategy.name(),
              BigDecimal.ONE,
              Map.of("identifiers", identifiersAsMaps(record.identifiers())),
              List.of(),
              Instant.now());
      graph.saveEvent(event);
      graph.linkRecord(tenantId, record.id(), guest.id(), event.id());
      return new Executed(IngestStatus.CREATED_GUEST, guest.id());
    }
    if (matchedGuests.size() == 1) {
      UUID guestId = matchedGuests.iterator().next();
      MergeEvent event =
          decisionEvent(
              tenantId, MergeEventKind.ATTACH, guestId, List.of(), record, matchDecisions);
      graph.saveEvent(event);
      graph.linkRecord(tenantId, record.id(), guestId, event.id());
      return new Executed(IngestStatus.ATTACHED, guestId);
    }
    // 2+ matches: transitive identity — merge all matched guests into a survivor.
    List<UUID> guests = new ArrayList<>(matchedGuests);
    UUID survivor = guests.getFirst();
    List<UUID> absorbed = guests.subList(1, guests.size());
    MergeEvent event =
        decisionEvent(tenantId, MergeEventKind.MERGE, survivor, absorbed, record, matchDecisions);
    graph.saveEvent(event);
    for (UUID absorbedGuest : absorbed) {
      graph.moveLinks(tenantId, absorbedGuest, survivor, event.id());
      // Pending reviews naming the absorbed guest must follow it, or confirm/reject
      // would later dereference a deleted guest (schema has no FK by design).
      graph.repointPendingReviews(tenantId, absorbedGuest, survivor);
      graph.deleteGuest(tenantId, absorbedGuest);
    }
    graph.linkRecord(tenantId, record.id(), survivor, event.id());
    return new Executed(IngestStatus.MERGED, survivor);
  }

  private List<UUID> queueReviews(SourceRecord record, List<MatchDecision> reviewDecisions) {
    List<UUID> reviewIds = new ArrayList<>();
    Set<UUID> queuedGuests = new LinkedHashSet<>();
    for (MatchDecision decision : reviewDecisions) {
      if (!queuedGuests.add(decision.guestId())
          || graph.pendingReviewExists(record.tenantId(), record.id(), decision.guestId())) {
        continue;
      }
      MatchReview review =
          new MatchReview(
              UUID.randomUUID(),
              record.tenantId(),
              ReviewStatus.PENDING,
              record.id(),
              decision.guestId(),
              decision.identifier().type(),
              decision.identifier().value(),
              decision.reason(),
              decision.matcherName(),
              decision.confidence(),
              Instant.now(),
              null,
              null);
      graph.saveReview(review);
      reviewIds.add(review.id());
    }
    return reviewIds;
  }

  /** Recomputes a guest's identifiers and golden profile from its linked records. */
  public void rebuildGuest(UUID tenantId, UUID guestId) {
    List<SourceRecord> records = graph.recordsOfGuest(tenantId, guestId);
    Set<NormalizedIdentifier> identifiers = new TreeSet<>();
    for (SourceRecord linked : records) {
      identifiers.addAll(linked.identifiers());
    }
    graph.replaceGuestIdentifiers(tenantId, guestId, identifiers);
    graph.updateGuestProfile(tenantId, guestId, profileDeriver.derive(records));
  }

  /**
   * ATTACH/MERGE events carry the audit metadata of the decisions that caused them (FR-009/FR-010):
   * the deciding matcher(s), the weakest contributing confidence, and per-guest matched identifiers
   * with their sharing counts — the "why" explain shows.
   */
  private MergeEvent decisionEvent(
      UUID tenantId,
      MergeEventKind kind,
      UUID guestId,
      List<UUID> absorbed,
      SourceRecord record,
      List<MatchDecision> matchDecisions) {
    Set<String> matcherNames = new LinkedHashSet<>();
    BigDecimal confidence = BigDecimal.ONE;
    List<Map<String, Object>> matches = new ArrayList<>();
    for (MatchDecision decision : matchDecisions) {
      matcherNames.add(decision.matcherName());
      confidence = confidence.min(decision.confidence());
      Map<String, Object> match = new LinkedHashMap<>();
      match.put("guestId", decision.guestId().toString());
      match.put(
          "identifier",
          Map.of(
              "type", decision.identifier().type().name(),
              "value", decision.identifier().value()));
      match.put("recordsSharingIdentifier", decision.recordsSharingIdentifier());
      match.put("reason", decision.reason());
      matches.add(match);
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("matches", matches);
    evidence.put("recordIdentifiers", identifiersAsMaps(record.identifiers()));
    return new MergeEvent(
        UUID.randomUUID(),
        tenantId,
        kind,
        guestId,
        List.copyOf(absorbed),
        List.of(record.id()),
        String.join(",", matcherNames),
        confidence,
        evidence,
        List.of(),
        Instant.now());
  }

  private List<Map<String, String>> identifiersAsMaps(List<NormalizedIdentifier> identifiers) {
    return identifiers.stream()
        .map(i -> Map.of("type", i.type().name(), "value", i.value()))
        .toList();
  }

  private record Executed(IngestStatus status, UUID guestId) {}
}
