package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Spec US2 scenarios 8–9: the object's own view. The observation history belongs to the object, not
 * to a guest — after a reassignment it spans both, which is exactly what makes it the right place
 * to prove nothing was lost.
 */
class SourceObjectApiTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystem() {
    register(TENANT_A_KEY, "opera-pms");
  }

  @Test
  void rosterShowsWhoIsOnItNowAndHistoryKeepsEveryoneWhoEverWas() {
    String anna = ingestPrimary("R1", "2026-03-01T10:00:00Z", "anna@example.com");
    String bruno = ingestPrimary("R1", "2026-03-01T11:00:00Z", "bruno@example.com");

    JsonNode object = get(TENANT_A_KEY, "/api/v1/source-objects/opera-pms/reservation/R1");

    assertThat(object.get("currentVersion").asString()).startsWith("2026-03-01T11:00");
    assertThat(object.get("roster").size()).isEqualTo(1);
    assertThat(object.get("roster").get(0).get("guestId").asString()).isEqualTo(bruno);

    // Constitution II: the superseded observation is still there, in version order.
    JsonNode observations = object.get("observations");
    assertThat(observations.size()).isEqualTo(2);
    assertThat(observations.get(0).get("guestId").asString()).isEqualTo(anna);
    assertThat(observations.get(1).get("guestId").asString()).isEqualTo(bruno);
    assertThat(observations.get(0).get("objectVersion").asString())
        .isLessThan(observations.get(1).get("objectVersion").asString());
  }

  @Test
  void anUnknownObjectIs404() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/source-objects/opera-pms/reservation/nope")
            .retrieve()
            .onStatus(status -> true, (req, res) -> {})
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
  }

  @Test
  void objectsAreTenantScoped() {
    ingestPrimary("R1", "2026-03-01T10:00:00Z", "anna@example.com");
    register(TENANT_B_KEY, "opera-pms");

    ResponseEntity<String> response =
        api(TENANT_B_KEY)
            .get()
            .uri("/api/v1/source-objects/opera-pms/reservation/R1")
            .retrieve()
            .onStatus(status -> true, (req, res) -> {})
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private String ingestPrimary(String objectId, String version, String email) {
    return ingest(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"%s:%s",
                 "recordTimestamp":"%s",
                 "payload":{"email":"%s"},
                 "sourceObject":{"type":"reservation","id":"%s","role":"PRIMARY_GUEST",
                                 "version":"%s"}}
                """
                .formatted(objectId, version, version, email, objectId, version))
        .get("guestId")
        .asString();
  }

  private void register(String apiKey, String code) {
    api(apiKey)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private JsonNode ingest(String apiKey, String body) {
    ResponseEntity<String> response =
        api(apiKey)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody()).get("results").get(0);
  }

  private JsonNode get(String apiKey, String uri) {
    return json(api(apiKey).get().uri(uri).retrieve().toEntity(String.class).getBody());
  }
}
