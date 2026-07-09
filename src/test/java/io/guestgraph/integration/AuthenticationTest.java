package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AuthenticationTest extends PostgresIntegrationTest {

  @Test
  void missingApiKeyIsRejectedWithProblemDetails() {
    ResponseEntity<String> response =
        api(null)
            .get()
            .uri("/api/v1/guests/00000000-0000-0000-0000-000000000000")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    JsonNode problem = json(response.getBody());
    assertThat(problem.get("status").asInt()).isEqualTo(401);
    assertThat(problem.get("detail").asString()).contains("X-API-Key");
  }

  @Test
  void unknownApiKeyIsRejected() {
    ResponseEntity<String> response =
        api("not-a-real-key")
            .get()
            .uri("/api/v1/guests/00000000-0000-0000-0000-000000000000")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(json(response.getBody()).get("detail").asString()).contains("Unknown or revoked");
  }

  @Test
  void validApiKeyPassesAuthentication() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY).get().uri("/api/v1/no-such-endpoint").retrieve().toEntity(String.class);

    // Auth passed: the request reached routing (404), it was not rejected at the filter (401).
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
