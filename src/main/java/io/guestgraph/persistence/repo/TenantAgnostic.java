package io.guestgraph.persistence.repo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The explicit allowlist for the ArchUnit tenant-scoping rule: every repository method must take a
 * {@code tenantId} parameter unless it carries this annotation with a justification. Use sparingly
 * — a missing tenant predicate is a cross-tenant data leak.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TenantAgnostic {

  /** Why this query may run without a tenant predicate. */
  String value();
}
