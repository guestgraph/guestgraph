package io.guestgraph.auth;

import io.guestgraph.domain.Tenant;
import java.util.UUID;

/**
 * Holds the authenticated tenant for the current request. Requests run on a single (virtual)
 * thread, so a ThreadLocal is sufficient; {@link ApiKeyFilter} sets and clears it. Every downstream
 * operation takes its tenant scope from here — there is no code path without a tenant (Constitution
 * I).
 */
public final class TenantContext {

  private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  static void set(Tenant tenant) {
    CURRENT.set(tenant);
  }

  static void clear() {
    CURRENT.remove();
  }

  public static Tenant tenant() {
    Tenant tenant = CURRENT.get();
    if (tenant == null) {
      throw new IllegalStateException("No tenant bound to the current request");
    }
    return tenant;
  }

  public static UUID tenantId() {
    return tenant().id();
  }
}
