package io.guestgraph.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import io.guestgraph.domain.ReviewStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Table-driven engine scenarios (Constitution VI): record sets in → expected guest clusters +
 * expected MergeEvents out. Pure JVM — no Spring, no database.
 */
class ResolutionScenarioTest {

  @Test
  void zeroMatchesCreatesNewGuest() {
    EngineFixture fx = new EngineFixture();

    ResolutionOutcome outcome = fx.record("r1").email("anna@example.com").resolve();

    assertThat(outcome.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(outcome.guestId()).isNotNull();
    fx.assertClusters(Set.of(Set.of("r1")));
    List<MergeEvent> events = fx.graph.events();
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().kind()).isEqualTo(MergeEventKind.CREATE);
    assertThat(events.getFirst().matcherName()).isEqualTo(DeterministicMatcher.NAME);
    assertThat(events.getFirst().confidence()).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void singleMatchAttachesToExistingGuest() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome first = fx.record("r1").email("anna@example.com").resolve();

    ResolutionOutcome second =
        fx.record("r2").email("anna@example.com").phone("+41791234567").resolve();

    assertThat(second.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(second.guestId()).isEqualTo(first.guestId());
    fx.assertClusters(Set.of(Set.of("r1", "r2")));
    // The attach decision is auditable: matcher + confidence + the shared identifier as evidence.
    MergeEvent attach = fx.graph.events().getLast();
    assertThat(attach.kind()).isEqualTo(MergeEventKind.ATTACH);
    assertThat(attach.sourceRecordIds()).containsExactly(fx.recordId("r2"));
    assertThat(attach.evidence().toString()).contains("anna@example.com");
  }

  @Test
  void twoMatchesMergeGuestsTransitively() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome a = fx.record("a").email("shared-a@example.com").resolve();
    ResolutionOutcome b = fx.record("b").phone("+41791112233").resolve();
    assertThat(a.guestId()).isNotEqualTo(b.guestId());

    // c shares an identifier with each — transitive identity: a, b, c are one guest.
    ResolutionOutcome c =
        fx.record("c").email("shared-a@example.com").phone("+41791112233").resolve();

    assertThat(c.status()).isEqualTo(IngestStatus.MERGED);
    fx.assertClusters(Set.of(Set.of("a", "b", "c")));
    MergeEvent merge = fx.graph.events().getLast();
    assertThat(merge.kind()).isEqualTo(MergeEventKind.MERGE);
    assertThat(merge.guestId()).isEqualTo(c.guestId());
    assertThat(merge.absorbedGuestIds()).hasSize(1);
    assertThat(merge.confidence()).isEqualByComparingTo(BigDecimal.ONE);
    // The surviving guest carries the union of identifiers.
    assertThat(fx.graph.identifiersOf(c.guestId())).hasSize(2);
  }

  @Test
  void chainOfSharedIdentifiersKeepsMergingIntoOneGuest() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("x@example.com").resolve();
    fx.record("r2").email("x@example.com").phone("+41790000001").resolve();
    fx.record("r3").phone("+41790000001").loyaltyId("LOY-9").resolve();
    fx.record("r4").loyaltyId("LOY-9").resolve();

    fx.assertClusters(Set.of(Set.of("r1", "r2", "r3", "r4")));
  }

  @Test
  void recordWithoutIdentifiersCreatesIsolatedGuest() {
    EngineFixture fx = new EngineFixture();

    ResolutionOutcome outcome = fx.record("r1").field("firstName", "Anna").needsReview().resolve();

    assertThat(outcome.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    fx.assertClusters(Set.of(Set.of("r1")));
    assertThat(fx.graph.identifiersOf(outcome.guestId())).isEmpty();
  }

  @Test
  void distinctIdentifiersKeepGuestsSeparate() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("a@example.com").resolve();
    fx.record("r2").email("b@example.com").resolve();

    fx.assertClusters(Set.of(Set.of("r1"), Set.of("r2")));
  }

  @Test
  void profileIsRecomputedOnEveryLinkChange() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("a@example.com").field("firstName", "Anna").resolve();
    ResolutionOutcome second =
        fx.record("r2").email("a@example.com").field("lastName", "Muster").resolve();

    assertThat(fx.graph.profileOf(second.guestId()))
        .containsEntry("firstName", "Anna")
        .containsEntry("lastName", "Muster");
  }

  @Test
  void mergeEvidenceRecordsWhichIdentifierMatchedWhichGuest() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome a = fx.record("a").email("shared@example.com").resolve();
    ResolutionOutcome b = fx.record("b").phone("+41791112233").resolve();
    fx.record("c").email("shared@example.com").phone("+41791112233").resolve();

    MergeEvent merge = fx.graph.events().getLast();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches = (List<Map<String, Object>>) merge.evidence().get("matches");
    // One entry per contributing decision, each naming its guest and its identifier (FR-009).
    assertThat(matches).hasSize(2);
    assertThat(matches)
        .anySatisfy(
            m -> {
              assertThat(m.get("guestId")).isEqualTo(a.guestId().toString());
              assertThat(m.get("identifier").toString()).contains("shared@example.com");
            })
        .anySatisfy(
            m -> {
              assertThat(m.get("guestId")).isEqualTo(b.guestId().toString());
              assertThat(m.get("identifier").toString()).contains("+41791112233");
            });
  }

  // --- Review-threshold scenarios (FR-017, US4-AS1, research R9) ---

  @Test
  void overThresholdSharedIdentifierIsParkedForReviewNotMerged() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(2);
    ResolutionOutcome first = fx.record("r1").email("family@example.com").resolve();
    fx.record("r2").email("family@example.com").resolve(); // 2 records ≤ threshold: attaches

    // Third record sharing the same email: 3 > 2 → suspicious, parked instead of merged.
    ResolutionOutcome third = fx.record("r3").email("family@example.com").resolve();

    assertThat(third.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(third.guestId()).isNotEqualTo(first.guestId());
    fx.assertClusters(Set.of(Set.of("r1", "r2"), Set.of("r3")));
    assertThat(third.pendingReviewIds()).hasSize(1);
    MatchReview review = fx.graph.reviews().getFirst();
    assertThat(review.status()).isEqualTo(ReviewStatus.PENDING);
    assertThat(review.sourceRecordId()).isEqualTo(fx.recordId("r3"));
    assertThat(review.candidateGuestId()).isEqualTo(first.guestId());
    assertThat(review.reason()).contains("3 records").contains("threshold 2");
    assertThat(review.matcherName()).isEqualTo(DeterministicMatcher.NAME);
  }

  @Test
  void safeMatchProceedsWhileSuspiciousLinkGoesToReview() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(2);
    // Family email shared by two records on one guest; a distinct guest matched by phone.
    ResolutionOutcome family = fx.record("f1").email("family@example.com").resolve();
    fx.record("f2").email("family@example.com").resolve();
    ResolutionOutcome phoneGuest = fx.record("p1").phone("+41790000009").resolve();

    // Mixed record: safe phone match (2 ≤ 2) + suspicious email (3 > 2).
    ResolutionOutcome mixed =
        fx.record("mix").phone("+41790000009").email("family@example.com").resolve();

    // The safe portion proceeded; the suspicious link was parked — no partial merge state.
    assertThat(mixed.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(mixed.guestId()).isEqualTo(phoneGuest.guestId());
    fx.assertClusters(Set.of(Set.of("f1", "f2"), Set.of("p1", "mix")));
    assertThat(mixed.pendingReviewIds()).hasSize(1);
    assertThat(fx.graph.reviews().getFirst().candidateGuestId()).isEqualTo(family.guestId());
  }

  @Test
  void guestMatchedSafelyViaOneIdentifierGetsNoParallelReviewEntry() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(2);
    // One guest carries both the safe phone and the crowded email.
    ResolutionOutcome guest =
        fx.record("g1").phone("+41790000009").email("family@example.com").resolve();
    fx.record("g2").email("family@example.com").resolve();

    // New record matches the same guest via safe phone AND suspicious email:
    ResolutionOutcome next =
        fx.record("g3").phone("+41790000009").email("family@example.com").resolve();

    // Already attached through the safe identifier — a parallel review would be noise.
    assertThat(next.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(next.guestId()).isEqualTo(guest.guestId());
    assertThat(next.pendingReviewIds()).isEmpty();
    assertThat(fx.graph.reviews()).isEmpty();
  }

  @Test
  void mergeRepointsPendingReviewsToTheSurvivor() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    ResolutionOutcome candidate = fx.record("c1").email("crowded@example.com").resolve();
    // Suspicious match parks a review naming the candidate guest:
    ResolutionOutcome parked = fx.record("c2").email("crowded@example.com").resolve();
    assertThat(parked.pendingReviewIds()).hasSize(1);

    // Now the candidate guest is absorbed into another via a safe merge path:
    ResolutionOutcome other = fx.record("o1").phone("+41790000001").resolve();
    fx.graph.setReviewThreshold(100); // everything safe from here
    ResolutionOutcome merged =
        fx.record("bridge").email("crowded@example.com").phone("+41790000001").resolve();
    assertThat(merged.status()).isEqualTo(IngestStatus.MERGED);

    // The pending review followed its guest to the survivor — no dangling candidate id.
    Set<UUID> liveGuests = Set.of(merged.guestId());
    for (MatchReview review : fx.graph.reviews()) {
      if (review.status() == ReviewStatus.PENDING) {
        assertThat(liveGuests).contains(review.candidateGuestId());
      }
    }
    assertThat(other.guestId()).isNotNull();
  }

  // --- Unmerge & explain scenarios (US3, research R8) ---

  @Test
  void detachingOneOfThreeRecordsRegroupsCorrectly() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").field("firstName", "Anna").resolve();
    fx.record("r2").email("shared@example.com").resolve();
    ResolutionOutcome third = fx.record("r3").email("shared@example.com").resolve();
    UUID originalGuest = third.guestId();

    UnmergeOperation.UnmergeResult result =
        new UnmergeOperation(fx.graph, fx.engine)
            .unmerge(EngineFixture.TENANT, originalGuest, List.of(fx.recordId("r3")));

    // The wrong record sits on its own guest now; the exclusion kept it from rejoining.
    fx.assertClusters(Set.of(Set.of("r1", "r2"), Set.of("r3")));
    assertThat(result.remainingGuestId()).isEqualTo(originalGuest);
    UUID newGuest = result.detachedRecordToGuest().get(fx.recordId("r3"));
    assertThat(newGuest).isNotNull().isNotEqualTo(originalGuest);
    // Remaining guest's identifiers and profile were recomputed from what stayed.
    assertThat(fx.graph.identifiersOf(originalGuest)).hasSize(1);
    assertThat(fx.graph.profileOf(originalGuest)).containsEntry("firstName", "Anna");
    // The UNMERGE decision is in the audit trail with its exclusion (never silent).
    MergeEvent unmergeEvent =
        fx.graph.events().stream()
            .filter(e -> e.kind() == MergeEventKind.UNMERGE)
            .findFirst()
            .orElseThrow();
    assertThat(unmergeEvent.id()).isEqualTo(result.unmergeEventId());
    assertThat(unmergeEvent.guestId()).isEqualTo(originalGuest);
    assertThat(unmergeEvent.sourceRecordIds()).containsExactly(fx.recordId("r3"));
    assertThat(unmergeEvent.excludedGuestIds()).containsExactly(originalGuest);
    assertThat(unmergeEvent.matcherName()).isEqualTo(UnmergeOperation.MATCHER_NAME);
  }

  @Test
  void detachedRecordsRegroupAmongThemselves() {
    EngineFixture fx = new EngineFixture();
    fx.record("keep").email("keep@example.com").phone("+41790000005").resolve();
    fx.record("w1").phone("+41790000005").email("wrong@example.com").resolve();
    ResolutionOutcome all = fx.record("w2").email("wrong@example.com").resolve();

    new UnmergeOperation(fx.graph, fx.engine)
        .unmerge(
            EngineFixture.TENANT, all.guestId(), List.of(fx.recordId("w1"), fx.recordId("w2")));

    // Both detached records share wrong@example.com → they regroup as ONE new guest.
    fx.assertClusters(Set.of(Set.of("keep"), Set.of("w1", "w2")));
  }

  @Test
  void unmergeOnSingleRecordGuestFails() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome only = fx.record("r1").email("solo@example.com").resolve();

    assertThatThrownBy(
            () ->
                new UnmergeOperation(fx.graph, fx.engine)
                    .unmerge(EngineFixture.TENANT, only.guestId(), List.of(fx.recordId("r1"))))
        .isInstanceOf(InvalidUnmergeException.class)
        .hasMessageContaining("single source record");
  }

  @Test
  void unmergeOfRecordNotLinkedToGuestFails() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome guest = fx.record("r1").email("a@example.com").resolve();
    fx.record("r2").email("a@example.com").resolve();
    fx.record("other").email("b@example.com").resolve();

    assertThatThrownBy(
            () ->
                new UnmergeOperation(fx.graph, fx.engine)
                    .unmerge(EngineFixture.TENANT, guest.guestId(), List.of(fx.recordId("other"))))
        .isInstanceOf(InvalidUnmergeException.class)
        .hasMessageContaining("not linked");
  }

  @Test
  void detachingEverythingRemovesTheGuestAndCancelsItsPendingReviews() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("a@example.com").resolve();
    ResolutionOutcome guest = fx.record("r2").email("a@example.com").resolve();
    // Park a review naming this guest (threshold stays strict for the whole scenario):
    fx.graph.setReviewThreshold(1);
    fx.record("r3").email("a@example.com").resolve();
    assertThat(fx.graph.reviews()).isNotEmpty();

    UnmergeOperation.UnmergeResult result =
        new UnmergeOperation(fx.graph, fx.engine)
            .unmerge(
                EngineFixture.TENANT,
                guest.guestId(),
                List.of(fx.recordId("r1"), fx.recordId("r2")));

    assertThat(result.remainingGuestId()).isNull();
    // With the crowded email still over threshold, every replayed record parks rather
    // than re-merging — three separate guests, and no review points at the removed one.
    fx.assertClusters(Set.of(Set.of("r1"), Set.of("r2"), Set.of("r3")));
    assertThat(fx.graph.reviews())
        .filteredOn(review -> review.status() == ReviewStatus.PENDING)
        .isNotEmpty()
        .allSatisfy(review -> assertThat(review.candidateGuestId()).isNotEqualTo(guest.guestId()));
  }

  @Test
  void explainChainIncludesAbsorbedGuestsEventsOldestFirst() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome a = fx.record("a").email("x@example.com").resolve();
    ResolutionOutcome b = fx.record("b").phone("+41791112233").resolve();
    ResolutionOutcome merged =
        fx.record("c").email("x@example.com").phone("+41791112233").resolve();

    List<MergeEvent> chain =
        new ExplainOperation(fx.graph).explain(EngineFixture.TENANT, merged.guestId());

    // Both original CREATE events (one lives on an absorbed guest id) plus the MERGE.
    assertThat(chain).hasSize(3);
    assertThat(chain.getFirst().kind()).isEqualTo(MergeEventKind.CREATE);
    assertThat(chain.getLast().kind()).isEqualTo(MergeEventKind.MERGE);
    assertThat(chain.stream().map(MergeEvent::guestId)).contains(a.guestId(), b.guestId());
    // Every step is auditable: matcher, confidence, timestamp (FR-015/SC-003).
    for (MergeEvent event : chain) {
      assertThat(event.matcherName()).isNotBlank();
      assertThat(event.confidence()).isNotNull();
      assertThat(event.createdAt()).isNotNull();
    }
  }

  // --- Review decision scenarios (FR-018, US4-AS2/3) ---

  @Test
  void confirmingAReviewExecutesAndRecordsTheMerge() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    ResolutionOutcome candidate = fx.record("r1").email("family@example.com").resolve();
    ResolutionOutcome parked = fx.record("r2").email("family@example.com").resolve();
    UUID reviewId = parked.pendingReviewIds().getFirst();

    MatchReview decided =
        new ReviewDecisionOperation(fx.graph, fx.engine)
            .decide(EngineFixture.TENANT, reviewId, true);

    assertThat(decided.status()).isEqualTo(ReviewStatus.CONFIRMED);
    assertThat(decided.decidedAt()).isNotNull();
    fx.assertClusters(Set.of(Set.of("r1", "r2")));
    MergeEvent event =
        fx.graph.events().stream()
            .filter(e -> e.kind() == MergeEventKind.REVIEW_CONFIRM)
            .findFirst()
            .orElseThrow();
    // Recorded like any automatic merge: matcher name + confidence (US4-AS2).
    assertThat(event.id()).isEqualTo(decided.decisionEventId());
    assertThat(event.guestId()).isEqualTo(candidate.guestId());
    assertThat(event.matcherName()).isEqualTo(ReviewDecisionOperation.MATCHER_NAME);
    assertThat(event.confidence()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(event.sourceRecordIds()).containsExactly(fx.recordId("r2"));
    // Survivor's identifiers and profile were recomputed over both records.
    assertThat(fx.graph.identifiersOf(candidate.guestId())).isNotEmpty();
  }

  @Test
  void rejectingAReviewKeepsGuestsSeparateAndIsRecorded() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    fx.record("r1").email("family@example.com").resolve();
    ResolutionOutcome parked = fx.record("r2").email("family@example.com").resolve();
    UUID reviewId = parked.pendingReviewIds().getFirst();

    MatchReview decided =
        new ReviewDecisionOperation(fx.graph, fx.engine)
            .decide(EngineFixture.TENANT, reviewId, false);

    assertThat(decided.status()).isEqualTo(ReviewStatus.REJECTED);
    fx.assertClusters(Set.of(Set.of("r1"), Set.of("r2")));
    assertThat(fx.graph.events().stream().anyMatch(e -> e.kind() == MergeEventKind.REVIEW_REJECT))
        .isTrue();
  }

  @Test
  void secondDecisionOnAReviewFailsAndTheFirstStands() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    fx.record("r1").email("family@example.com").resolve();
    ResolutionOutcome parked = fx.record("r2").email("family@example.com").resolve();
    UUID reviewId = parked.pendingReviewIds().getFirst();
    ReviewDecisionOperation operation = new ReviewDecisionOperation(fx.graph, fx.engine);
    operation.decide(EngineFixture.TENANT, reviewId, false);

    assertThatThrownBy(() -> operation.decide(EngineFixture.TENANT, reviewId, true))
        .isInstanceOf(ReviewAlreadyDecidedException.class);
    // The rejection stands: records remain separate.
    fx.assertClusters(Set.of(Set.of("r1"), Set.of("r2")));
  }

  @Test
  void duplicateRecordIdsInUnmergeRequestAreDeduplicated() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").resolve();
    fx.record("r2").email("shared@example.com").resolve();
    ResolutionOutcome guest = fx.record("r3").email("shared@example.com").resolve();

    // The same id twice must behave exactly like once — not replay (and re-link) twice.
    UnmergeOperation.UnmergeResult result =
        new UnmergeOperation(fx.graph, fx.engine)
            .unmerge(
                EngineFixture.TENANT,
                guest.guestId(),
                List.of(fx.recordId("r3"), fx.recordId("r3")));

    fx.assertClusters(Set.of(Set.of("r1", "r2"), Set.of("r3")));
    assertThat(result.detachedRecordToGuest()).hasSize(1);
  }

  @Test
  void freshRecordSharingTheIdentifierAfterUnmergeRemergesWithATrace() {
    // Pins the v1 unmerge semantics (US3-AS3): the exclusion binds the *replay* of the
    // detached records; it is not a persistent do-not-merge rule. A brand-new record
    // carrying the shared identifier is fresh evidence and may re-merge the guests —
    // but never silently: the MERGE event makes the re-merge visible in explain.
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").resolve();
    ResolutionOutcome guest = fx.record("r2").email("shared@example.com").resolve();
    new UnmergeOperation(fx.graph, fx.engine)
        .unmerge(EngineFixture.TENANT, guest.guestId(), List.of(fx.recordId("r2")));
    fx.assertClusters(Set.of(Set.of("r1"), Set.of("r2")));

    ResolutionOutcome fresh = fx.record("r3").email("shared@example.com").resolve();

    assertThat(fresh.status()).isEqualTo(IngestStatus.MERGED);
    fx.assertClusters(Set.of(Set.of("r1", "r2", "r3")));
    List<MergeEventKind> kinds =
        new ExplainOperation(fx.graph)
            .explain(EngineFixture.TENANT, fresh.guestId()).stream().map(MergeEvent::kind).toList();
    // The full story is auditable: original merge chain, the unmerge, and the re-merge.
    assertThat(kinds).contains(MergeEventKind.UNMERGE, MergeEventKind.MERGE);
  }

  @Test
  void explainAfterUnmergeShowsTheUnmergeDecision() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").resolve();
    ResolutionOutcome guest = fx.record("r2").email("shared@example.com").resolve();

    new UnmergeOperation(fx.graph, fx.engine)
        .unmerge(EngineFixture.TENANT, guest.guestId(), List.of(fx.recordId("r2")));

    List<MergeEvent> chain =
        new ExplainOperation(fx.graph).explain(EngineFixture.TENANT, guest.guestId());
    assertThat(chain.stream().map(MergeEvent::kind)).contains(MergeEventKind.UNMERGE);
  }
}
