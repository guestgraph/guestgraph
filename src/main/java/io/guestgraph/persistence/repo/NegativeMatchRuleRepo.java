package io.guestgraph.persistence.repo;

import io.guestgraph.domain.ActorType;
import io.guestgraph.persistence.entity.NegativeMatchRuleEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface NegativeMatchRuleRepo extends Repository<NegativeMatchRuleEntity, UUID> {

  /** Does any ACTIVE rule span the two record sets? Pure membership test (R2-5). */
  @Query(
      """
            select count(r) > 0 from NegativeMatchRuleEntity r
            where r.tenantId = :tenantId and r.liftedAt is null
              and ((r.recordA in :recordsA and r.recordB in :recordsB)
                or (r.recordA in :recordsB and r.recordB in :recordsA))
            """)
  boolean existsBetween(
      @Param("tenantId") UUID tenantId,
      @Param("recordsA") Collection<UUID> recordsA,
      @Param("recordsB") Collection<UUID> recordsB);

  /**
   * Confirm across a rule lifts every spanning rule (FR-011). A stamp, not a delete: the override
   * of a steward's split stays on the record with the actor who made it (FR-016a).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update NegativeMatchRuleEntity r
               set r.liftedAt = :liftedAt,
                   r.liftedActorType = :actorType,
                   r.liftedActorId = :actorId
            where r.tenantId = :tenantId and r.liftedAt is null
              and ((r.recordA in :recordsA and r.recordB in :recordsB)
                or (r.recordA in :recordsB and r.recordB in :recordsA))
            """)
  int liftBetween(
      @Param("tenantId") UUID tenantId,
      @Param("recordsA") Collection<UUID> recordsA,
      @Param("recordsB") Collection<UUID> recordsB,
      @Param("liftedAt") Instant liftedAt,
      @Param("actorType") ActorType actorType,
      @Param("actorId") String actorId);

  @Query("select r from NegativeMatchRuleEntity r where r.tenantId = :tenantId and r.id = :id")
  Optional<NegativeMatchRuleEntity> findRule(
      @Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /** Steward lifting one rule by id — the same stamp, never a delete. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update NegativeMatchRuleEntity r
               set r.liftedAt = :liftedAt,
                   r.liftedActorType = :actorType,
                   r.liftedActorId = :actorId
            where r.tenantId = :tenantId and r.id = :id and r.liftedAt is null
            """)
  int liftRule(
      @Param("tenantId") UUID tenantId,
      @Param("id") UUID id,
      @Param("liftedAt") Instant liftedAt,
      @Param("actorType") ActorType actorType,
      @Param("actorId") String actorId);

  /** Keyset paging on (created_at, id) descending — the same cursor idiom as every paged read. */
  @Query(
      nativeQuery = true,
      value =
          """
            SELECT * FROM negative_match_rule
            WHERE tenant_id = :tenantId AND (:includeLifted OR lifted_at IS NULL)
              AND (CAST(:afterCreatedAt AS timestamptz) IS NULL
                   OR (created_at, id) < (CAST(:afterCreatedAt AS timestamptz), CAST(:afterId AS uuid)))
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
  List<NegativeMatchRuleEntity> list(
      @Param("tenantId") UUID tenantId,
      @Param("includeLifted") boolean includeLifted,
      @Param("afterCreatedAt") Instant afterCreatedAt,
      @Param("afterId") UUID afterId,
      @Param("limit") int limit);

  @Query(
      """
            select count(r) from NegativeMatchRuleEntity r
            where r.tenantId = :tenantId and (:includeLifted = true or r.liftedAt is null)
            """)
  int count(@Param("tenantId") UUID tenantId, @Param("includeLifted") boolean includeLifted);
}
