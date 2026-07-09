package io.guestgraph.resolution;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Serializes all graph mutations within a tenant (Constitution IV, FR-011) via a
 * transaction-scoped Postgres advisory lock — released automatically on
 * commit/rollback. Tenants never block each other (research R3).
 */
@Component
public class TenantLock {

    private final JdbcClient jdbc;

    public TenantLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside an active transaction, before any graph mutation. */
    public void acquire(UUID tenantId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantLock.acquire requires an active transaction");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:tenantId))")
                .param("tenantId", tenantId.toString())
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
    }
}
