package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US2 acceptance scenarios 1–5 over the API. */
class GuestQueryApiTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystems() {
    register(TENANT_A_KEY, "opera-pms");
    register(TENANT_A_KEY, "loyalty-db");
  }

  @Test
  void goldenProfileCarriesMostRecentNonNullValuePerField() {
    ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"opera-pms","externalKey":"r-1","recordTimestamp":"2026-01-01T00:00:00Z",
             "payload":{"email":"anna@example.com","firstName":"Anna","roomPreference":"non-smoking"}}
            """);
    ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"loyalty-db","externalKey":"l-1","recordTimestamp":"2026-03-01T00:00:00Z",
             "payload":{"email":"anna@example.com","lastName":"Muster"}}
            """);
    String guestId =
        ingest(
                TENANT_A_KEY,
                """
                    {"sourceSystem":"opera-pms","externalKey":"r-2","recordTimestamp":"2026-06-01T00:00:00Z",
                     "payload":{"email":"anna@example.com","firstName":"Anne"}}
                    """)
            .get("guestId")
            .asString();

    JsonNode guest = get(TENANT_A_KEY, "/api/v1/guests/" + guestId);

    // Most recent non-null per field: firstName from June, lastName from March, preference from
    // January.
    assertThat(guest.get("profile").get("firstName").asString()).isEqualTo("Anne");
    assertThat(guest.get("profile").get("lastName").asString()).isEqualTo("Muster");
    assertThat(guest.get("profile").get("roomPreference").asString()).isEqualTo("non-smoking");
    assertThat(guest.get("recordCount").asInt()).isEqualTo(3);
    assertThat(guest.get("identifiers").size()).isEqualTo(1);
  }

  @Test
  void sourceRecordsAreReturnedExactlyAsReceived() {
    String guestId =
        ingest(
                TENANT_A_KEY,
                """
                    {"sourceSystem":"opera-pms","externalKey":"r-1",
                     "payload":{"email":"Anna.Muster@Example.com","custom":{"nested":[1,2,3]},"note":"VIP  guest"}}
                    """)
            .get("guestId")
            .asString();

    JsonNode records = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/records").get("records");

    assertThat(records.size()).isEqualTo(1);
    JsonNode payload = records.get(0).get("payload");
    // Verbatim original: unnormalized email casing, nested structures, odd whitespace all intact.
    assertThat(payload.get("email").asString()).isEqualTo("Anna.Muster@Example.com");
    assertThat(payload.get("custom").get("nested").get(2).asInt()).isEqualTo(3);
    assertThat(payload.get("note").asString()).isEqualTo("VIP  guest");
    assertThat(records.get(0).get("sourceSystem").asString()).isEqualTo("opera-pms");
  }

  @Test
  void lookupNormalizesTheQueryValue() {
    String guestId =
        ingest(
                TENANT_A_KEY,
                """
                    {"sourceSystem":"opera-pms","externalKey":"r-1",
                     "payload":{"email":"anna@example.com","phone":"+41791234567"}}
                    """)
            .get("guestId")
            .asString();

    JsonNode byEmail = lookup(" ANNA@Example.COM ");
    JsonNode byPhone = lookup("+41 79 123 45 67");

    assertThat(byEmail.size()).isEqualTo(1);
    assertThat(byEmail.get(0).get("id").asString()).isEqualTo(guestId);
    assertThat(byPhone.size()).isEqualTo(1);
    assertThat(byPhone.get(0).get("id").asString()).isEqualTo(guestId);
  }

  @Test
  void lookupMissReturnsEmptyListNotError() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/guests?identifier=nobody@example.com")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(response.getBody()).get("guests").size()).isZero();
  }

  @Test
  void typeFilterRestrictsLookup() {
    ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"opera-pms","externalKey":"r-1","payload":{"loyaltyId":"GOLD-7"}}
            """);

    JsonNode asLoyalty =
        get(TENANT_A_KEY, "/api/v1/guests?identifier=GOLD-7&type=LOYALTY_ID").get("guests");
    JsonNode asEmail =
        get(TENANT_A_KEY, "/api/v1/guests?identifier=GOLD-7&type=EMAIL").get("guests");

    assertThat(asLoyalty.size()).isEqualTo(1);
    assertThat(asEmail.size()).isZero();
  }

  @Test
  void unknownGuestIdIsNotFoundProblem() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/guests/00000000-0000-0000-0000-000000000099")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
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
    ResponseEntity<String> response = api(apiKey).get().uri(uri).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody());
  }

  private JsonNode lookup(String rawIdentifier) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri(b -> b.path("/api/v1/guests").queryParam("identifier", "{v}").build(rawIdentifier))
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody()).get("guests");
  }
}
