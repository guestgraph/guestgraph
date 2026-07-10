package io.guestgraph.config;

import io.guestgraph.auth.Sha256;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seeds tenant "demo" with API key "demo-key" for local development only. Never active outside the
 * "local" profile — production tenants and keys are provisioned by the operator.
 */
@Component
@Profile("local")
public class LocalDevSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(LocalDevSeeder.class);

  private final JdbcClient jdbc;

  public LocalDevSeeder(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(String... args) {
    UUID tenantId = UUID.nameUUIDFromBytes("tenant:demo".getBytes());
    jdbc.sql(
            """
                INSERT INTO tenant (id, slug, name, review_threshold, created_at)
                VALUES (:id, 'demo', 'Demo Tenant', 10, now())
                ON CONFLICT (slug) DO NOTHING
                """)
        .param("id", tenantId)
        .update();
    jdbc.sql(
            """
                INSERT INTO api_key (id, tenant_id, key_hash, label, created_at)
                VALUES (:id, :tenantId, :keyHash, 'local-dev', now())
                ON CONFLICT (key_hash) DO NOTHING
                """)
        .param("id", UUID.nameUUIDFromBytes("api-key:demo".getBytes()))
        .param("tenantId", tenantId)
        .param("keyHash", Sha256.hex("demo-key"))
        .update();
    log.info("Local dev seed ready: tenant 'demo', API key 'demo-key'");
  }
}
