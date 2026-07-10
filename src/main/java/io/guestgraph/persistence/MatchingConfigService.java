package io.guestgraph.persistence;

import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.persistence.repo.TenantRepo;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read/write side of the per-tenant matching thresholds (US4). */
@Service
public class MatchingConfigService {

  private final TenantRepo tenantRepo;

  public MatchingConfigService(TenantRepo tenantRepo) {
    this.tenantRepo = tenantRepo;
  }

  @Transactional(readOnly = true)
  public MatchingConfig get(UUID tenantId) {
    return tenantRepo
        .matchingConfig(tenantId)
        .orElseThrow(() -> new IllegalStateException("Unknown tenant " + tenantId));
  }

  /** Transactional: an invalid combination leaves the previous configuration in effect. */
  @Transactional
  public MatchingConfig update(UUID tenantId, MatchingConfig config) {
    tenantRepo.updateMatchingConfig(
        tenantId, config.autoMergeThreshold(), config.reviewFloor(), config.reviewThreshold());
    return get(tenantId);
  }
}
