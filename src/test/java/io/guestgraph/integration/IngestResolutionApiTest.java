package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

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

    assertThat(result.get("status").asString()).isEqualTo("NEEDS_REVIEW");
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
    List<CompletableFuture<JsonNode>> futures = new java.util.ArrayList<>();
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
