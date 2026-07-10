package io.guestgraph.persistence.repo;

import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.persistence.entity.TenantEntity;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TenantRepo extends Repository<TenantEntity, UUID> {

  @TenantAgnostic(
      "resolves which tenant an API key belongs to — the tenant is the output, not an input")
  @Query(
      """
            select t from TenantEntity t, ApiKeyEntity k
            where k.tenantId = t.id and k.keyHash = :keyHash and k.revokedAt is null
            """)
  Optional<TenantEntity> findByApiKeyHash(@Param("keyHash") String keyHash);

  @Query("select t.reviewThreshold from TenantEntity t where t.id = :tenantId")
  Optional<Integer> reviewThreshold(@Param("tenantId") UUID tenantId);

  @Query(
      """
            select new io.guestgraph.domain.MatchingConfig(
                t.autoMergeThreshold, t.reviewFloor, t.reviewThreshold)
            from TenantEntity t where t.id = :tenantId
            """)
  Optional<MatchingConfig> matchingConfig(@Param("tenantId") UUID tenantId);

  /**
   * Native: TenantEntity is @Immutable — config writes bypass the entity model (like GuestRepo).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            UPDATE tenant SET auto_merge_threshold = :auto, review_floor = :floor, review_threshold = :sharing
            WHERE id = :tenantId
            """)
  int updateMatchingConfig(
      @Param("tenantId") UUID tenantId,
      @Param("auto") BigDecimal auto,
      @Param("floor") BigDecimal floor,
      @Param("sharing") int sharing);
}
