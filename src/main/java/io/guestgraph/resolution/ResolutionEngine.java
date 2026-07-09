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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Storage-agnostic resolution orchestrator: builds candidates from shared strong
 * identifiers, lets the {@link ResolutionStrategy} score them, executes the
 * create/attach/merge outcome, records MergeEvents, parks suspicious matches for
 * review, and recomputes the affected guest's identifiers and golden profile.
 *
 * <p>Callers are responsible for transaction + per-tenant lock (TenantLock).
 */
public class ResolutionEngine {

    private final GraphPort graph;
    private final ResolutionStrategy strategy;
    private final GoldenProfileDeriver profileDeriver;

    public ResolutionEngine(GraphPort graph, ResolutionStrategy strategy, GoldenProfileDeriver profileDeriver) {
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

        Set<UUID> matchedGuests = new LinkedHashSet<>();
        List<MatchDecision> reviewDecisions = new ArrayList<>();
        for (MatchDecision decision : decisions) {
            switch (decision.disposition()) {
                case MATCH -> matchedGuests.add(decision.guestId());
                case REVIEW -> reviewDecisions.add(decision);
            }
        }
        // A guest safely matched via another identifier needs no parallel review entry.
        reviewDecisions.removeIf(d -> matchedGuests.contains(d.guestId()));

        Executed executed = execute(record, matchedGuests);
        List<UUID> reviewIds = queueReviews(record, reviewDecisions);
        rebuildGuest(tenantId, executed.guestId());

        return new ResolutionOutcome(executed.status(), executed.guestId(), reviewIds);
    }

    private List<MatchCandidate> findCandidates(SourceRecord record, Set<UUID> excludedGuestIds) {
        List<MatchCandidate> candidates = new ArrayList<>();
        for (NormalizedIdentifier identifier : record.identifiers()) {
            int sharing = graph.recordsSharingIdentifier(record.tenantId(), identifier.type(), identifier.value());
            for (UUID guestId : graph.guestIdsByIdentifier(record.tenantId(), identifier.type(), identifier.value())) {
                if (!excludedGuestIds.contains(guestId)) {
                    candidates.add(new MatchCandidate(guestId, identifier, sharing));
                }
            }
        }
        return candidates;
    }

    private Executed execute(SourceRecord record, Set<UUID> matchedGuests) {
        UUID tenantId = record.tenantId();
        Map<String, Object> evidence = evidence(record);
        if (matchedGuests.isEmpty()) {
            Guest guest = graph.createGuest(tenantId);
            MergeEvent event = event(tenantId, MergeEventKind.CREATE, guest.id(), List.of(), record, evidence);
            graph.saveEvent(event);
            graph.linkRecord(tenantId, record.id(), guest.id(), event.id());
            return new Executed(IngestStatus.CREATED_GUEST, guest.id());
        }
        if (matchedGuests.size() == 1) {
            UUID guestId = matchedGuests.iterator().next();
            MergeEvent event = event(tenantId, MergeEventKind.ATTACH, guestId, List.of(), record, evidence);
            graph.saveEvent(event);
            graph.linkRecord(tenantId, record.id(), guestId, event.id());
            return new Executed(IngestStatus.ATTACHED, guestId);
        }
        // 2+ matches: transitive identity — merge all matched guests into a survivor.
        List<UUID> guests = new ArrayList<>(matchedGuests);
        UUID survivor = guests.getFirst();
        List<UUID> absorbed = guests.subList(1, guests.size());
        MergeEvent event = event(tenantId, MergeEventKind.MERGE, survivor, absorbed, record, evidence);
        graph.saveEvent(event);
        for (UUID absorbedGuest : absorbed) {
            graph.moveLinks(tenantId, absorbedGuest, survivor, event.id());
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
            MatchReview review = new MatchReview(UUID.randomUUID(), record.tenantId(), ReviewStatus.PENDING,
                    record.id(), decision.guestId(), decision.identifier().type(), decision.identifier().value(),
                    decision.reason(), decision.matcherName(), decision.confidence(), Instant.now(), null, null);
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

    private MergeEvent event(UUID tenantId, MergeEventKind kind, UUID guestId, List<UUID> absorbed,
            SourceRecord record, Map<String, Object> evidence) {
        return new MergeEvent(UUID.randomUUID(), tenantId, kind, guestId, List.copyOf(absorbed),
                List.of(record.id()), strategy.name(), java.math.BigDecimal.ONE, evidence, List.of(), Instant.now());
    }

    private Map<String, Object> evidence(SourceRecord record) {
        return Map.of("identifiers", record.identifiers().stream()
                .map(i -> Map.of("type", i.type().name(), "value", i.value()))
                .toList());
    }

    private record Executed(IngestStatus status, UUID guestId) {
    }
}
