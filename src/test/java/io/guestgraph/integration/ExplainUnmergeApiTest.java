package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US3 acceptance scenarios 1–4 over the API. */
class ExplainUnmergeApiTest extends PostgresIntegrationTest {

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
  void explainReturnsTheFullDecisionChain() {
    ingest("a", "{\"email\":\"x@example.com\"}");
    ingest("b", "{\"phone\":\"+41791112233\"}");
    String survivor =
        ingest("c", "{\"email\":\"x@example.com\",\"phone\":\"+41791112233\"}")
            .get("guestId")
            .asString();

    JsonNode explain = get("/api/v1/guests/" + survivor + "/explain");

    assertThat(explain.get("guestId").asString()).isEqualTo(survivor);
    JsonNode events = explain.get("events");
    // Two CREATEs (one on an absorbed guest) + the MERGE, each fully auditable.
    assertThat(events.size()).isEqualTo(3);
    assertThat(events.get(events.size() - 1).get("kind").asString()).isEqualTo("MERGE");
    for (JsonNode event : events) {
      assertThat(event.get("matcherName").asString()).isNotBlank();
      assertThat(event.get("confidence").asDouble()).isBetween(0.0, 1.0);
      assertThat(event.get("createdAt").asString()).isNotBlank();
    }
    // The merge evidence names the identifiers that connected the records (FR-009/I2).
    JsonNode merge = events.get(events.size() - 1);
    assertThat(merge.get("evidence").get("matches").size()).isEqualTo(2);
  }

  @Test
  void unmergeSplitsReplaysAndPreservesOriginals() {
    ingest("r1", "{\"email\":\"shared@example.com\",\"firstName\":\"Anna\"}");
    ingest("r2", "{\"email\":\"shared@example.com\"}");
    JsonNode third = ingest("r3", "{\"email\":\"shared@example.com\",\"note\":\"wrong person\"}");
    String guestId = third.get("guestId").asString();
    String recordId = third.get("sourceRecordId").asString();

    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/guests/" + guestId + "/unmerge")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"sourceRecordIds\":[\"" + recordId + "\"]}")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode result = json(response.getBody());
    assertThat(result.get("remainingGuestId").asString()).isEqualTo(guestId);
    String newGuestId = result.get("detachedRecords").get(0).get("guestId").asString();
    assertThat(newGuestId).isNotEqualTo(guestId);

    // Remaining guest shrank to two records; the detached record sits on the new guest.
    assertThat(get("/api/v1/guests/" + guestId).get("recordCount").asInt()).isEqualTo(2);
    JsonNode movedRecords = get("/api/v1/guests/" + newGuestId + "/records").get("records");
    assertThat(movedRecords.size()).isEqualTo(1);
    // Originals untouched, bit for bit (US3-AS4).
    assertThat(movedRecords.get(0).get("payload").get("note").asString()).isEqualTo("wrong person");
    // The unmerge itself is in the decision history (US3-AS3).
    JsonNode explain = get("/api/v1/guests/" + guestId + "/explain").get("events");
    boolean hasUnmerge = false;
    for (JsonNode event : explain) {
      hasUnmerge |= "UNMERGE".equals(event.get("kind").asString());
    }
    assertThat(hasUnmerge).isTrue();
  }

  @Test
  void reingestingTheIdenticalRecordDoesNotSilentlyRecreateTheMerge() {
    ingest("r1", "{\"email\":\"shared@example.com\"}");
    JsonNode second = ingest("r2", "{\"email\":\"shared@example.com\"}");
    String guestId = second.get("guestId").asString();
    String recordId = second.get("sourceRecordId").asString();
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/guests/" + guestId + "/unmerge")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"sourceRecordIds\":[\"" + recordId + "\"]}")
        .retrieve()
        .toEntity(String.class);

    // Identical record, identical externalKey: dedup keeps the unmerge intact.
    JsonNode reingest = ingest("r2", "{\"email\":\"shared@example.com\"}");

    assertThat(reingest.get("status").asString()).isEqualTo("DUPLICATE_IGNORED");
    assertThat(reingest.get("guestId").asString()).isNotEqualTo(guestId);
    assertThat(get("/api/v1/guests/" + guestId).get("recordCount").asInt()).isEqualTo(1);
  }

  @Test
  void unmergeOnSingleRecordGuestIsRejectedAsProblemDetails() {
    JsonNode only = ingest("solo", "{\"email\":\"solo@example.com\"}");

    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/guests/" + only.get("guestId").asString() + "/unmerge")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"sourceRecordIds\":[\"" + only.get("sourceRecordId").asString() + "\"]}")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    assertThat(json(response.getBody()).get("detail").asString()).contains("single source record");
  }

  @Test
  void explainOnUnknownGuestIsNotFound() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/guests/00000000-0000-0000-0000-000000000042/explain")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody()).get("results").get(0);
  }

  private JsonNode get(String uri) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY).get().uri(uri).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody());
  }
}
