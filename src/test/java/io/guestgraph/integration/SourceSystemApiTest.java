package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class SourceSystemApiTest extends PostgresIntegrationTest {

  @Test
  void registersSourceSystem() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/source-systems")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                        {"code":"opera-pms","name":"Opera PMS"}
                        """)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode body = json(response.getBody());
    assertThat(body.get("code").asString()).isEqualTo("opera-pms");
    assertThat(body.get("id").asString()).isNotBlank();
  }

  @Test
  void duplicateCodeInSameTenantConflicts() {
    register(TENANT_A_KEY, "opera-pms");

    ResponseEntity<String> response = register(TENANT_A_KEY, "opera-pms");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    assertThat(json(response.getBody()).get("detail").asString()).contains("opera-pms");
  }

  @Test
  void sameCodeInDifferentTenantsIsAllowed() {
    assertThat(register(TENANT_A_KEY, "opera-pms").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(register(TENANT_B_KEY, "opera-pms").getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void invalidCodeIsRejectedAsProblemDetails() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/source-systems")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                        {"code":"Not Valid!","name":"x"}
                        """)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
  }

  private ResponseEntity<String> register(String apiKey, String code) {
    return api(apiKey)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}")
        .retrieve()
        .toEntity(String.class);
  }
}
