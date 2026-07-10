package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US2 acceptance scenarios 1–4 over the API. */
class NegativeRuleApiTest extends PostgresIntegrationTest {

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
  void unmergeWritesARuleAndFreshEvidenceParksInsteadOfMerging() {
    ingest("r1", "{\"email\":\"shared@example.com\"}");
    JsonNode second = ingest("r2", "{\"email\":\"shared@example.com\"}");
    unmerge(second.get("guestId").asString(), second.get("sourceRecordId").asString());

    // US2-AS1: fresh exact evidence bridging the split parks, citing the rule.
    JsonNode fresh = ingest("r3", "{\"email\":\"shared@example.com\"}");

    assertThat(fresh.get("status").asString()).isNotEqualTo("MERGED");
    assertThat(fresh.get("pendingReviewIds").size()).isGreaterThan(0);
    JsonNode review = get("/api/v1/match-reviews").get("reviews").get(0);
    assertThat(review.get("reason").asString()).contains("do-not-merge");
    // US2-AS4: the rule is visible with its origin.
    JsonNode rules = get("/api/v1/negative-rules");
    assertThat(rules.get("total").asInt()).isEqualTo(1);
    assertThat(rules.get("rules").get(0).get("origin").asString()).isEqualTo("UNMERGE");
  }

  @Test
  void rejectionWritesARuleAndDoesNotRequeueTheSamePair() {
    setReviewThreshold(TENANT_A, 1);
    ingest("r1", "{\"email\":\"family@example.com\"}");
    JsonNode parked = ingest("r2", "{\"email\":\"family@example.com\"}");
    decide(parked.get("pendingReviewIds").get(0).asString(), "REJECT");

    JsonNode rules = get("/api/v1/negative-rules");
    assertThat(rules.get("total").asInt()).isGreaterThan(0);
    assertThat(rules.get("rules").get(0).get("origin").asString()).isEqualTo("REVIEW_REJECT");

    // US2-AS2: the same evidence does not silently merge and does not spam the queue.
    setReviewThreshold(TENANT_A, 100);
    JsonNode bridge = ingest("r3", "{\"email\":\"family@example.com\"}");
    assertThat(bridge.get("status").asString()).isNotEqualTo("MERGED");
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(2);
  }

  @Test
  void confirmingAcrossARuleLiftsIt() {
    ingest("r1", "{\"email\":\"shared@example.com\"}");
    JsonNode second = ingest("r2", "{\"email\":\"shared@example.com\"}");
    unmerge(second.get("guestId").asString(), second.get("sourceRecordId").asString());
    JsonNode fresh = ingest("r3", "{\"email\":\"shared@example.com\"}");
    String reviewId = fresh.get("pendingReviewIds").get(0).asString();

    // US2-AS3: explicit confirmation supersedes the split and lifts the rule.
    decide(reviewId, "CONFIRM");

    assertThat(get("/api/v1/negative-rules").get("total").asInt()).isZero();
    JsonNode next = ingest("r4", "{\"email\":\"shared@example.com\"}");
    assertThat(next.get("status").asString()).isEqualTo("ATTACHED");
  }

  @Test
  void deletingARuleReenablesMatching() {
    ingest("r1", "{\"email\":\"shared@example.com\"}");
    JsonNode second = ingest("r2", "{\"email\":\"shared@example.com\"}");
    unmerge(second.get("guestId").asString(), second.get("sourceRecordId").asString());
    String ruleId = get("/api/v1/negative-rules").get("rules").get(0).get("id").asString();

    ResponseEntity<String> delete =
        api(TENANT_A_KEY)
            .delete()
            .uri("/api/v1/negative-rules/" + ruleId)
            .retrieve()
            .toEntity(String.class);

    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    JsonNode next = ingest("r5", "{\"email\":\"shared@example.com\"}");
    assertThat(next.get("status").asString()).isEqualTo("MERGED");
  }

  @Test
  void rulesAreTenantIsolated() {
    ingest("r1", "{\"email\":\"shared@example.com\"}");
    JsonNode second = ingest("r2", "{\"email\":\"shared@example.com\"}");
    unmerge(second.get("guestId").asString(), second.get("sourceRecordId").asString());
    String ruleId = get("/api/v1/negative-rules").get("rules").get(0).get("id").asString();

    ResponseEntity<String> tenantB =
        api(TENANT_B_KEY).get().uri("/api/v1/negative-rules").retrieve().toEntity(String.class);
    assertThat(json(tenantB.getBody()).get("total").asInt()).isZero();
    ResponseEntity<String> crossDelete =
        api(TENANT_B_KEY)
            .delete()
            .uri("/api/v1/negative-rules/" + ruleId)
            .retrieve()
            .toEntity(String.class);
    assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

  private void unmerge(String guestId, String recordId) {
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/guests/" + guestId + "/unmerge")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"sourceRecordIds\":[\"" + recordId + "\"]}")
        .retrieve()
        .toEntity(String.class);
  }

  private void decide(String reviewId, String decision) {
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/match-reviews/" + reviewId)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"decision\":\"" + decision + "\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private JsonNode get(String uri) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY).get().uri(uri).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody());
  }
}
