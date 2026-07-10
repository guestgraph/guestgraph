package io.guestgraph.persistence;

import io.guestgraph.domain.SourceSystem;
import io.guestgraph.persistence.entity.SourceSystemEntity;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.SourceSystemRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository // @Repository (not @Component) for Hibernate→Spring exception translation
public class SourceSystemStore {

  @PersistenceContext private EntityManager em;

  private final SourceSystemRepo repo;
  private final DomainMappers mappers;

  public SourceSystemStore(SourceSystemRepo repo, DomainMappers mappers) {
    this.repo = repo;
    this.mappers = mappers;
  }

  /**
   * @throws DataIntegrityViolationException when the code is already registered in the tenant
   */
  @Transactional
  public SourceSystem insert(UUID tenantId, String code, String name) {
    SourceSystemEntity entity =
        new SourceSystemEntity(UUID.randomUUID(), tenantId, code, name, Instant.now());
    em.persist(entity);
    em.flush(); // surface the unique violation here, not at commit
    return mappers.toDomain(entity);
  }

  public Optional<SourceSystem> findByCode(UUID tenantId, String code) {
    return repo.findByCode(tenantId, code).map(mappers::toDomain);
  }
}
