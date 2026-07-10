package io.guestgraph.persistence;

import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.persistence.entity.IdentifierQualityRuleEntity;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.IdentifierQualityRuleRepo;
import io.guestgraph.resolution.BuiltinQualityRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD side of identifier quality rules (US3); built-ins listed read-only. */
@Service
public class IdentifierRuleService {

  @PersistenceContext private EntityManager em;

  private final IdentifierQualityRuleRepo repo;
  private final DomainMappers mappers;

  public IdentifierRuleService(IdentifierQualityRuleRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  @Transactional(readOnly = true)
  public List<IdentifierQualityRule> list(UUID tenantId) {
    List<IdentifierQualityRule> all = new ArrayList<>(BuiltinQualityRules.RULES);
    all.addAll(mappers.toDomainQualityRules(repo.listByTenant(tenantId)));
    return all;
  }

  @Transactional
  public IdentifierQualityRule add(IdentifierQualityRule rule) {
    IdentifierQualityRuleEntity entity =
        new IdentifierQualityRuleEntity(
            UUID.randomUUID(),
            rule.tenantId(),
            rule.identifierType(),
            rule.matchKind(),
            rule.valueNormalized(),
            rule.rule(),
            rule.note(),
            Instant.now());
    try {
      em.persist(entity);
      em.flush(); // surface the unique violation here, not at commit
    } catch (jakarta.persistence.PersistenceException e) {
      // No Spring exception translation on @Service beans — map the unique violation.
      if (e instanceof ConstraintViolationException) {
        throw new DuplicateKeyException("equivalent rule exists", e);
      }
      throw e;
    }
    return mappers.toDomain(entity);
  }

  /**
   * @return true when deleted; false when absent in this tenant
   */
  @Transactional
  public boolean delete(UUID tenantId, UUID ruleId) {
    return repo.deleteRule(tenantId, ruleId) > 0;
  }
}
