package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US1 acceptance scenarios 1–6 end to end over the API. */
class FuzzyReviewApiTest extends PostgresIntegrationTest {

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
  void fuzzyCandidateParksForReviewWithBreakdownUnderDefaults() {
    JsonNode first =
        ingest(
            "f-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");

    JsonNode second =
        ingest(
            "f-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Mueller\",\"birthdate\":\"1985-03-12\"}");

    // US1-AS1: no auto-merge; a scored review entry with the breakdown appears.
    assertThat(second.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(second.get("guestId").asString()).isNotEqualTo(first.get("guestId").asString());
    assertThat(second.get("pendingReviewIds").size()).isEqualTo(1);
    JsonNode review = get("/api/v1/match-reviews").get("reviews").get(0);
    assertThat(review.get("matcherName").asString()).isEqualTo("fuzzy-rules-v1");
    assertThat(review.get("confidence").asDouble()).isBetween(0.75, 1.0);
    assertThat(review.get("reason").asString()).contains("score").contains("name");
  }

  @Test
  void confirmingAFuzzyReviewMergesAndRecordsScoreAsConfidence() {
    ingest("f-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");
    JsonNode second =
        ingest(
            "f-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Mueller\",\"birthdate\":\"1985-03-12\"}");
    String reviewId = second.get("pendingReviewIds").get(0).asString();

    ResponseEntity<String> decide =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/match-reviews/" + reviewId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"decision\":\"CONFIRM\"}")
            .retrieve()
            .toEntity(String.class);

    // US1-AS2: merge executed and recorded like any decision.
    assertThat(decide.getStatusCode()).isEqualTo(HttpStatus.OK);
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(1);
  }

  @Test
  void weakSimilarityDoesNotFloodTheQueue() {
    ingest(
        "w-1",
        "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\",\"phone\":\"+41441234567\"}");

    // Same-ish name but conflicting birthdate: different-person evidence (US1-AS3).
    JsonNode other =
        ingest(
            "w-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1991-07-01\",\"phone\":\"+41791234567\"}");

    assertThat(other.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(other.get("pendingReviewIds").size()).isZero();
    assertThat(get("/api/v1/match-reviews").get("total").asInt()).isZero();
  }

  @Test
  void tenantOptInAutoMergesAndAuditsTheBreakdown() {
    // US1-AS4 — tenant explicitly lowers the auto-merge threshold (config API lands in
    // US4; the persistent config is exercised directly here).
    jdbc.sql("UPDATE tenant SET auto_merge_threshold = 0.850 WHERE id = :t")
        .param("t", TENANT_A)
        .update();
    JsonNode first =
        ingest(
            "a-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");

    JsonNode second =
        ingest(
            "a-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Mueller\",\"birthdate\":\"1985-03-12\"}");

    assertThat(second.get("status").asString()).isEqualTo("ATTACHED");
    assertThat(second.get("guestId").asString()).isEqualTo(first.get("guestId").asString());
    JsonNode explain =
        get("/api/v1/guests/" + second.get("guestId").asString() + "/explain").get("events");
    JsonNode attach = explain.get(explain.size() - 1);
    assertThat(attach.get("matcherName").asString()).isEqualTo("fuzzy-rules-v1");
    assertThat(attach.get("confidence").asDouble()).isBetween(0.85, 1.0);
    assertThat(attach.get("evidence").toString()).contains("signals");
  }

  @Test
  void perfectScoreStillParksUnderDefaultConfiguration() {
    ingest("p-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");

    JsonNode second =
        ingest(
            "p-2", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\"}");

    // US1-AS5: automation is strictly opt-in.
    assertThat(second.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(second.get("pendingReviewIds").size()).isEqualTo(1);
  }

  @Test
  void exactIdentifierPrecedenceUnchanged() {
    JsonNode first =
        ingest(
            "e-1",
            "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"birthdate\":\"1985-03-12\",\"email\":\"anna@example.com\"}");

    JsonNode second =
        ingest(
            "e-2",
            "{\"firstName\":\"Anna\",\"lastName\":\"Mueller\",\"birthdate\":\"1985-03-12\",\"email\":\"anna@example.com\"}");

    // US1-AS6: deterministic wins, no parallel fuzzy review.
    assertThat(second.get("status").asString()).isEqualTo("ATTACHED");
    assertThat(second.get("guestId").asString()).isEqualTo(first.get("guestId").asString());
    assertThat(get("/api/v1/match-reviews").get("total").asInt()).isZero();
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
