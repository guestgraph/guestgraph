package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.IdentifierQualityRuleEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface IdentifierQualityRuleRepo extends Repository<IdentifierQualityRuleEntity, UUID> {

  @Query(
      """
            select r from IdentifierQualityRuleEntity r
            where r.tenantId = :tenantId
            order by r.createdAt, r.id
            """)
  List<IdentifierQualityRuleEntity> listByTenant(@Param("tenantId") UUID tenantId);

  @Query("select r from IdentifierQualityRuleEntity r where r.tenantId = :tenantId and r.id = :id")
  Optional<IdentifierQualityRuleEntity> findRule(
      @Param("tenantId") UUID tenantId, @Param("id") UUID id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from IdentifierQualityRuleEntity r where r.tenantId = :tenantId and r.id = :id")
  int deleteRule(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
