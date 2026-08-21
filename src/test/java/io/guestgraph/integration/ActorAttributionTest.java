package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.auth.Sha256;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US3 acceptance scenarios: every decision names who made it. */
class ActorAttributionTest extends PostgresIntegrationTest {

  private static final String AGENT_KEY = "agent-key";

  @BeforeEach
  void setUp() {
    register(TENANT_A_KEY, "opera-pms");
    // A credential issued to an agent rather than a person. The type is fixed here, at
    // issuance — which is the whole point: a request can never claim its way to HUMAN.
    jdbc.sql(
            """
                INSERT INTO api_key (id, tenant_id, key_hash, label, actor_type, actor_name)
                VALUES (:id, :tenantId, :keyHash, 'agent', 'AGENT', 'triage-bot')
                ON CONFLICT (key_hash) DO NOTHING
                """)
        .param("id", UUID.nameUUIDFromBytes("key:agent".getBytes()))
        .param("tenantId", TENANT_A)
        .param("keyHash", Sha256.hex(AGENT_KEY))
        .update();
    // api_key is not in resetDatabase's TRUNCATE list, so a revocation from an earlier test
    // would otherwise persist into the next one.
    jdbc.sql("UPDATE api_key SET revoked_at = NULL WHERE tenant_id = :t")
        .param("t", TENANT_A)
        .update();
  }

  @Test
  void automaticResolutionIsAttributedToTheSystemAndNeverToAPerson() {
    String guestId = ingest(TENANT_A_KEY, "r-1", "anna@example.com").get("guestId").asString();

    JsonNode events = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/explain").get("events");
    assertThat(events.size()).isPositive();
    for (JsonNode event : events) {
      assertThat(event.get("actor").get("type").asString()).isEqualTo("SYSTEM");
      assertThat(event.get("actor").get("id").asString())
          .isEqualTo(event.get("matcherName").asString());
    }
  }

  @Test
  void aHumanStewardsUnmergeNamesTheIndividualBehindTheCredential() {
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");

    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/guests/" + second.get("guestId").asString() + "/unmerge")
        .header("X-Actor-Id", "rob@example.com")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"sourceRecordIds\":[\"" + second.get("sourceRecordId").asString() + "\"]}")
        .retrieve()
        .toEntity(String.class);

    JsonNode rule = get(TENANT_A_KEY, "/api/v1/negative-rules").get("rules").get(0);
    assertThat(rule.get("actor").get("type").asString()).isEqualTo("HUMAN");
    assertThat(rule.get("actor").get("id").asString()).isEqualTo("rob@example.com");
  }

  @Test
  void anAgentCredentialRecordsAnAgentActor() {
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");

    api(AGENT_KEY)
        .post()
        .uri("/api/v1/guests/" + second.get("guestId").asString() + "/unmerge")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"sourceRecordIds\":[\"" + second.get("sourceRecordId").asString() + "\"]}")
        .retrieve()
        .toEntity(String.class);

    JsonNode rule = get(AGENT_KEY, "/api/v1/negative-rules").get("rules").get(0);
    assertThat(rule.get("actor").get("type").asString()).isEqualTo("AGENT");
    // Falls back to the credential's own name when the request names no individual.
    assertThat(rule.get("actor").get("id").asString()).isEqualTo("triage-bot");
  }

  @Test
  void aRequestCannotClaimAnActorTypeItsCredentialDoesNotGrant() {
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");

    ResponseEntity<String> response =
        api(AGENT_KEY)
            .post()
            .uri("/api/v1/guests/" + second.get("guestId").asString() + "/unmerge")
            .header("X-Actor-Type", "HUMAN")
            .header("X-Actor-Id", "rob@example.com")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"sourceRecordIds\":[\"" + second.get("sourceRecordId").asString() + "\"]}")
            .retrieve()
            .onStatus(status -> true, (req, res) -> {})
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
    // ...and nothing was recorded.
    assertThat(get(TENANT_A_KEY, "/api/v1/negative-rules").get("total").asInt()).isZero();
  }

  @Test
  void liftingARuleKeepsItReadableWithBothActors() {
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");
    api(TENANT_A_KEY)
        .post()
        .uri("/api/v1/guests/" + second.get("guestId").asString() + "/unmerge")
        .header("X-Actor-Id", "rob@example.com")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"sourceRecordIds\":[\"" + second.get("sourceRecordId").asString() + "\"]}")
        .retrieve()
        .toEntity(String.class);
    String ruleId =
        get(TENANT_A_KEY, "/api/v1/negative-rules").get("rules").get(0).get("id").asString();

    api(AGENT_KEY).delete().uri("/api/v1/negative-rules/" + ruleId).retrieve().toBodilessEntity();

    // No longer active...
    assertThat(get(TENANT_A_KEY, "/api/v1/negative-rules").get("total").asInt()).isZero();
    // ...but the override of a human's split is still on the record, with both actors.
    JsonNode lifted =
        get(TENANT_A_KEY, "/api/v1/negative-rules?includeLifted=true").get("rules").get(0);
    assertThat(lifted.get("liftedAt").isNull()).isFalse();
    assertThat(lifted.get("actor").get("id").asString()).isEqualTo("rob@example.com");
    assertThat(lifted.get("liftedActor").get("type").asString()).isEqualTo("AGENT");
  }

  @Test
  void actorsAreTenantScoped() {
    ingest(TENANT_A_KEY, "r-1", "anna@example.com");
    register(TENANT_B_KEY, "opera-pms");
    ingest(TENANT_B_KEY, "r-1", "anna@example.com");

    assertThat(get(TENANT_B_KEY, "/api/v1/negative-rules").get("total").asInt()).isZero();
  }

  @Test
  void aPairMayBeSplitAgainAfterItsRuleWasLifted() {
    // The whole reason V3 replaces negative_match_rule's unique constraint with a partial index
    // over active rules. Nothing else exercises that against Postgres.
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");
    String guestId = second.get("guestId").asString();
    String recordId = second.get("sourceRecordId").asString();

    unmerge(TENANT_A_KEY, guestId, recordId, "rob@example.com");
    String ruleId =
        get(TENANT_A_KEY, "/api/v1/negative-rules").get("rules").get(0).get("id").asString();
    api(TENANT_A_KEY)
        .delete()
        .uri("/api/v1/negative-rules/" + ruleId)
        .retrieve()
        .toBodilessEntity();

    // Fresh evidence re-merges the pair, and a second unmerge splits them again — the insert
    // would violate a plain unique constraint on (tenant, record_a, record_b).
    JsonNode rejoined = ingest(TENANT_A_KEY, "r-3", "shared@example.com");
    unmerge(
        TENANT_A_KEY,
        rejoined.get("guestId").asString(),
        rejoined.get("sourceRecordId").asString(),
        "rob@example.com");

    assertThat(get(TENANT_A_KEY, "/api/v1/negative-rules").get("total").asInt()).isPositive();
    assertThat(get(TENANT_A_KEY, "/api/v1/negative-rules?includeLifted=true").get("total").asInt())
        .isGreaterThan(get(TENANT_A_KEY, "/api/v1/negative-rules").get("total").asInt());
  }

  @Test
  void aRecordedActorStaysReadableAfterItsCredentialIsRevoked() {
    // FR-017: the audit trail outlives the key. Revoking a credential must not blank the
    // attribution of decisions it already made.
    ingest(TENANT_A_KEY, "r-1", "shared@example.com");
    JsonNode second = ingest(TENANT_A_KEY, "r-2", "shared@example.com");
    unmerge(
        AGENT_KEY, second.get("guestId").asString(), second.get("sourceRecordId").asString(), null);

    jdbc.sql("UPDATE api_key SET revoked_at = now() WHERE actor_name = 'triage-bot'").update();

    JsonNode rule = get(TENANT_A_KEY, "/api/v1/negative-rules").get("rules").get(0);
    assertThat(rule.get("actor").get("type").asString()).isEqualTo("AGENT");
    assertThat(rule.get("actor").get("id").asString()).isEqualTo("triage-bot");
  }

  @Test
  void eventsPredatingActorIdentityRenderUnattributed() {
    // FR-015 through the real path: a NULL actor_type must survive the mapper and the explain
    // serializer, not merely the domain record's own accessor.
    String guestId = ingest(TENANT_A_KEY, "r-1", "anna@example.com").get("guestId").asString();
    jdbc.sql("UPDATE merge_event SET actor_type = NULL, actor_id = NULL").update();

    JsonNode events = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/explain").get("events");
    assertThat(events.size()).isPositive();
    assertThat(events.get(0).get("actor").isNull()).isTrue();
  }

  @Test
  void theDecisionResponseNamesTheActorThatMadeIt() {
    // FR-015: "explain output AND decision responses".
    setReviewThreshold(TENANT_A, 1);
    ingest(TENANT_A_KEY, "p-1", "shared@example.com");
    JsonNode parked = ingest(TENANT_A_KEY, "p-2", "shared@example.com");
    String reviewId = parked.get("pendingReviewIds").get(0).asString();

    JsonNode decided =
        json(
            api(TENANT_A_KEY)
                .post()
                .uri("/api/v1/match-reviews/" + reviewId)
                .header("X-Actor-Id", "rob@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"CONFIRM\"}")
                .retrieve()
                .toEntity(String.class)
                .getBody());

    assertThat(decided.get("actor").get("type").asString()).isEqualTo("HUMAN");
    assertThat(decided.get("actor").get("id").asString()).isEqualTo("rob@example.com");
  }

  private void unmerge(String apiKey, String guestId, String recordId, String actorId) {
    var request =
        api(apiKey)
            .post()
            .uri("/api/v1/guests/" + guestId + "/unmerge")
            .contentType(MediaType.APPLICATION_JSON);
    if (actorId != null) {
      request = request.header("X-Actor-Id", actorId);
    }
    request.body("{\"sourceRecordIds\":[\"" + recordId + "\"]}").retrieve().toEntity(String.class);
  }

  // --- helpers ---

  private void register(String apiKey, String code) {
    api(apiKey)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private JsonNode ingest(String apiKey, String externalKey, String email) {
    ResponseEntity<String> response =
        api(apiKey)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"sourceSystem\":\"opera-pms\",\"externalKey\":\""
                    + externalKey
                    + "\",\"payload\":{\"email\":\""
                    + email
                    + "\"}}")
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody()).get("results").get(0);
  }

  private JsonNode get(String apiKey, String uri) {
    return json(api(apiKey).get().uri(uri).retrieve().toEntity(String.class).getBody());
  }
}
