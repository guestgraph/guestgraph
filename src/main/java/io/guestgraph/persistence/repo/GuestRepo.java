package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.GuestEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface GuestRepo extends Repository<GuestEntity, UUID> {

  /** Native: jsonb cast — the sanctioned explicit-SQL corner (research R1). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            UPDATE guest SET profile = CAST(:profileJson AS jsonb), updated_at = now()
            WHERE tenant_id = :tenantId AND id = :guestId
            """)
  int updateProfile(
      @Param("tenantId") UUID tenantId,
      @Param("guestId") UUID guestId,
      @Param("profileJson") String profileJson);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from GuestEntity g where g.tenantId = :tenantId and g.id = :guestId")
  int deleteGuest(@Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId);
}
