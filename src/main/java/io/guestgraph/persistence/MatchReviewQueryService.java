package io.guestgraph.persistence;

import io.guestgraph.api.Cursor;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.MatchReviewRepo;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the review queue (US4): list pending (or decided) reviews, oldest first. */
@Service
@Transactional(readOnly = true)
public class MatchReviewQueryService {

  public record ReviewPage(List<MatchReview> reviews, int total, String nextCursor) {}

  private final MatchReviewRepo repo;
  private final DomainMappers mappers;

  public MatchReviewQueryService(MatchReviewRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  public ReviewPage list(UUID tenantId, ReviewStatus status, int limit, String cursor) {
    Cursor.Key after = cursor == null ? null : Cursor.decode(cursor);
    List<MatchReview> reviews =
        mappers.toDomainReviews(
            repo.list(
                tenantId,
                status.name(),
                after == null ? null : after.time(),
                after == null ? null : after.uuid(),
                limit + 1));
    return page(reviews, limit, repo.count(tenantId, status));
  }

  private ReviewPage page(List<MatchReview> fetched, int limit, int total) {
    boolean more = fetched.size() > limit;
    List<MatchReview> items = more ? fetched.subList(0, limit) : fetched;
    String next =
        more ? Cursor.encode(items.getLast().createdAt(), items.getLast().id().toString()) : null;
    return new ReviewPage(List.copyOf(items), total, next);
  }
}
