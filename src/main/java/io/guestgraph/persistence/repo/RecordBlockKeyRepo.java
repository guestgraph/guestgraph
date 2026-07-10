package io.guestgraph.persistence.repo;

import io.guestgraph.domain.BlockKeyType;
import io.guestgraph.persistence.entity.RecordBlockKeyEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RecordBlockKeyRepo extends Repository<RecordBlockKeyEntity, UUID> {

  /** Fuzzy candidates: guests whose linked records share this blocking key. */
  @Query(
      """
            select distinct l.guestId from ResolutionLinkEntity l
            where l.tenantId = :tenantId and l.sourceRecordId in
                (select bk.sourceRecordId from RecordBlockKeyEntity bk
                 where bk.tenantId = :tenantId and bk.type = :type and bk.valueNormalized = :value)
            """)
  List<UUID> guestIdsByBlockKey(
      @Param("tenantId") UUID tenantId,
      @Param("type") BlockKeyType type,
      @Param("value") String value);

  @Query(
      """
            select bk from RecordBlockKeyEntity bk
            where bk.tenantId = :tenantId and bk.sourceRecordId = :recordId
            """)
  List<RecordBlockKeyEntity> keysOfRecord(
      @Param("tenantId") UUID tenantId, @Param("recordId") UUID recordId);
}
