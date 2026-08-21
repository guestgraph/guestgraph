package io.guestgraph.persistence;

import io.guestgraph.domain.Credential;
import io.guestgraph.persistence.repo.TenantRepo;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Domain-facing tenant lookups (auth filter runs outside any transaction). */
@Component
public class TenantStore {

  private final TenantRepo tenantRepo;

  public TenantStore(TenantRepo tenantRepo) {
    this.tenantRepo = tenantRepo;
  }

  public Optional<Credential> findCredentialByApiKeyHash(String keyHash) {
    return tenantRepo.findCredentialByApiKeyHash(keyHash);
  }
}
