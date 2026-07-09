package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US4 acceptance scenarios 1–4 over the API. */
class MatchReviewApiTest extends PostgresIntegrationTest {

  private String candidateGuestId;
  private String reviewId;

  @BeforeEach
  void parkAReview() {
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"opera-pms\",\"name\":\"Opera\"}")
        .retrieve()
        .toEntity(String.class);
    setReviewThreshold(TENANT_A, 1);
    candidateGuestId = ingest("r1", "{\"email\":\"family@example.com\"}").get("guestId").asString();
    JsonNode parked = ingest("r2", "{\"email\":\"family@example.com\"}");
    reviewId = parked.get("pendingReviewIds").get(0).asString();
  }

  @Test
  void overThresholdMatchIsQueuedNotMerged() {
    JsonNode queue = get("/api/v1/match-reviews");

    assertThat(queue.get("total").asInt()).isEqualTo(1);
    JsonNode review = queue.get("reviews").get(0);
    assertThat(review.get("id").asString()).isEqualTo(reviewId);
    assertThat(review.get("status").asString()).isEqualTo("PENDING");
    assertThat(review.get("candidateGuestId").asString()).isEqualTo(candidateGuestId);
    assertThat(review.get("reason").asString()).contains("threshold");
    // No merge happened: two distinct guests exist.
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(2);
  }

  @Test
  void confirmExecutesTheMergeAndRecordsIt() {
    JsonNode decided = decide(reviewId, "CONFIRM", HttpStatus.OK);

    assertThat(decided.get("status").asString()).isEqualTo("CONFIRMED");
    assertThat(decided.get("decidedAt").asString()).isNotBlank();
    // Merge executed and recorded with matcher name + confidence (US4-AS2).
    Integer confirms =
        jdbc.sql(
                """
                    SELECT count(*) FROM merge_event
                    WHERE kind = 'REVIEW_CONFIRM' AND matcher_name = 'manual-review' AND confidence = 1.0
                    """)
            .query(Integer.class)
            .single();
    assertThat(confirms).isEqualTo(1);
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(1);
  }

  @Test
  void rejectKeepsRecordsSeparateAndIsRecorded() {
    JsonNode decided = decide(reviewId, "REJECT", HttpStatus.OK);

    assertThat(decided.get("status").asString()).isEqualTo("REJECTED");
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(2);
    Integer rejects =
        jdbc.sql("SELECT count(*) FROM merge_event WHERE kind = 'REVIEW_REJECT'")
            .query(Integer.class)
            .single();
    assertThat(rejects).isEqualTo(1);
  }

  @Test
  void secondDecisionConflictsAndFirstStands() {
    decide(reviewId, "REJECT", HttpStatus.OK);

    ResponseEntity<String> second = decideRaw(reviewId, "CONFIRM");

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(second.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    // The rejection stands: still two guests.
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(2);
  }

  @Test
  void reviewQueueIsTenantIsolated() {
    ResponseEntity<String> tenantBQueue =
        api(TENANT_B_KEY).get().uri("/api/v1/match-reviews").retrieve().toEntity(String.class);
    assertThat(json(tenantBQueue.getBody()).get("total").asInt()).isZero();

    // And tenant B cannot decide tenant A's review — it looks nonexistent.
    ResponseEntity<String> crossDecide =
        api(TENANT_B_KEY)
            .post()
            .uri("/api/v1/match-reviews/" + reviewId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"decision\":\"CONFIRM\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(crossDecide.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

  private JsonNode get(String uri) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY).get().uri(uri).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody());
  }

  private JsonNode decide(String id, String decision, HttpStatus expected) {
    ResponseEntity<String> response = decideRaw(id, decision);
    assertThat(response.getStatusCode()).isEqualTo(expected);
    return json(response.getBody());
  }

  private ResponseEntity<String> decideRaw(String id, String decision) {
    return api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/match-reviews/" + id)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"decision\":\"" + decision + "\"}")
        .retrieve()
        .toEntity(String.class);
  }
}
