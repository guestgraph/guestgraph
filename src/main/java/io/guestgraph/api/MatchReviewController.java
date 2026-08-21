package io.guestgraph.api;

import io.guestgraph.auth.ActorResolver;
import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.Actor;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.persistence.MatchReviewQueryService;
import io.guestgraph.resolution.GraphMutationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Review queue (US4): list parked suspicious matches; confirm or reject exactly once. */
@RestController
@RequestMapping("/api/v1/match-reviews")
public class MatchReviewController {

  public record MatchReviewDto(
      UUID id,
      String status,
      UUID sourceRecordId,
      UUID candidateGuestId,
      Map<String, String> identifier,
      String reason,
      String matcherName,
      BigDecimal confidence,
      Instant createdAt,
      Instant decidedAt,
      ActorDto actor,
      UUID decisionEventId) {

    static MatchReviewDto of(MatchReview review) {
      return of(review, null);
    }

    /** FR-015: a decision response names the actor that made it, not only explain does. */
    static MatchReviewDto of(MatchReview review, Actor actor) {
      return new MatchReviewDto(
          review.id(),
          review.status().name(),
          review.sourceRecordId(),
          review.candidateGuestId(),
          Map.of("type", review.identifierType(), "value", review.identifierValue()),
          review.reason(),
          review.matcherName(),
          review.confidence(),
          review.createdAt(),
          review.decidedAt(),
          ActorDto.of(actor),
          review.decisionEventId());
    }
  }

  public record DecisionRequest(String decision) {}

  private final MatchReviewQueryService queryService;
  private final GraphMutationService mutationService;

  public MatchReviewController(
      MatchReviewQueryService queryService, GraphMutationService mutationService) {
    this.queryService = queryService;
    this.mutationService = mutationService;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(value = "status", defaultValue = "PENDING") ReviewStatus status,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (limit < 1 || limit > 200) {
      throw new BadRequestException("limit must be 1..200");
    }
    MatchReviewQueryService.ReviewPage page =
        queryService.list(TenantContext.tenantId(), status, limit, cursor);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reviews", page.reviews().stream().map(MatchReviewDto::of).toList());
    body.put("total", page.total());
    body.put("nextCursor", page.nextCursor());
    return body;
  }

  @PostMapping("/{reviewId}")
  public MatchReviewDto decide(@PathVariable UUID reviewId, @RequestBody DecisionRequest request) {
    boolean confirm =
        switch (request.decision() == null ? "" : request.decision()) {
          case "CONFIRM" -> true;
          case "REJECT" -> false;
          default -> throw new BadRequestException("Field 'decision' must be CONFIRM or REJECT");
        };
    Actor actor = ActorResolver.actor();
    return MatchReviewDto.of(
        mutationService.decideReview(TenantContext.tenantId(), reviewId, confirm, actor), actor);
  }
}
