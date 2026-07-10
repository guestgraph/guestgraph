package io.guestgraph.resolution;

import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary around the pure graph operations: unmerge and review decisions mutate the
 * graph, so they run inside a transaction under the per-tenant lock (FR-011).
 */
@Service
public class GraphMutationService {

  private final UnmergeOperation unmergeOperation;
  private final ExplainOperation explainOperation;
  private final ReviewDecisionOperation reviewDecisionOperation;
  private final TenantLock tenantLock;

  public GraphMutationService(
      UnmergeOperation unmergeOperation,
      ExplainOperation explainOperation,
      ReviewDecisionOperation reviewDecisionOperation,
      TenantLock tenantLock) {
    this.unmergeOperation = unmergeOperation;
    this.explainOperation = explainOperation;
    this.reviewDecisionOperation = reviewDecisionOperation;
    this.tenantLock = tenantLock;
  }

  @Transactional
  public MatchReview decideReview(UUID tenantId, UUID reviewId, boolean confirm) {
    tenantLock.acquire(tenantId);
    return reviewDecisionOperation.decide(tenantId, reviewId, confirm);
  }

  @Transactional
  public UnmergeOperation.UnmergeResult unmerge(
      UUID tenantId, UUID guestId, List<UUID> sourceRecordIds) {
    tenantLock.acquire(tenantId);
    return unmergeOperation.unmerge(tenantId, guestId, sourceRecordIds);
  }

  @Transactional(readOnly = true)
  public List<MergeEvent> explain(UUID tenantId, UUID guestId) {
    return explainOperation.explain(tenantId, guestId);
  }
}
