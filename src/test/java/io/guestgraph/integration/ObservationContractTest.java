package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Spec US4: the contract connectors must satisfy for mutable, multi-person source objects. Mostly
 * verification of behaviour US1 introduced — that is the point of writing it down separately.
 */
class ObservationContractTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystem() {
    register(TENANT_A_KEY, "opera-pms");
  }

  @Test
  void threePersonsOnOneVersionStoreThreeObservationsWithoutColliding() {
    String v = "2026-03-01T10:00:00Z";
    ingest(person("R1", "PRIMARY_GUEST", null, v, "anna@example.com"));
    ingest(person("R1", "ADDITIONAL_GUEST", 0, v, "bruno@example.com"));
    ingest(person("R1", "ADDITIONAL_GUEST", 1, v, "clara@example.com"));

    JsonNode object = get("/api/v1/source-objects/opera-pms/reservation/R1");
    assertThat(object.get("observations").size()).isEqualTo(3);
    assertThat(object.get("roster").size()).isEqualTo(3);
  }

  @Test
  void aVerbatimResubmissionIsAbsorbedWithNoSideEffects() {
    String v = "2026-03-01T10:00:00Z";
    String body = person("R1", "PRIMARY_GUEST", null, v, "anna@example.com");
    ingest(body);

    long records = count("source_record");
    long objects = count("record_object");
    long events = count("merge_event");

    JsonNode again = ingest(body);

    assertThat(again.get("status").asString()).isEqualTo("DUPLICATE_IGNORED");
    assertThat(count("source_record")).isEqualTo(records);
    assertThat(count("record_object")).isEqualTo(objects);
    assertThat(count("merge_event")).isEqualTo(events);
  }

  @Test
  void anUnusableVersionIsStoredAndFlaggedAndJoinsNoRoster() {
    JsonNode result =
        ingest(
            """
                {"sourceSystem":"opera-pms","externalKey":"bad-1",
                 "payload":{"email":"anna@example.com"},
                 "sourceObject":{"type":"reservation","id":"R9","role":"PRIMARY_GUEST",
                                 "version":"whenever"}}
                """);

    assertThat(result.get("needsReview").asBoolean()).isTrue();
    assertThat(count("record_object")).isZero();

    ResponseEntity<String> object =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/source-objects/opera-pms/reservation/R9")
            .retrieve()
            .onStatus(status -> true, (req, res) -> {})
            .toEntity(String.class);
    assertThat(object.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aRecordTimestampDisagreeingWithTheVersionIsFlaggedNotRejected() {
    JsonNode result =
        ingest(
            """
                {"sourceSystem":"opera-pms","externalKey":"skew-1",
                 "recordTimestamp":"2026-03-01T09:00:00Z",
                 "payload":{"email":"anna@example.com"},
                 "sourceObject":{"type":"reservation","id":"R8","role":"PRIMARY_GUEST",
                                 "version":"2026-03-01T10:00:00Z"}}
                """);

    // FR-020: survivorship and roster supersession must order observations identically.
    assertThat(result.get("needsReview").asBoolean()).isTrue();
    assertThat(result.get("status").asString()).isNotEqualTo("ERROR");
  }

  @Test
  void bookingLevelContactDataNestedInThePayloadCreatesNoGuestIdentifier() {
    // FR-023: the agency's phone belongs to the booking, not to the guest. Nested under an
    // object in the payload, extraction never sees it — a persistent non-personal identifier
    // on a reassigned reservation would transitively merge different people.
    ingest(
        """
            {"sourceSystem":"opera-pms","externalKey":"agency-1",
             "recordTimestamp":"2026-03-01T10:00:00Z",
             "payload":{"email":"anna@example.com",
                        "booking":{"agencyPhone":"+41445550000","propertyEmail":"desk@hotel.example"}},
             "sourceObject":{"type":"reservation","id":"R5","role":"PRIMARY_GUEST",
                             "version":"2026-03-01T10:00:00Z"}}
            """);
    JsonNode other =
        ingest(
            """
                {"sourceSystem":"opera-pms","externalKey":"agency-2",
                 "recordTimestamp":"2026-03-01T10:00:00Z",
                 "payload":{"email":"bruno@example.com",
                            "booking":{"agencyPhone":"+41445550000","propertyEmail":"desk@hotel.example"}},
                 "sourceObject":{"type":"reservation","id":"R6","role":"PRIMARY_GUEST",
                                 "version":"2026-03-01T10:00:00Z"}}
                """);

    // Two different people sharing a booking phone stay two people.
    assertThat(other.get("status").asString()).isEqualTo("CREATED_GUEST");
    assertThat(count("identifier")).isEqualTo(2); // one email each, no phone
  }

  @Test
  void theSameObjectIdUnderTwoSourceSystemsStaysTwoObjects() {
    register(TENANT_A_KEY, "channel-mgr");
    String v = "2026-03-01T10:00:00Z";
    ingest(person("R1", "PRIMARY_GUEST", null, v, "anna@example.com"));
    ingest(
        person("R1", "PRIMARY_GUEST", null, v, "anna@example.com")
            .replace("opera-pms", "channel-mgr")
            .replace("\"externalKey\":\"", "\"externalKey\":\"cm-"));

    assertThat(get("/api/v1/source-objects/opera-pms/reservation/R1").get("observations").size())
        .isEqualTo(1);
    assertThat(get("/api/v1/source-objects/channel-mgr/reservation/R1").get("observations").size())
        .isEqualTo(1);
  }

  // --- helpers ---

  private static String person(
      String objectId, String role, Integer position, String version, String email) {
    String pos = position == null ? "" : ",\"position\":" + position;
    return """
        {"sourceSystem":"opera-pms","externalKey":"%s:%s:%s",
         "recordTimestamp":"%s",
         "payload":{"email":"%s"},
         "sourceObject":{"type":"reservation","id":"%s","role":"%s","version":"%s"%s}}
        """
        .formatted(
            objectId, role + position, version, version, email, objectId, role, version, pos);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
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

  private JsonNode ingest(String body) {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json(response.getBody()).get("results").get(0);
  }

  private JsonNode get(String uri) {
    return json(api(TENANT_A_KEY).get().uri(uri).retrieve().toEntity(String.class).getBody());
  }
}
