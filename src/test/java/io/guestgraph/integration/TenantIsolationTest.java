package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * SC-006 — the project's #1 invariant: no operation ever returns or modifies another tenant's data.
 * Cross-tenant ids behave exactly like nonexistent ones.
 */
class TenantIsolationTest extends PostgresIntegrationTest {

  private String tenantAGuestId;

  @BeforeEach
  void seedGuestInTenantA() {
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"opera-pms\",\"name\":\"Opera\"}")
        .retrieve()
        .toEntity(String.class);
    ResponseEntity<String> ingest =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                    {"sourceSystem":"opera-pms","externalKey":"iso-1",
                     "payload":{"email":"isolated@example.com","firstName":"Anna"}}
                    """)
            .retrieve()
            .toEntity(String.class);
    tenantAGuestId = json(ingest.getBody()).get("results").get(0).get("guestId").asString();
  }

  @Test
  void guestFetchAcrossTenantsLooksNonexistent() {
    ResponseEntity<String> response =
        api(TENANT_B_KEY)
            .get()
            .uri("/api/v1/guests/" + tenantAGuestId)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void recordsFetchAcrossTenantsLooksNonexistent() {
    ResponseEntity<String> response =
        api(TENANT_B_KEY)
            .get()
            .uri("/api/v1/guests/" + tenantAGuestId + "/records")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void lookupAcrossTenantsIsEmpty() {
    ResponseEntity<String> response =
        api(TENANT_B_KEY)
            .get()
            .uri("/api/v1/guests?identifier=isolated@example.com")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(response.getBody()).get("guests").size()).isZero();
  }

  @Test
  void sameTenantSeesItsOwnGuest() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/guests/" + tenantAGuestId)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(response.getBody()).get("profile").get("firstName").asString())
        .isEqualTo("Anna");
  }
}
