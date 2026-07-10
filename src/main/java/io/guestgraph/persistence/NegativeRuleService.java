package io.guestgraph.persistence;

import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.NegativeMatchRuleRepo;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read/delete side of do-not-merge rules (US2): listing and steward lifting. */
@Service
public class NegativeRuleService {

  public record RulePage(List<NegativeMatchRule> rules, int total) {}

  private final NegativeMatchRuleRepo repo;
  private final DomainMappers mappers;

  public NegativeRuleService(NegativeMatchRuleRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  @Transactional(readOnly = true)
  public RulePage list(UUID tenantId, int limit, int offset) {
    return new RulePage(
        mappers.toDomainNegativeRules(repo.list(tenantId, limit, offset)), repo.count(tenantId));
  }

  /**
   * @return true when a rule was lifted; false when it did not exist in this tenant
   */
  @Transactional
  public boolean delete(UUID tenantId, UUID ruleId) {
    return repo.deleteRule(tenantId, ruleId) > 0;
  }
}
