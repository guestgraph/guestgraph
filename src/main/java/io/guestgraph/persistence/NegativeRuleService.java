package io.guestgraph.persistence;

import io.guestgraph.api.Cursor;
import io.guestgraph.domain.Actor;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.NegativeMatchRuleRepo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read/lift side of do-not-merge rules (US2): listing and steward lifting (FR-016a). */
@Service
public class NegativeRuleService {

  public record RulePage(List<NegativeMatchRule> rules, int total, String nextCursor) {}

  private final NegativeMatchRuleRepo repo;
  private final DomainMappers mappers;

  public NegativeRuleService(NegativeMatchRuleRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  @Transactional(readOnly = true)
  /**
   * Active rules by default; lifted ones are readable on request, the same way the timeline hides
   * ended associations unless asked. A lifted rule is history, not a live constraint.
   */
  public RulePage list(UUID tenantId, boolean includeLifted, int limit, String cursor) {
    Cursor.Key after = cursor == null ? null : Cursor.decode(cursor);
    List<NegativeMatchRule> fetched =
        mappers.toDomainNegativeRules(
            repo.list(
                tenantId,
                includeLifted,
                after == null ? null : after.time(),
                after == null ? null : after.uuid(),
                limit + 1));
    boolean more = fetched.size() > limit;
    List<NegativeMatchRule> items = more ? fetched.subList(0, limit) : fetched;
    String next =
        more ? Cursor.encode(items.getLast().createdAt(), items.getLast().id().toString()) : null;
    return new RulePage(List.copyOf(items), repo.count(tenantId, includeLifted), next);
  }

  /**
   * @return true when an active rule was lifted; false when it does not exist in this tenant or was
   *     already lifted
   */
  @Transactional
  public boolean lift(UUID tenantId, UUID ruleId, Actor actor) {
    return repo.liftRule(tenantId, ruleId, Instant.now(), actor.type(), actor.id()) > 0;
  }
}
