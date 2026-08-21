package io.guestgraph.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.ObjectRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Table-driven, pure JVM: no Spring, no database. These are the rules that decide who currently
 * holds a booking, and they are the subtle part of the slice — the whole point of deriving on read
 * is that they can be pinned here on fixtures rather than only through Postgres.
 */
class AssociationDeriverTest {

  private static final UUID SYS = UUID.randomUUID();
  private static final UUID ANNA = UUID.randomUUID();
  private static final UUID BRUNO = UUID.randomUUID();
  private static final UUID YARA = UUID.randomUUID();
  private static final UUID ZOE = UUID.randomUUID();

  private static final Instant V1 = Instant.parse("2026-01-01T10:00:00Z");
  private static final Instant V2 = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant V3 = Instant.parse("2026-01-03T10:00:00Z");

  // --- US1: one entry per object and role, newest version's data ---

  @Test
  @DisplayName("three versions of one reservation collapse to one entry showing the newest")
  void collapsesVersionsToOneEntry() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V3, ANNA));

    List<Association> result = AssociationDeriver.derive(ANNA, obs, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().status()).isEqualTo(AssociationStatus.CURRENT);
    assertThat(result.getFirst().observationCount()).isEqualTo(3);
    assertThat(result.getFirst().currentObservation().objectVersion()).isEqualTo(V3);
  }

  @Test
  @DisplayName("one object, two roles on two guests: each sees its own entry")
  void separatesRolesAcrossGuests() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.ADDITIONAL_GUEST, 0, V1, BRUNO));

    assertThat(AssociationDeriver.derive(ANNA, obs, false))
        .singleElement()
        .extracting(Association::role)
        .isEqualTo(ObjectRole.PRIMARY_GUEST);
    assertThat(AssociationDeriver.derive(BRUNO, obs, false))
        .singleElement()
        .extracting(Association::role)
        .isEqualTo(ObjectRole.ADDITIONAL_GUEST);
  }

  @Test
  @DisplayName("the same guest twice in one role on one version yields one entry")
  void deduplicatesGuestTwiceInOneRole() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.ADDITIONAL_GUEST, 0, V1, ANNA),
            obs("R1", ObjectRole.ADDITIONAL_GUEST, 1, V1, ANNA));

    assertThat(AssociationDeriver.derive(ANNA, obs, false)).hasSize(1);
  }

  @Test
  @DisplayName("a guest with no business-object observations gets an empty list, not an error")
  void emptyWhenNothingToShow() {
    assertThat(AssociationDeriver.derive(ANNA, List.of(), true)).isEmpty();
  }

  // --- FR-004a: ordering ---

  @Test
  @DisplayName("ordered by business start, not by which was edited most recently")
  void ordersByBusinessStartNotEditRecency() {
    // Edited yesterday but stayed last year; and a booking arriving next week.
    ObjectObservation lastYear =
        obs("OLD", ObjectRole.PRIMARY_GUEST, null, V3, ANNA, ts("2025-06-01T00:00:00Z"), null);
    ObjectObservation nextWeek =
        obs("NEW", ObjectRole.PRIMARY_GUEST, null, V1, ANNA, ts("2026-09-01T00:00:00Z"), null);

    assertThat(AssociationDeriver.derive(ANNA, List.of(lastYear, nextWeek), false))
        .extracting(Association::objectId)
        .containsExactly("OLD", "NEW");
  }

  @Test
  @DisplayName("an object with no business dates falls back to its observation timestamp")
  void fallsBackToObservationTimestamp() {
    ObjectObservation dated =
        obs("DATED", ObjectRole.PRIMARY_GUEST, null, V1, ANNA, ts("2026-05-01T00:00:00Z"), null);
    ObjectObservation undated = obs("UNDATED", ObjectRole.PRIMARY_GUEST, null, V1, ANNA);

    assertThat(AssociationDeriver.derive(ANNA, List.of(dated, undated), false))
        .extracting(Association::objectId)
        .containsExactly("UNDATED", "DATED"); // V1 (Jan) precedes May
  }

  @Test
  @DisplayName("the ordering key is total, so it can serve as a keyset cursor")
  void orderingKeyIsTotal() {
    // Same ordering time — the object id must break the tie deterministically.
    ObjectObservation b = obs("B", ObjectRole.PRIMARY_GUEST, null, V1, ANNA);
    ObjectObservation a = obs("A", ObjectRole.PRIMARY_GUEST, null, V1, ANNA);

    assertThat(AssociationDeriver.derive(ANNA, List.of(b, a), false))
        .extracting(Association::objectId)
        .containsExactly("A", "B");
  }

  // --- US2: roster supersession ---

  @Test
  @DisplayName("reassignment: the newest roster decides, the previous holder ends")
  void reassignmentMovesTheAssociation() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, BRUNO));

    assertThat(AssociationDeriver.derive(BRUNO, obs, false))
        .singleElement()
        .extracting(Association::status)
        .isEqualTo(AssociationStatus.CURRENT);
    assertThat(AssociationDeriver.derive(ANNA, obs, false)).isEmpty();

    Association ended = AssociationDeriver.derive(ANNA, obs, true).getFirst();
    assertThat(ended.status()).isEqualTo(AssociationStatus.ENDED);
    assertThat(ended.successorGuestId()).isEqualTo(BRUNO);
  }

  @Test
  @DisplayName("removal from a shared role names no successor — nobody replaced them")
  void removalFabricatesNoTransfer() {
    // [Yara, Zoe] -> [Zoe]. Zoe did not take Yara's place; Yara was dropped.
    List<ObjectObservation> obs =
        List.of(
            obs("R2", ObjectRole.ADDITIONAL_GUEST, 0, V1, YARA),
            obs("R2", ObjectRole.ADDITIONAL_GUEST, 1, V1, ZOE),
            obs("R2", ObjectRole.ADDITIONAL_GUEST, 0, V2, ZOE));

    Association yara = AssociationDeriver.derive(YARA, obs, true).getFirst();
    assertThat(yara.status()).isEqualTo(AssociationStatus.ENDED);
    assertThat(yara.successorGuestId()).isNull();

    // Zoe is unchanged and inherits nothing — not even a position change reads as an event.
    assertThat(AssociationDeriver.derive(ZOE, obs, true))
        .singleElement()
        .extracting(Association::status)
        .isEqualTo(AssociationStatus.CURRENT);
  }

  @Test
  @DisplayName("a role that empties entirely names no successor")
  void emptiedRoleNamesNoSuccessor() {
    List<ObjectObservation> obs =
        List.of(
            obs("R2", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R2", ObjectRole.ADDITIONAL_GUEST, 0, V1, YARA),
            obs("R2", ObjectRole.PRIMARY_GUEST, null, V2, ANNA));

    assertThat(AssociationDeriver.derive(YARA, obs, true).getFirst().successorGuestId()).isNull();
  }

  @Test
  @DisplayName("a late-arriving older version never displaces the newer roster")
  void olderVersionNeverDisplaces() {
    // V3 seen first, V2 arrives afterwards — arrival order is irrelevant.
    List<ObjectObservation> obs =
        new ArrayList<>(
            List.of(
                obs("R1", ObjectRole.PRIMARY_GUEST, null, V3, BRUNO),
                obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, ANNA)));

    assertThat(AssociationDeriver.derive(BRUNO, obs, false)).hasSize(1);
    assertThat(AssociationDeriver.derive(ANNA, obs, false)).isEmpty();
  }

  @Test
  @DisplayName("a revert makes the original holder current again")
  void revertRestoresTheOriginalHolder() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, BRUNO),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V3, ANNA));

    assertThat(AssociationDeriver.derive(ANNA, obs, false)).hasSize(1);
    assertThat(AssociationDeriver.derive(BRUNO, obs, true).getFirst().status())
        .isEqualTo(AssociationStatus.ENDED);
  }

  @Test
  @DisplayName("observationCount spans every version and guest of that object and role")
  void observationCountSpansVersionsAndGuests() {
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, BRUNO),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V3, ANNA));

    assertThat(AssociationDeriver.derive(ANNA, obs, false).getFirst().observationCount())
        .isEqualTo(3);
  }

  // --- FR-009: merge and unmerge leave associations consistent ---

  @Test
  @DisplayName("after a merge the surviving guest holds the association exactly once")
  void mergeLeavesOneAssociation() {
    // Both observations of one object and role now resolve to the same guest.
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, ANNA));

    assertThat(AssociationDeriver.derive(ANNA, obs, true)).hasSize(1);
  }

  @Test
  @DisplayName("after an unmerge the association follows the link, not the former guest")
  void unmergeMovesTheAssociationWithTheLink() {
    // The current observation is linked to BRUNO now — it was detached onto him.
    List<ObjectObservation> obs =
        List.of(
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R1", ObjectRole.PRIMARY_GUEST, null, V2, BRUNO));

    assertThat(AssociationDeriver.derive(BRUNO, obs, false)).hasSize(1);
    assertThat(AssociationDeriver.derive(ANNA, obs, false)).isEmpty();
  }

  // --- FR-004a: the ordering key must be total, or paging drops entries ---

  @Test
  @DisplayName("two roles on one object at one time get distinct ordering keys")
  void oneObjectTwoRolesOrdersTotally() {
    // A booker who is also the primary guest: same object, same guest, same time.
    List<Association> result =
        AssociationDeriver.derive(
            ANNA,
            List.of(
                obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
                obs("R1", ObjectRole.BOOKER, null, V1, ANNA)),
            false);

    assertThat(result).hasSize(2);
    // Equal keys would make a page boundary between them drop one, silently.
    assertThat(result.get(0).orderingKey()).isNotEqualTo(result.get(1).orderingKey());
  }

  @Test
  @DisplayName("the same object id under two source systems gets distinct ordering keys")
  void sameObjectIdAcrossSourceSystemsOrdersTotally() {
    ObjectObservation opera = obs("R1", ObjectRole.PRIMARY_GUEST, null, V1, ANNA);
    ObjectObservation channel =
        new ObjectObservation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "channel-mgr",
            "reservation",
            "R1",
            ObjectRole.PRIMARY_GUEST,
            null,
            V1,
            null,
            null,
            ANNA,
            Map.of(),
            V1,
            false);

    List<Association> result = AssociationDeriver.derive(ANNA, List.of(opera, channel), false);
    assertThat(result).hasSize(2);
    assertThat(result.get(0).orderingKey()).isNotEqualTo(result.get(1).orderingKey());
  }

  // --- FR-010a: a partial roster derives from what is present ---

  @Test
  @DisplayName("a partially delivered version derives from what landed and invents nothing")
  void partialRosterInventsNothing() {
    // v2 of a three-person booking; only the primary guest has arrived so far.
    List<ObjectObservation> partial =
        List.of(
            obs("R3", ObjectRole.PRIMARY_GUEST, null, V1, ANNA),
            obs("R3", ObjectRole.ADDITIONAL_GUEST, 0, V1, YARA),
            obs("R3", ObjectRole.PRIMARY_GUEST, null, V2, ANNA));

    assertThat(AssociationDeriver.derive(ANNA, partial, false)).hasSize(1);
    // Yara is absent from the roster that has landed — reported as ended, not guessed at.
    assertThat(AssociationDeriver.derive(YARA, partial, false)).isEmpty();
    assertThat(AssociationDeriver.derive(YARA, partial, true))
        .singleElement()
        .extracting(Association::successorGuestId)
        .isNull();
  }

  // --- fixtures ---

  private static ObjectObservation obs(
      String objectId, ObjectRole role, Integer position, Instant version, UUID guestId) {
    return obs(objectId, role, position, version, guestId, null, null);
  }

  private static ObjectObservation obs(
      String objectId,
      ObjectRole role,
      Integer position,
      Instant version,
      UUID guestId,
      Instant businessStart,
      Instant businessEnd) {
    return new ObjectObservation(
        UUID.randomUUID(),
        SYS,
        "opera-pms",
        "reservation",
        objectId,
        role,
        position,
        version,
        businessStart,
        businessEnd,
        guestId,
        Map.of(),
        version,
        false);
  }

  private static Instant ts(String iso) {
    return Instant.parse(iso);
  }
}
