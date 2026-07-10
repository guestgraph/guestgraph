package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US3 acceptance scenarios 1–5 over the API. */
class QualityRuleApiTest extends PostgresIntegrationTest {

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
  void sharedMaskedOtaEmailNeverMergesStrangers() {
    JsonNode ben =
        ingest(
            "m-1",
            "{\"firstName\":\"Ben\",\"lastName\":\"Ott\",\"email\":\"x1@guest.booking.com\"}");
    JsonNode eva =
        ingest(
            "m-2",
            "{\"firstName\":\"Eva\",\"lastName\":\"Roth\",\"email\":\"x1@guest.booking.com\"}");

    // US3-AS1: two guests; the alias alone produces at most a review, never a merge.
    assertThat(ben.get("guestId").asString()).isNotEqualTo(eva.get("guestId").asString());
    Integer merges =
        jdbc.sql("SELECT count(*) FROM merge_event WHERE kind IN ('ATTACH','MERGE')")
            .query(Integer.class)
            .single();
    assertThat(merges).isZero();
    // No EMAIL identifier was written for the relay address.
    Integer emailIdentifiers =
        jdbc.sql("SELECT count(*) FROM record_identifier WHERE type = 'EMAIL'")
            .query(Integer.class)
            .single();
    assertThat(emailIdentifiers).isZero();
  }

  @Test
  void maskedEmailNeverReplacesARealProfileEmail() {
    JsonNode first =
        ingest(
            "p-1",
            "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"email\":\"anna@example.com\",\"phone\":\"+41791234567\"}");
    // Newer record on the same guest (shared phone) carries only a relay address:
    ingest(
        "p-2",
        "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"email\":\"x9@guest.booking.com\",\"phone\":\"+41791234567\"}");

    JsonNode guest = get("/api/v1/guests/" + first.get("guestId").asString());

    // US3-AS2: the golden profile keeps the real address.
    assertThat(guest.get("profile").get("email").asString()).isEqualTo("anna@example.com");
    assertThat(guest.get("profile").has("emailMasked")).isFalse();
  }

  @Test
  void ignoreRuleSilencesASharedAgencyIdentifier() {
    // US3-AS3 — including retroactivity: the first record's phone identifier predates the rule.
    ingest("a-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"phone\":\"+41448887766\"}");
    addRule(
        "{\"identifierType\":\"PHONE\",\"matchKind\":\"EXACT\",\"value\":\"+41448887766\",\"rule\":\"IGNORE\",\"note\":\"agency desk\"}");

    JsonNode second =
        ingest("a-2", "{\"firstName\":\"Bob\",\"lastName\":\"Meier\",\"phone\":\"+41448887766\"}");

    assertThat(second.get("status").asString()).isEqualTo("CREATED_GUEST");
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(2);
  }

  @Test
  void perfectMatchRuleRoutesNameMismatchesToReview() {
    addRule(
        "{\"identifierType\":\"EMAIL\",\"matchKind\":\"EXACT\",\"value\":\"vip@example.com\",\"rule\":\"PERFECT_MATCH\"}");
    ingest("v-1", "{\"firstName\":\"Anna\",\"lastName\":\"Müller\",\"email\":\"vip@example.com\"}");

    JsonNode mismatch =
        ingest(
            "v-2", "{\"firstName\":\"Bob\",\"lastName\":\"Meier\",\"email\":\"vip@example.com\"}");

    // US3-AS4: parked instead of merged.
    assertThat(mismatch.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(mismatch.get("pendingReviewIds").size()).isEqualTo(1);
    JsonNode review = get("/api/v1/match-reviews").get("reviews").get(0);
    assertThat(review.get("reason").asString()).contains("perfect-match");
  }

  @Test
  void builtinsAreListedDistinguishableAndNotDeletable() {
    JsonNode rules = get("/api/v1/config/identifier-rules").get("rules");

    // US3-AS5: built-ins visible and flagged.
    boolean hasBookingBuiltin = false;
    for (JsonNode rule : rules) {
      if (rule.get("builtin").asBoolean()
          && rule.get("value").asString().equals("guest.booking.com")) {
        hasBookingBuiltin = true;
        assertThat(rule.get("rule").asString()).isEqualTo("MASKED_ALIAS");
      }
    }
    assertThat(hasBookingBuiltin).isTrue();

    // Tenant rules are deletable; built-ins have no id and cannot be addressed.
    JsonNode created =
        addRule(
            "{\"identifierType\":\"EMAIL\",\"matchKind\":\"EMAIL_DOMAIN\",\"value\":\"relay.example.com\",\"rule\":\"MASKED_ALIAS\"}");
    ResponseEntity<String> delete =
        api(TENANT_A_KEY)
            .delete()
            .uri("/api/v1/config/identifier-rules/" + created.get("id").asString())
            .retrieve()
            .toEntity(String.class);
    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void duplicateRuleConflictsAndInvalidComboIsRejected() {
    addRule(
        "{\"identifierType\":\"EMAIL\",\"matchKind\":\"EXACT\",\"value\":\"dup@example.com\",\"rule\":\"IGNORE\"}");
    ResponseEntity<String> duplicate =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/config/identifier-rules")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"identifierType\":\"EMAIL\",\"matchKind\":\"EXACT\",\"value\":\"dup@example.com\",\"rule\":\"IGNORE\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    ResponseEntity<String> invalid =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/config/identifier-rules")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"identifierType\":\"PHONE\",\"matchKind\":\"EMAIL_DOMAIN\",\"value\":\"x.com\",\"rule\":\"IGNORE\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private JsonNode addRule(String body) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/config/identifier-rules")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return json(response.getBody());
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
}
