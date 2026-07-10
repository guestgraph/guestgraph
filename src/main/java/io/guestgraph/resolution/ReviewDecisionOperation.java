package io.guestgraph.resolution;

import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import io.guestgraph.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Confirms or rejects a parked suspicious match (FR-018): confirmation executes the merge and
 * records it like any automatic decision (REVIEW_CONFIRM, matcher manual-review); rejection keeps
 * the records separate and is itself recorded.
 *
 * <p>Pure JVM behind {@link GraphPort}; transaction + tenant lock are the caller's concern.
 */
public class ReviewDecisionOperation {

  public static final String MATCHER_NAME = "manual-review";

  private final GraphPort graph;
  private final ResolutionEngine engine;

  public ReviewDecisionOperation(GraphPort graph, ResolutionEngine engine) {
    this.graph = graph;
    this.engine = engine;
  }

  public MatchReview decide(UUID tenantId, UUID reviewId, boolean confirm) {
    MatchReview review =
        graph
            .findReview(tenantId, reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));
    if (review.status() != ReviewStatus.PENDING) {
      throw new ReviewAlreadyDecidedException(reviewId, review.status());
    }

    // The event must exist before any link references it (FK created_by_event_id).
    MergeEvent event =
        confirm ? buildConfirmEvent(tenantId, review) : recordReject(tenantId, review);
    graph.saveEvent(event);
    if (confirm) {
      applyConfirm(tenantId, event);
    }
    ReviewStatus newStatus = confirm ? ReviewStatus.CONFIRMED : ReviewStatus.REJECTED;
    if (graph.decideReview(tenantId, reviewId, newStatus, event.id()) == 0) {
      // Guarded transition: a concurrent decision won (should not happen under TenantLock).
      // Re-read for the message — the status we loaded at entry is the stale PENDING.
      ReviewStatus current =
          graph.findReview(tenantId, reviewId).map(MatchReview::status).orElse(review.status());
      throw new ReviewAlreadyDecidedException(reviewId, current);
    }
    if (confirm) {
      engine.rebuildGuest(tenantId, review.candidateGuestId());
    }
    return graph
        .findReview(tenantId, reviewId)
        .orElseThrow(() -> new ReviewNotFoundException(reviewId));
  }

  private MergeEvent buildConfirmEvent(UUID tenantId, MatchReview review) {
    UUID recordGuest =
        graph
            .guestOfRecord(tenantId, review.sourceRecordId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Reviewed record " + review.sourceRecordId() + " has no guest link"));
    UUID survivor = review.candidateGuestId();
    List<UUID> absorbed = recordGuest.equals(survivor) ? List.of() : List.of(recordGuest);
    return new MergeEvent(
        UUID.randomUUID(),
        tenantId,
        MergeEventKind.REVIEW_CONFIRM,
        survivor,
        absorbed,
        List.of(review.sourceRecordId()),
        MATCHER_NAME,
        BigDecimal.ONE,
        evidence(review, "confirmed by steward — merge executed"),
        List.of(),
        Instant.now());
  }

  private void applyConfirm(UUID tenantId, MergeEvent event) {
    for (UUID absorbedGuest : event.absorbedGuestIds()) {
      graph.moveLinks(tenantId, absorbedGuest, event.guestId(), event.id());
      graph.repointPendingReviews(tenantId, absorbedGuest, event.guestId());
      graph.deleteGuest(tenantId, absorbedGuest);
    }
  }

  private MergeEvent recordReject(UUID tenantId, MatchReview review) {
    return new MergeEvent(
        UUID.randomUUID(),
        tenantId,
        MergeEventKind.REVIEW_REJECT,
        review.candidateGuestId(),
        List.of(),
        List.of(review.sourceRecordId()),
        MATCHER_NAME,
        BigDecimal.ONE,
        evidence(review, "rejected by steward — records stay separate"),
        List.of(),
        Instant.now());
  }

  private Map<String, Object> evidence(MatchReview review, String outcome) {
    return Map.of(
        "reviewId", review.id().toString(),
        "identifier",
            Map.of("type", review.identifierType().name(), "value", review.identifierValue()),
        "parkedBecause", review.reason(),
        "outcome", outcome);
  }
}
