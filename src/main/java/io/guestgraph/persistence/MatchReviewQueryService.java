package io.guestgraph.persistence;

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

  public record ReviewPage(List<MatchReview> reviews, int total) {}

  private final MatchReviewRepo repo;
  private final DomainMappers mappers;

  public MatchReviewQueryService(MatchReviewRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  public ReviewPage list(UUID tenantId, ReviewStatus status, int limit, int offset) {
    List<MatchReview> reviews =
        mappers.toDomainReviews(repo.list(tenantId, status.name(), limit, offset));
    return new ReviewPage(reviews, repo.count(tenantId, status));
  }
}
