package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US4 acceptance scenarios 1–4 over the API. */
class MatchingConfigApiTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystem() {
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"opera-pms\",\"name\":\"Opera\"}")
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void freshTenantShowsShippedDefaults() {
    JsonNode config = get();

    // US4-AS1: automation off, default floor and sharing threshold.
    assertThat(config.get("autoMergeThreshold").asDouble()).isEqualTo(1.0);
    assertThat(config.get("reviewFloor").asDouble()).isEqualTo(0.75);
    assertThat(config.get("reviewThreshold").asInt()).isEqualTo(10);
  }

  @Test
  void updatedBandsApplyToTheNextResolutionWithoutRestart() {
    put("{\"autoMergeThreshold\":0.85,\"reviewFloor\":0.75,\"reviewThreshold\":10}", HttpStatus.OK);
    ingest("c-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");

    // US4-AS2: the very next ingest routes per the new bands — an auto-merge happens.
    JsonNode second =
        ingest(
            "c-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Mueller\",\"birthdate\":\"1985-03-12\"}");

    assertThat(second.get("status").asString()).isEqualTo("ATTACHED");
  }

  @Test
  void invalidCombinationsAreRejectedAndPreviousConfigStays() {
    // US4-AS3: floor above threshold; out-of-range values.
    put(
        "{\"autoMergeThreshold\":0.8,\"reviewFloor\":0.95,\"reviewThreshold\":10}",
        HttpStatus.BAD_REQUEST);
    put(
        "{\"autoMergeThreshold\":1.5,\"reviewFloor\":0.5,\"reviewThreshold\":10}",
        HttpStatus.BAD_REQUEST);
    put(
        "{\"autoMergeThreshold\":0.9,\"reviewFloor\":0.5,\"reviewThreshold\":0}",
        HttpStatus.BAD_REQUEST);

    JsonNode config = get();
    assertThat(config.get("autoMergeThreshold").asDouble()).isEqualTo(1.0);
    assertThat(config.get("reviewFloor").asDouble()).isEqualTo(0.75);
  }

  @Test
  void configurationIsTenantIsolated() {
    put("{\"autoMergeThreshold\":0.85,\"reviewFloor\":0.6,\"reviewThreshold\":5}", HttpStatus.OK);

    // US4-AS4: tenant B still sees (and resolves with) its own defaults.
    ResponseEntity<String> tenantB =
        api(TENANT_B_KEY).get().uri("/api/v1/config/matching").retrieve().toEntity(String.class);
    JsonNode config = json(tenantB.getBody());
    assertThat(config.get("autoMergeThreshold").asDouble()).isEqualTo(1.0);
    assertThat(config.get("reviewThreshold").asInt()).isEqualTo(10);
  }

  private JsonNode get() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY).get().uri("/api/v1/config/matching").retrieve().toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody());
  }

  private void put(String body, HttpStatus expected) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .put()
            .uri("/api/v1/config/matching")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(expected);
  }

  private JsonNode ingest(String externalKey, String payload) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"sourceSystem\":\"opera-pms\",\"externalKey\":\""
                    + externalKey
                    + "\",\"payload\":"
                    + payload
                    + "}")
            .retrieve()
            .toEntity(String.class);
    return json(response.getBody()).get("results").get(0);
  }
}
