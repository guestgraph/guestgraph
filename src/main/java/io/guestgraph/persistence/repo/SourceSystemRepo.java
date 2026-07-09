package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.SourceSystemEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SourceSystemRepo extends Repository<SourceSystemEntity, UUID> {

    @Query("select s from SourceSystemEntity s where s.tenantId = :tenantId and s.code = :code")
    Optional<SourceSystemEntity> findByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);
}
