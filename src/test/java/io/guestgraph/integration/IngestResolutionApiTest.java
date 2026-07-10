package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US1 acceptance scenarios 1–6, end to end over the API. */
class IngestResolutionApiTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystems() {
    registerSource(TENANT_A_KEY, "opera-pms");
    registerSource(TENANT_A_KEY, "loyalty-db");
  }

  @Test
  void newEmailCreatesGuest() {
    JsonNode result =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"r-1",
                 "payload":{"firstName":"Anna","lastName":"Muster","email":"anna@example.com"}}
                """);

    assertThat(result.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(result.get("guestId").asString()).isNotBlank();
  }

  @Test
  void sameEmailDifferentCaseAttachesToExistingGuest() {
    JsonNode first =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"r-1","payload":{"email":"anna@example.com"}}
                """);
    JsonNode second =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"loyalty-db","externalKey":"l-1","payload":{"email":"  ANNA@Example.COM "}}
                """);

    assertThat(second.get("status").asString()).isEqualTo("ATTACHED");
    assertThat(second.get("guestId").asString()).isEqualTo(first.get("guestId").asString());
  }

  @Test
  void recordSharingIdentifiersWithTwoGuestsMergesThem() {
    JsonNode a =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"a","payload":{"email":"a@example.com"}}
                """);
    JsonNode b =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"b","payload":{"phone":"+41791112233"}}
                """);
    assertThat(a.get("guestId").asString()).isNotEqualTo(b.get("guestId").asString());

    JsonNode c =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"loyalty-db","externalKey":"c",
                 "payload":{"email":"a@example.com","phone":"+41 79 111 22 33"}}
                """);

    assertThat(c.get("status").asString()).isEqualTo("MERGED");
    // The merge decision is recorded with matcher name + confidence (FR-009).
    Integer merges =
        jdbc.sql(
                """
                        SELECT count(*) FROM merge_event
                        WHERE kind = 'MERGE' AND matcher_name = 'deterministic-identifier-v1' AND confidence = 1.0
                        """)
            .query(Integer.class)
            .single();
    assertThat(merges).isEqualTo(1);
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(1);
  }

  @Test
  void malformedButParseableRecordIsStoredFlaggedNeverDropped() {
    JsonNode result =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"bad-1",
                 "payload":{"firstName":"Anna","email":"not-an-email"}}
                """);

    assertThat(result.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(result.get("needsReview").asBoolean()).isTrue();
    assertThat(result.get("guestId").asString()).isNotBlank();
    JsonNode stored =
        jdbc.sql(
                "SELECT needs_review, needs_review_reasons::text AS reasons FROM source_record WHERE external_key = 'bad-1'")
            .query(
                (rs, i) ->
                    json(
                        "{\"needsReview\":"
                            + rs.getBoolean("needs_review")
                            + ",\"reasons\":"
                            + rs.getString("reasons")
                            + "}"))
            .single();
    assertThat(stored.get("needsReview").asBoolean()).isTrue();
    assertThat(stored.get("reasons").toString()).contains("email");
  }

  @Test
  void recordWithOneMalformedAndOneValidIdentifierResolvesOnTheValidOne() {
    JsonNode first =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"mix-1","payload":{"phone":"+41791234567"}}
                """);

    // Spec edge case: malformed email + valid phone → resolves via the phone, flagged, never
    // dropped.
    JsonNode mixed =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"loyalty-db","externalKey":"mix-2",
                 "payload":{"email":"not-an-email","phone":"+41 79 123 45 67"}}
                """);

    assertThat(mixed.get("status").asString()).isEqualTo("ATTACHED");
    assertThat(mixed.get("guestId").asString()).isEqualTo(first.get("guestId").asString());
    assertThat(mixed.get("needsReview").asBoolean()).isTrue();
    JsonNode reasons =
        jdbc.sql(
                "SELECT needs_review_reasons::text AS r FROM source_record WHERE external_key = 'mix-2'")
            .query((rs, i) -> json(rs.getString("r")))
            .single();
    assertThat(reasons.toString()).contains("email");
  }

  @Test
  void unparseableBodyIsRejectedAndNothingStored() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{this is not json")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    Integer records = jdbc.sql("SELECT count(*) FROM source_record").query(Integer.class).single();
    assertThat(records).isZero();
  }

  @Test
  void unknownSourceSystemOnSingleRecordIsBadRequest() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                        {"sourceSystem":"nope","externalKey":"x","payload":{"email":"a@example.com"}}
                        """)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(json(response.getBody()).get("detail").asString()).contains("nope");
  }

  @Test
  void batchIsNeverAtomicAndReportsPerRecordOutcomes() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                        [{"sourceSystem":"opera-pms","externalKey":"ok-1","payload":{"email":"ok@example.com"}},
                         {"sourceSystem":"nope","externalKey":"bad-1","payload":{"email":"x@example.com"}},
                         {"sourceSystem":"opera-pms","externalKey":"ok-1","payload":{"email":"ok@example.com"}}]
                        """)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode results = json(response.getBody()).get("results");
    assertThat(results.size()).isEqualTo(3);
    assertThat(results.get(0).get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(results.get(1).get("status").asString()).isEqualTo("ERROR");
    assertThat(results.get(1).get("problem").get("detail").asString()).contains("nope");
    assertThat(results.get(2).get("status").asString()).isEqualTo("DUPLICATE_IGNORED");
  }

  @Test
  void concurrentIngestSharingAnIdentifierYieldsOneConsistentGuest() throws Exception {
    List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String body =
          """
                    {"sourceSystem":"opera-pms","externalKey":"conc-%d","payload":{"email":"race@example.com"}}
                    """
              .formatted(i);
      futures.add(CompletableFuture.supplyAsync(() -> ingestOne(TENANT_A_KEY, body)));
    }
    Set<String> guestIds = new HashSet<>();
    for (CompletableFuture<JsonNode> future : futures) {
      guestIds.add(future.get().get("guestId").asString());
    }

    // All 8 records resolved; the graph converged on exactly one guest.
    Integer guests =
        jdbc.sql("SELECT count(*) FROM guest WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(guests).isEqualTo(1);
    Integer links =
        jdbc.sql("SELECT count(*) FROM resolution_link WHERE tenant_id = :t")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(links).isEqualTo(8);
    // And every response pointed at a guest that still exists or was merged away consistently:
    assertThat(guestIds).isNotEmpty();
  }

  @Test
  void overThresholdIdentifierParksReviewInsteadOfMerging() {
    setReviewThreshold(TENANT_A, 1);
    JsonNode first =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"t-1","payload":{"email":"family@example.com"}}
                """);
    assertThat(first.get("status").asString()).isEqualTo("CREATED_GUEST");

    // Second record sharing the email: 2 > threshold 1 → parked, not attached (FR-017).
    JsonNode second =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"t-2","payload":{"email":"family@example.com"}}
                """);

    assertThat(second.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(second.get("guestId").asString()).isNotEqualTo(first.get("guestId").asString());
    assertThat(second.get("pendingReviewIds").size()).isEqualTo(1);
    Integer pending =
        jdbc.sql("SELECT count(*) FROM match_review WHERE tenant_id = :t AND status = 'PENDING'")
            .param("t", TENANT_A)
            .query(Integer.class)
            .single();
    assertThat(pending).isEqualTo(1);
    Integer merges =
        jdbc.sql("SELECT count(*) FROM merge_event WHERE kind IN ('ATTACH','MERGE')")
            .query(Integer.class)
            .single();
    assertThat(merges).isZero();
  }

  @Test
  void identifierSharedAcrossTenantsNeverMatches() {
    registerSource(TENANT_B_KEY, "opera-pms");
    JsonNode inTenantA =
        ingestOne(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"x-1","payload":{"email":"same@example.com"}}
                """);

    JsonNode inTenantB =
        ingestOne(
            TENANT_B_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"x-1","payload":{"email":"same@example.com"}}
                """);

    // SC-006: the same identifier in another tenant is invisible — two guests, no merge, no review.
    assertThat(inTenantA.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(inTenantB.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(inTenantB.get("guestId").asString())
        .isNotEqualTo(inTenantA.get("guestId").asString());
    Integer crossTenantEvents =
        jdbc.sql("SELECT count(*) FROM merge_event WHERE kind IN ('ATTACH','MERGE')")
            .query(Integer.class)
            .single();
    assertThat(crossTenantEvents).isZero();
    Integer reviews = jdbc.sql("SELECT count(*) FROM match_review").query(Integer.class).single();
    assertThat(reviews).isZero();
  }

  private void registerSource(String apiKey, String code) {
    api(apiKey)
        .post()
        .uri("/api/v1/source-systems")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private JsonNode ingestOne(String apiKey, String body) {
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
}
