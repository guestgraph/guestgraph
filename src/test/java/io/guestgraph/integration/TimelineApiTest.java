package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec US1 and US2 acceptance scenarios over the API, plus the read-model invariants. */
class TimelineApiTest extends PostgresIntegrationTest {

  @BeforeEach
  void registerSourceSystems() {
    register(TENANT_A_KEY, "opera-pms");
    register(TENANT_A_KEY, "channel-mgr");
    register(TENANT_A_KEY, "loyalty-db");
  }

  // --- US1 ---

  @Test
  void aReservationEditedThreeTimesAppearsOnceShowingTheNewest() {
    String guestId = null;
    for (String v : new String[] {"10:00", "11:00", "12:00"}) {
      guestId =
          reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T" + v + ":00Z", "anna@example.com")
              .get("guestId")
              .asString();
    }

    JsonNode items = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline").get("items");

    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).get("objectId").asString()).isEqualTo("R1");
    assertThat(items.get(0).get("role").asString()).isEqualTo("PRIMARY_GUEST");
    assertThat(items.get(0).get("status").asString()).isEqualTo("CURRENT");
    assertThat(items.get(0).get("observationCount").asInt()).isEqualTo(3);
    assertThat(items.get(0).get("currentObservation").get("objectVersion").asString())
        .startsWith("2026-03-01T12:00");
  }

  @Test
  void oneReservationTwoPersonsAppearsOnBothTimelinesWithItsOwnRole() {
    String anna =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();
    String bruno =
        reservation("R1", "ADDITIONAL_GUEST", 0, "2026-03-01T10:00:00Z", "bruno@example.com")
            .get("guestId")
            .asString();

    assertThat(role(anna, "R1")).isEqualTo("PRIMARY_GUEST");
    assertThat(role(bruno, "R1")).isEqualTo("ADDITIONAL_GUEST");
  }

  @Test
  void recordsWithoutObjectIdentityAreNotAssociationsButRemainInTheRecordsList() {
    String guestId =
        ingest(
                TENANT_A_KEY,
                """
                    {"sourceSystem":"loyalty-db","externalKey":"l-1",
                     "payload":{"email":"anna@example.com","loyaltyId":"GOLD-1"}}
                    """)
            .get("guestId")
            .asString();

    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline").get("items").size())
        .isZero();
    // FR-005: the slice-1 contract is untouched.
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/records").get("records").size())
        .isEqualTo(1);
  }

  @Test
  void timelineOfAnUnknownGuestIs404NotAnEmptyPage() {
    ResponseEntity<String> response =
        api(TENANT_A_KEY)
            .get()
            .uri("/api/v1/guests/" + UUID.randomUUID() + "/timeline")
            .retrieve()
            .onStatus(status -> true, (req, res) -> {})
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void orderedByStayDatesNotByEditRecency() {
    // Booked long ago for next year, edited today; and a stay that already happened.
    String guestId =
        reservationDated(
                "FUTURE", "2026-03-01T10:00:00Z", "2027-01-01T14:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();
    reservationDated("PAST", "2026-01-01T10:00:00Z", "2025-01-01T14:00:00Z", "anna@example.com");

    JsonNode items = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline").get("items");
    assertThat(items.get(0).get("objectId").asString()).isEqualTo("PAST");
    assertThat(items.get(1).get("objectId").asString()).isEqualTo("FUTURE");
  }

  // --- US2 ---

  @Test
  void reassignmentMovesTheAssociationAndTheFormerHolderCanStillSeeIt() {
    String anna =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();
    String bruno =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T11:00:00Z", "bruno@example.com")
            .get("guestId")
            .asString();

    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + bruno + "/timeline").get("items").size())
        .isEqualTo(1);
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + anna + "/timeline").get("items").size())
        .isZero();

    JsonNode ended =
        get(TENANT_A_KEY, "/api/v1/guests/" + anna + "/timeline?includePast=true").get("items");
    assertThat(ended.get(0).get("status").asString()).isEqualTo("ENDED");
    assertThat(ended.get(0).get("successorGuestId").asString()).isEqualTo(bruno);

    // Constitution II: her original observation is untouched.
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + anna + "/records").get("records").size())
        .isEqualTo(1);
  }

  @Test
  void aDroppedGuestNamesNoSuccessorAndTheRemainingGuestInheritsNothing() {
    // [Yara, Zoe] -> [Zoe]. The case positional slot-tracking gets wrong.
    String yara =
        reservation("R2", "ADDITIONAL_GUEST", 0, "2026-03-01T10:00:00Z", "yara@example.com")
            .get("guestId")
            .asString();
    String zoe =
        reservation("R2", "ADDITIONAL_GUEST", 1, "2026-03-01T10:00:00Z", "zoe@example.com")
            .get("guestId")
            .asString();
    reservation("R2", "ADDITIONAL_GUEST", 0, "2026-03-01T11:00:00Z", "zoe@example.com");

    JsonNode ended =
        get(TENANT_A_KEY, "/api/v1/guests/" + yara + "/timeline?includePast=true").get("items");
    assertThat(ended.get(0).get("status").asString()).isEqualTo("ENDED");
    assertThat(ended.get(0).get("successorGuestId").isNull()).isTrue();

    assertThat(status(zoe, "R2")).isEqualTo("CURRENT");
  }

  @Test
  void aLateArrivingOlderVersionNeverDisplacesTheNewerRoster() {
    String bruno =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T12:00:00Z", "bruno@example.com")
            .get("guestId")
            .asString();
    String anna =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();

    assertThat(status(bruno, "R1")).isEqualTo("CURRENT");
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + anna + "/timeline").get("items").size())
        .isZero();
  }

  @Test
  void theSameObjectIdInTwoSourceSystemsStaysTwoObjects() {
    String guestId =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();
    ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"channel-mgr","externalKey":"cm-1",
             "recordTimestamp":"2026-03-01T10:00:00Z",
             "payload":{"email":"anna@example.com"},
             "sourceObject":{"type":"reservation","id":"R1","role":"PRIMARY_GUEST",
                             "version":"2026-03-01T10:00:00Z"}}
            """);

    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline").get("items").size())
        .isEqualTo(2);
  }

  // --- read-model invariants ---

  @Test
  void aPartiallyDeliveredVersionConvergesWithoutAnyConvergenceStep() {
    // v1: two guests on the booking.
    String anna =
        reservation("R3", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();
    String yara =
        reservation("R3", "ADDITIONAL_GUEST", 0, "2026-03-01T10:00:00Z", "yara@example.com")
            .get("guestId")
            .asString();

    // v2 arrives one record at a time; mid-delivery the roster is legitimately partial.
    reservation("R3", "PRIMARY_GUEST", null, "2026-03-01T11:00:00Z", "anna@example.com");
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + yara + "/timeline").get("items").size())
        .isZero();

    reservation("R3", "ADDITIONAL_GUEST", 0, "2026-03-01T11:00:00Z", "yara@example.com");
    assertThat(status(yara, "R3")).isEqualTo("CURRENT");
    assertThat(status(anna, "R3")).isEqualTo("CURRENT");
  }

  @Test
  void readingTheTimelineWritesNothing() {
    String guestId =
        reservation("R1", "PRIMARY_GUEST", null, "2026-03-01T10:00:00Z", "anna@example.com")
            .get("guestId")
            .asString();

    long events = count("merge_event");
    long links = count("resolution_link");
    long objects = count("record_object");

    get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline?includePast=true");
    get(TENANT_A_KEY, "/api/v1/source-objects/opera-pms/reservation/R1");

    // FR-010: no association state exists that could not be recomputed from the records.
    assertThat(count("merge_event")).isEqualTo(events);
    assertThat(count("resolution_link")).isEqualTo(links);
    assertThat(count("record_object")).isEqualTo(objects);
  }

  @Test
  void pagingWalksEveryAssociationExactlyOnce() {
    String guestId = null;
    for (int i = 0; i < 7; i++) {
      guestId =
          reservationDated(
                  "R" + i,
                  "2026-03-01T10:00:00Z",
                  Instant.parse("2026-04-01T00:00:00Z").plus(Duration.ofDays(i)).toString(),
                  "anna@example.com")
              .get("guestId")
              .asString();
    }

    List<String> seen = new ArrayList<>();
    String cursor = null;
    for (int guard = 0; guard < 10; guard++) {
      String uri = "/api/v1/guests/" + guestId + "/timeline?limit=3";
      JsonNode page = get(TENANT_A_KEY, cursor == null ? uri : uri + "&cursor=" + cursor);
      page.get("items").forEach(i -> seen.add(i.get("objectId").asString()));
      if (page.get("nextCursor").isNull()) {
        break;
      }
      cursor = page.get("nextCursor").asString();
    }

    assertThat(seen).containsExactly("R0", "R1", "R2", "R3", "R4", "R5", "R6");
  }

  // --- FR-024 ---

  @Test
  void anUnusableObjectVersionIsStoredAndFlaggedButJoinsNoRoster() {
    JsonNode result =
        ingest(
            TENANT_A_KEY,
            """
                {"sourceSystem":"opera-pms","externalKey":"bad-1",
                 "payload":{"email":"anna@example.com"},
                 "sourceObject":{"type":"reservation","id":"R9","role":"PRIMARY_GUEST",
                                 "version":"not-an-instant"}}
                """);

    assertThat(result.get("needsReview").asBoolean()).isTrue();
    String guestId = result.get("guestId").asString();
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline").get("items").size())
        .isZero();
    // Constitution III: stored, never dropped.
    assertThat(get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/records").get("records").size())
        .isEqualTo(1);
  }

  @Test
  void firstPageStaysWithinTheLatencyBudgetForABusyGuest() {
    // SC-006: 500 associations on one guest. Seeded directly rather than through ingest —
    // ingest is O(records-on-guest) per record (the rebuildGuest scale lever in
    // docs/roadmap-notes.md), and this criterion is about the read, not ingest throughput.
    // 500 objects x 5 versions = ~2,500 observations, the sizing research R1 argues from.
    // Cost is per-observation, not per-association.
    UUID guestId = seedBusyGuest(500, 5);

    long startNanos = System.nanoTime();
    JsonNode page = get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline?limit=50");
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    assertThat(page.get("items").size()).isEqualTo(50);
    assertThat(page.get("nextCursor").isNull()).isFalse();
    assertThat(elapsedMillis).isLessThan(1000);
  }

  private UUID seedBusyGuest(int associations, int versionsEach) {
    UUID guestId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID sourceSystemId =
        jdbc.sql("SELECT id FROM source_system WHERE tenant_id = :t AND code = 'opera-pms'")
            .param("t", TENANT_A)
            .query(UUID.class)
            .single();
    jdbc.sql("INSERT INTO guest (id, tenant_id) VALUES (:id, :t)")
        .param("id", guestId)
        .param("t", TENANT_A)
        .update();
    jdbc.sql(
            """
                INSERT INTO merge_event (id, tenant_id, kind, guest_id, matcher_name, confidence)
                VALUES (:id, :t, 'CREATE', :g, 'seed', 1.0)
                """)
        .param("id", eventId)
        .param("t", TENANT_A)
        .param("g", guestId)
        .update();
    jdbc.sql(
            """
                WITH n AS (
                       SELECT i, v FROM generate_series(1, :count) AS i,
                                        generate_series(1, :versions) AS v),
                     r AS (
                       INSERT INTO source_record (id, tenant_id, source_system_id, external_key,
                                                  payload, extracted, record_timestamp)
                       SELECT gen_random_uuid(), :t, :ss, 'seed-' || i || '-' || v,
                              '{}'::jsonb, '{}'::jsonb,
                              timestamptz '2026-03-01T10:00:00Z' + (v * interval '1 hour')
                       FROM n RETURNING id, external_key),
                     l AS (
                       INSERT INTO resolution_link (id, tenant_id, source_record_id, guest_id,
                                                    created_by_event_id)
                       SELECT gen_random_uuid(), :t, r.id, :g, :e FROM r)
                INSERT INTO record_object (id, tenant_id, source_record_id, source_system_id,
                                           object_type, object_id, object_role, object_version,
                                           business_start)
                SELECT gen_random_uuid(), :t, r.id, :ss, 'reservation',
                       split_part(r.external_key, '-', 2),
                       'PRIMARY_GUEST',
                       timestamptz '2026-03-01T10:00:00Z'
                           + (split_part(r.external_key, '-', 3)::int * interval '1 hour'),
                       timestamptz '2026-04-01T00:00:00Z'
                           + (split_part(r.external_key, '-', 2)::int * interval '1 day')
                FROM r
                """)
        .param("count", associations)
        .param("versions", versionsEach)
        .param("t", TENANT_A)
        .param("ss", sourceSystemId)
        .param("g", guestId)
        .param("e", eventId)
        .update();
    return guestId;
  }

  // --- helpers ---

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private String role(String guestId, String objectId) {
    return field(guestId, objectId, "role");
  }

  private String status(String guestId, String objectId) {
    return field(guestId, objectId, "status");
  }

  private String field(String guestId, String objectId, String field) {
    for (JsonNode item :
        get(TENANT_A_KEY, "/api/v1/guests/" + guestId + "/timeline?includePast=true")
            .get("items")) {
      if (item.get("objectId").asString().equals(objectId)) {
        return item.get(field).asString();
      }
    }
    return null;
  }

  private JsonNode reservation(
      String objectId, String role, Integer position, String version, String email) {
    String pos = position == null ? "" : ",\"position\":" + position;
    return ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"opera-pms","externalKey":"%s:%s:%s",
             "recordTimestamp":"%s",
             "payload":{"email":"%s"},
             "sourceObject":{"type":"reservation","id":"%s","role":"%s","version":"%s"%s}}
            """
            .formatted(
                objectId, role + position, version, version, email, objectId, role, version, pos));
  }

  private JsonNode reservationDated(
      String objectId, String version, String businessStart, String email) {
    return ingest(
        TENANT_A_KEY,
        """
            {"sourceSystem":"opera-pms","externalKey":"%s:%s",
             "recordTimestamp":"%s",
             "payload":{"email":"%s"},
             "sourceObject":{"type":"reservation","id":"%s","role":"PRIMARY_GUEST",
                             "version":"%s","businessStart":"%s"}}
            """
            .formatted(objectId, version, version, email, objectId, version, businessStart));
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
    return json(api(apiKey).get().uri(uri).retrieve().toEntity(String.class).getBody());
  }
}
