package io.guestgraph.integration;

import io.guestgraph.auth.Sha256;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared Testcontainers harness: one PostgreSQL container for the whole test run, Flyway-migrated
 * schema, two seeded tenants (isolation testing), and an API-key-authenticated HTTP client that
 * never throws on 4xx/5xx so tests can assert error responses.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
public abstract class PostgresIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  protected static final UUID TENANT_A = UUID.nameUUIDFromBytes("tenant:acme".getBytes());
  protected static final UUID TENANT_B = UUID.nameUUIDFromBytes("tenant:globex".getBytes());
  protected static final String TENANT_A_KEY = "acme-key";
  protected static final String TENANT_B_KEY = "globex-key";

  @LocalServerPort protected int port;

  @Autowired protected JdbcClient jdbc;

  @Autowired protected ObjectMapper mapper;

  @BeforeEach
  void resetDatabase() {
    jdbc.sql(
            """
                TRUNCATE match_review, resolution_link, identifier, merge_event,
                         record_identifier, source_record, guest, source_system
                """)
        .update();
    seedTenant(TENANT_A, "acme", TENANT_A_KEY);
    seedTenant(TENANT_B, "globex", TENANT_B_KEY);
    jdbc.sql("UPDATE tenant SET review_threshold = 10").update();
  }

  private void seedTenant(UUID tenantId, String slug, String apiKey) {
    jdbc.sql(
            """
                        INSERT INTO tenant (id, slug, name) VALUES (:id, :slug, :slug)
                        ON CONFLICT (slug) DO NOTHING
                        """)
        .param("id", tenantId)
        .param("slug", slug)
        .update();
    jdbc.sql(
            """
                        INSERT INTO api_key (id, tenant_id, key_hash, label)
                        VALUES (:id, :tenantId, :keyHash, 'test')
                        ON CONFLICT (key_hash) DO NOTHING
                        """)
        .param("id", UUID.nameUUIDFromBytes(("key:" + slug).getBytes()))
        .param("tenantId", tenantId)
        .param("keyHash", Sha256.hex(apiKey))
        .update();
  }

  /** Client that sends the given API key and returns 4xx/5xx responses instead of throwing. */
  protected RestClient api(String apiKey) {
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> {});
    if (apiKey != null) {
      builder.defaultHeader("X-API-Key", apiKey);
    }
    return builder.build();
  }

  protected JsonNode json(String body) {
    return mapper.readTree(body);
  }

  protected void setReviewThreshold(UUID tenantId, int threshold) {
    jdbc.sql("UPDATE tenant SET review_threshold = :threshold WHERE id = :tenantId")
        .param("threshold", threshold)
        .param("tenantId", tenantId)
        .update();
  }
}
