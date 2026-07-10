package io.guestgraph.persistence;

import io.guestgraph.domain.Tenant;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.TenantRepo;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Domain-facing tenant lookups (auth filter runs outside any transaction). */
@Component
public class TenantStore {

  private final TenantRepo tenantRepo;
  private final DomainMappers mappers;

  public TenantStore(TenantRepo tenantRepo, DomainMappers mappers) {
    this.tenantRepo = tenantRepo;
    this.mappers = mappers;
  }

  public Optional<Tenant> findByApiKeyHash(String keyHash) {
    return tenantRepo.findByApiKeyHash(keyHash).map(mappers::toDomain);
  }
}
