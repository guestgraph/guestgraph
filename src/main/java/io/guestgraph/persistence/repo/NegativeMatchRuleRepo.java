package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.NegativeMatchRuleEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface NegativeMatchRuleRepo extends Repository<NegativeMatchRuleEntity, UUID> {

  /** Does any rule span the two record sets? Pure membership test (R2-5). */
  @Query(
      """
            select count(r) > 0 from NegativeMatchRuleEntity r
            where r.tenantId = :tenantId
              and ((r.recordA in :recordsA and r.recordB in :recordsB)
                or (r.recordA in :recordsB and r.recordB in :recordsA))
            """)
  boolean existsBetween(
      @Param("tenantId") UUID tenantId,
      @Param("recordsA") Collection<UUID> recordsA,
      @Param("recordsB") Collection<UUID> recordsB);

  /** Confirm across a rule lifts every spanning rule (FR-011). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            delete from NegativeMatchRuleEntity r
            where r.tenantId = :tenantId
              and ((r.recordA in :recordsA and r.recordB in :recordsB)
                or (r.recordA in :recordsB and r.recordB in :recordsA))
            """)
  int liftBetween(
      @Param("tenantId") UUID tenantId,
      @Param("recordsA") Collection<UUID> recordsA,
      @Param("recordsB") Collection<UUID> recordsB);

  @Query("select r from NegativeMatchRuleEntity r where r.tenantId = :tenantId and r.id = :id")
  Optional<NegativeMatchRuleEntity> findRule(
      @Param("tenantId") UUID tenantId, @Param("id") UUID id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from NegativeMatchRuleEntity r where r.tenantId = :tenantId and r.id = :id")
  int deleteRule(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /** Native for exact LIMIT/OFFSET paging, as the review queue does. */
  @Query(
      nativeQuery = true,
      value =
          """
            SELECT * FROM negative_match_rule
            WHERE tenant_id = :tenantId
            ORDER BY created_at DESC, id
            LIMIT :limit OFFSET :offset
            """)
  List<NegativeMatchRuleEntity> list(
      @Param("tenantId") UUID tenantId, @Param("limit") int limit, @Param("offset") int offset);

  @Query("select count(r) from NegativeMatchRuleEntity r where r.tenantId = :tenantId")
  int count(@Param("tenantId") UUID tenantId);
}
