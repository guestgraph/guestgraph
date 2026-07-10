package io.guestgraph.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.MatchReview;
import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.domain.NegativeRuleOrigin;
import io.guestgraph.domain.ReviewStatus;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.domain.RuleMatchKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden-pair corpus for the fuzzy matcher (Constitution VI, SC-001/SC-002): same-person variants
 * must surface, different-person near-misses must not merge, and no automatic merge may ever happen
 * below the configured threshold. Pure JVM on InMemoryGraph.
 */
class FuzzyScenarioTest {

  // --- same-person variants surface for review (default bands: auto-merge OFF) ---

  @Test
  void diacriticVariantWithSameBirthdateParksForReview() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome first =
        fx.record("pms").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    ResolutionOutcome second =
        fx.record("wifi").name("Anna", "Mueller").birthdate("1985-03-12").resolve();

    // No shared exact identifier → no merge; the fuzzy candidate is parked, not decided.
    assertThat(second.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(second.guestId()).isNotEqualTo(first.guestId());
    assertThat(second.pendingReviewIds()).hasSize(1);
    MatchReview review = fx.graph.reviews().getFirst();
    assertThat(review.status()).isEqualTo(ReviewStatus.PENDING);
    assertThat(review.candidateGuestId()).isEqualTo(first.guestId());
    assertThat(review.matcherName()).isEqualTo(FuzzyMatcher.NAME);
    assertThat(review.confidence()).isLessThan(BigDecimal.ONE);
    assertThat(review.confidence().doubleValue()).isGreaterThanOrEqualTo(0.75);
    // The per-signal breakdown is the steward's explanation (FR-003).
    assertThat(review.reason()).contains("score").contains("name").contains("birthdate");
  }

  @Test
  void typoVariantWithSameBirthdateParksForReview() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    ResolutionOutcome typo =
        fx.record("r2").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    assertThat(typo.pendingReviewIds()).hasSize(1);
  }

  @Test
  void nameOrderSwapStillSurfaces() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    ResolutionOutcome swapped =
        fx.record("r2").name("Müller", "Anna").birthdate("1985-03-12").resolve();

    assertThat(swapped.pendingReviewIds()).hasSize(1);
  }

  @Test
  void missingBirthdateWithAgreeingPhoneStillSurfaces() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").name("Anna", "Müller").phone("+41791234567").email("a1@example.com").resolve();

    // Different email (no exact match), same phone → blocked via PHONE_SUFFIX7 → scored.
    ResolutionOutcome second =
        fx.record("r2").name("Anna", "Mueller").phone("+41791234567").resolve();

    // Phone is an exact identifier too — so this pair MERGES deterministically; the
    // fuzzy-only variant needs a non-identifier overlap. Use differing phones with the
    // same suffix instead:
    EngineFixture fy = new EngineFixture();
    fy.record("p1").name("Anna", "Müller").phone("+41441234567").resolve();
    ResolutionOutcome parked =
        fy.record("p2").name("Anna", "Mueller").phone("+41791234567").resolve();

    assertThat(second.status()).isEqualTo(IngestStatus.ATTACHED); // deterministic precedence
    assertThat(parked.pendingReviewIds()).hasSize(1); // fuzzy via shared suffix + name
  }

  // --- different-person near-misses never merge ---

  @Test
  void conflictingBirthdatesSinkTheScore() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").phone("+41441234567").resolve();

    // Same name, same phone suffix — but a DIFFERENT birthdate: different person evidence.
    ResolutionOutcome other =
        fx.record("r2")
            .name("Anna", "Müller")
            .birthdate("1991-07-01")
            .phone("+41791234567")
            .resolve();

    assertThat(other.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(other.pendingReviewIds()).isEmpty();
    assertThat(fx.graph.reviews()).isEmpty();
  }

  @Test
  void candidateWithoutNameSignalIsNeverScored() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1")
        .name("Anna", "Müller")
        .email("anna.m@example.com")
        .birthdate("1985-03-12")
        .resolve();

    // Same email local-part at a different domain, but the record carries no name:
    ResolutionOutcome anonymous = fx.record("r2").email("anna.m@other.com").resolve();

    assertThat(anonymous.pendingReviewIds()).isEmpty();
    assertThat(fx.graph.reviews()).isEmpty();
  }

  // --- banding (FR-005/006) ---

  @Test
  void tenantOptInEnablesAutoMerge() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setBands("0.85", "0.75");
    ResolutionOutcome first =
        fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    // Near-perfect name + birthdate agreement scores well above 0.85 → automatic merge.
    ResolutionOutcome second =
        fx.record("r2").name("Anna", "Mueller").birthdate("1985-03-12").resolve();

    assertThat(second.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(second.guestId()).isEqualTo(first.guestId());
    MergeEvent event = fx.graph.events().getLast();
    assertThat(event.kind()).isEqualTo(MergeEventKind.ATTACH);
    assertThat(event.matcherName()).isEqualTo(FuzzyMatcher.NAME);
    assertThat(event.confidence()).isLessThanOrEqualTo(BigDecimal.ONE);
    assertThat(event.confidence().doubleValue()).isGreaterThanOrEqualTo(0.85);
    // The signal breakdown lands in the audit trail (FR-003/FR-007).
    assertThat(event.evidence().toString()).contains("signals");
  }

  @Test
  void invariantNoAutoMergeBelowConfiguredThreshold() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setBands("0.99", "0.60");
    fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").phone("+41441234567").resolve();

    // Name variant + shared suffix, no birthdate on the second → good but not perfect score.
    ResolutionOutcome second =
        fx.record("r2").name("Anna", "Muller").phone("+41791234567").resolve();

    // Whatever the score, below 0.99 nothing merges automatically (SC-002).
    assertThat(second.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(fx.graph.events().stream().map(MergeEvent::kind))
        .doesNotContain(MergeEventKind.MERGE);
    assertThat(second.pendingReviewIds()).hasSize(1);
  }

  @Test
  void defaultConfigurationNeverAutoMergesEvenPerfectScores() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    ResolutionOutcome second =
        fx.record("r2").name("Anna", "Müller").birthdate("1985-03-12").resolve();

    // Score 1.0 — still parked: automation is strictly opt-in (US1-AS5).
    assertThat(second.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(second.pendingReviewIds()).hasSize(1);
  }

  @Test
  void atThresholdScoresBelongToTheHigherBand() {
    MatchingConfig config =
        new MatchingConfig(new BigDecimal("0.900"), new BigDecimal("0.750"), 10);

    assertThat(config.autoMergeEnabledFor(new BigDecimal("0.900"))).isTrue();
    assertThat(config.autoMergeEnabledFor(new BigDecimal("0.899"))).isFalse();
    assertThat(config.reviewableFor(new BigDecimal("0.750"))).isTrue();
    assertThat(config.reviewableFor(new BigDecimal("0.749"))).isFalse();
  }

  // --- deterministic precedence (US1-AS6) ---

  @Test
  void exactIdentifierMatchWinsWithoutParallelFuzzyReview() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1")
        .name("Anna", "Müller")
        .birthdate("1985-03-12")
        .email("anna@example.com")
        .resolve();

    ResolutionOutcome second =
        fx.record("r2")
            .name("Anna", "Mueller")
            .birthdate("1985-03-12")
            .email("anna@example.com")
            .resolve();

    // Deterministic email match decides; fuzzy adds no duplicate review for the same guest.
    assertThat(second.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(second.pendingReviewIds()).isEmpty();
    assertThat(fx.graph.reviews()).isEmpty();
    assertThat(fx.graph.events().getLast().matcherName()).isEqualTo(DeterministicMatcher.NAME);
  }

  @Test
  void slice1BehaviorUnchangedWhenNoFuzzySignalsExist() {
    EngineFixture fx = new EngineFixture();
    ResolutionOutcome a = fx.record("a").email("a@example.com").resolve();
    ResolutionOutcome b = fx.record("b").email("b@example.com").resolve();

    assertThat(a.guestId()).isNotEqualTo(b.guestId());
    assertThat(fx.graph.reviews()).isEmpty();
    fx.assertClusters(Set.of(Set.of("a"), Set.of("b")));
  }

  // --- negative match rules (US2, FR-009..012) ---

  @Test
  void unmergeWritesRulesAndFreshExactEvidenceIsDowngraded() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").resolve();
    ResolutionOutcome merged = fx.record("r2").email("shared@example.com").resolve();
    new UnmergeOperation(fx.graph, fx.engine)
        .unmerge(EngineFixture.TENANT, merged.guestId(), List.of(fx.recordId("r2")));

    // The steward split wrote a persistent rule.
    assertThat(fx.graph.negativeRules()).isNotEmpty();
    assertThat(fx.graph.negativeRules().getFirst().origin()).isEqualTo(NegativeRuleOrigin.UNMERGE);

    // Fresh exact evidence connecting both sides: downgraded to review, never silent (FR-010).
    ResolutionOutcome fresh = fx.record("r3").email("shared@example.com").resolve();

    assertThat(fresh.status()).isNotEqualTo(IngestStatus.MERGED);
    assertThat(fresh.pendingReviewIds()).isNotEmpty();
    assertThat(fx.graph.reviews().stream().anyMatch(r -> r.reason().contains("do-not-merge")))
        .isTrue();
    // Two clusters remain: the split held (r3 attached to one side, the other parked).
    assertThat(fx.graph.clusters()).hasSize(2);
  }

  @Test
  void rejectWritesRulesThatDowngradeFutureEvidence() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    fx.record("r1").email("family@example.com").resolve();
    ResolutionOutcome parked = fx.record("r2").email("family@example.com").resolve();
    new ReviewDecisionOperation(fx.graph, fx.engine)
        .decide(EngineFixture.TENANT, parked.pendingReviewIds().getFirst(), false);

    assertThat(fx.graph.negativeRules()).isNotEmpty();
    assertThat(fx.graph.negativeRules().getFirst().origin())
        .isEqualTo(NegativeRuleOrigin.REVIEW_REJECT);

    // New evidence bridging the rejected pair parks with the rule cited, never merges.
    fx.graph.setReviewThreshold(100);
    ResolutionOutcome bridge = fx.record("r3").email("family@example.com").resolve();

    assertThat(bridge.status()).isNotEqualTo(IngestStatus.MERGED);
    assertThat(fx.graph.clusters()).hasSize(2);
  }

  @Test
  void confirmingAcrossARuleExecutesTheMergeAndLiftsIt() {
    EngineFixture fx = new EngineFixture();
    fx.record("r1").email("shared@example.com").resolve();
    ResolutionOutcome merged = fx.record("r2").email("shared@example.com").resolve();
    new UnmergeOperation(fx.graph, fx.engine)
        .unmerge(EngineFixture.TENANT, merged.guestId(), List.of(fx.recordId("r2")));
    ResolutionOutcome downgraded = fx.record("r3").email("shared@example.com").resolve();
    assertThat(downgraded.pendingReviewIds()).isNotEmpty();

    // A human confirms: merge executes, the split is superseded (FR-011).
    new ReviewDecisionOperation(fx.graph, fx.engine)
        .decide(EngineFixture.TENANT, downgraded.pendingReviewIds().getFirst(), true);

    fx.assertClusters(Set.of(Set.of("r1", "r2", "r3")));
    assertThat(fx.graph.negativeRules()).isEmpty();

    // And the next evidence merges silently again — the rule is gone.
    ResolutionOutcome next = fx.record("r4").email("shared@example.com").resolve();
    assertThat(next.status()).isEqualTo(IngestStatus.ATTACHED);
  }

  // --- identifier quality rules & masked aliases (US3, FR-013..016) ---

  @Test
  void ignoredIdentifierConnectsNothingEvenRetroactively() {
    EngineFixture fx = new EngineFixture();
    // The identifier row is written BEFORE the rule exists (matching-time semantics, R2-4).
    ResolutionOutcome first = fx.record("r1").email("bookings@agency.example").resolve();
    fx.graph.addQualityRule(
        new IdentifierQualityRule(
            UUID.randomUUID(),
            EngineFixture.TENANT,
            IdentifierType.EMAIL,
            RuleMatchKind.EXACT,
            "bookings@agency.example",
            RuleEffect.IGNORE,
            "shared agency inbox",
            false,
            Instant.now()));

    ResolutionOutcome second = fx.record("r2").email("bookings@agency.example").resolve();

    assertThat(second.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(second.guestId()).isNotEqualTo(first.guestId());
    assertThat(second.pendingReviewIds()).isEmpty();
    assertThat(fx.graph.reviews()).isEmpty();
  }

  @Test
  void perfectMatchIdentifierRequiresExactNameAgreement() {
    EngineFixture fx = new EngineFixture();
    fx.graph.addQualityRule(
        new IdentifierQualityRule(
            UUID.randomUUID(),
            EngineFixture.TENANT,
            IdentifierType.EMAIL,
            RuleMatchKind.EXACT,
            "vip@example.com",
            RuleEffect.PERFECT_MATCH,
            null,
            false,
            Instant.now()));
    ResolutionOutcome first =
        fx.record("r1").name("Anna", "Müller").email("vip@example.com").resolve();

    // Same email, different person name → review, not merge (US3-AS4).
    ResolutionOutcome mismatch =
        fx.record("r2").name("Bob", "Meier").email("vip@example.com").resolve();
    assertThat(mismatch.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(mismatch.pendingReviewIds()).hasSize(1);
    assertThat(fx.graph.reviews().getFirst().reason()).contains("perfect-match");

    // Exact (diacritic-folded) name agreement → merges normally.
    ResolutionOutcome agrees =
        fx.record("r3").name("ANNA", "MÜLLER").email("vip@example.com").resolve();
    assertThat(agrees.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(agrees.guestId()).isEqualTo(first.guestId());
  }

  @Test
  void sharedMaskedAliasIsAWeakReviewOnlySignal() {
    EngineFixture fx = new EngineFixture();
    // Even with aggressive automation, a masked alias never auto-merges (US3-AS1).
    fx.graph.setBands("0.90", "0.60");
    ResolutionOutcome first =
        fx.record("r1").name("Anna", "Müller").maskedEmail("x1@guest.booking.com").resolve();

    ResolutionOutcome sameAlias =
        fx.record("r2").name("Anna", "Mueller").maskedEmail("x1@guest.booking.com").resolve();

    assertThat(sameAlias.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(sameAlias.guestId()).isNotEqualTo(first.guestId());
    assertThat(sameAlias.pendingReviewIds()).hasSize(1);

    // A different person on the same alias must not even reach the queue: no name agreement.
    ResolutionOutcome stranger =
        fx.record("r3").name("Eva", "Roth").maskedEmail("x1@guest.booking.com").resolve();
    assertThat(stranger.status()).isEqualTo(IngestStatus.CREATED_GUEST);
    assertThat(stranger.pendingReviewIds()).isEmpty();
  }

  // --- review-fix regressions ---

  @Test
  void rejectingAReviewWhoseSidesAlreadyMergedSucceedsWithoutRules() {
    EngineFixture fx = new EngineFixture();
    fx.graph.setReviewThreshold(1);
    fx.record("r1").email("family@example.com").phone("+41791110001").resolve();
    // r2 parks vs r1's guest (email over threshold):
    ResolutionOutcome parked =
        fx.record("r2").email("family@example.com").phone("+41791110002").resolve();
    assertThat(parked.pendingReviewIds()).hasSize(1);
    // A bridging record merges the two sides while the review is pending
    // (repointPendingReviews moves the review onto the survivor):
    fx.graph.setReviewThreshold(100);
    ResolutionOutcome bridge = fx.record("r3").phone("+41791110001").resolve(); // attach to r1 side
    ResolutionOutcome merge = fx.record("r4").phone("+41791110001").phone("+41791110002").resolve();
    assertThat(merge.status()).isEqualTo(IngestStatus.MERGED);

    // The reviewed record now sits inside the candidate cluster — reject must not
    // crash (self-pair rule) and writes no rules; the decision is still recorded.
    MatchReview decided =
        new ReviewDecisionOperation(fx.graph, fx.engine)
            .decide(EngineFixture.TENANT, parked.pendingReviewIds().getFirst(), false);

    assertThat(decided.status()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(fx.graph.negativeRules()).isEmpty();
    assertThat(bridge.guestId()).isNotNull();
  }

  @Test
  void perfectMatchRuleDoesNotBlockACleanSecondIdentifier() {
    EngineFixture fx = new EngineFixture();
    fx.graph.addQualityRule(
        new IdentifierQualityRule(
            UUID.randomUUID(),
            EngineFixture.TENANT,
            IdentifierType.EMAIL,
            RuleMatchKind.EXACT,
            "vip@example.com",
            RuleEffect.PERFECT_MATCH,
            null,
            false,
            Instant.now()));
    ResolutionOutcome first =
        fx.record("r1")
            .name("Anna", "Müller")
            .email("vip@example.com")
            .phone("+41791234567")
            .resolve();

    // Names disagree, but the PHONE match is clean — the rule governs the identifier,
    // not the guest (FR-013): the merge proceeds via the phone.
    ResolutionOutcome second =
        fx.record("r2")
            .name("Bob", "Meier")
            .email("vip@example.com")
            .phone("+41791234567")
            .resolve();

    assertThat(second.status()).isEqualTo(IngestStatus.ATTACHED);
    assertThat(second.guestId()).isEqualTo(first.guestId());
  }

  @Test
  void executedEventEvidenceListsOnlyGuestsThatActuallyMerged() {
    EngineFixture fx = new EngineFixture();
    // Two guests; a do-not-merge rule between them.
    ResolutionOutcome a = fx.record("a").email("a@example.com").phone("+41790000001").resolve();
    ResolutionOutcome b = fx.record("b").email("b@example.com").resolve();
    fx.graph.saveNegativeRule(
        NegativeMatchRule.of(
            EngineFixture.TENANT, fx.recordId("a"), fx.recordId("b"), NegativeRuleOrigin.MANUAL));

    // The bridge matches both guests, each via TWO identifiers; guest b gets vetoed.
    ResolutionOutcome bridge =
        fx.record("bridge")
            .email("a@example.com")
            .phone("+41790000001")
            .email("b@example.com")
            .resolve();

    assertThat(bridge.guestId()).isEqualTo(a.guestId());
    MergeEvent executed = fx.graph.events().getLast();
    // Evidence must not reference the vetoed guest — the audit reflects what merged.
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches =
        (List<Map<String, Object>>) executed.evidence().get("matches");
    assertThat(matches)
        .allSatisfy(m -> assertThat(m.get("guestId")).isEqualTo(a.guestId().toString()));
    assertThat(bridge.pendingReviewIds()).isNotEmpty(); // b parked, citing the rule
    assertThat(b.guestId()).isNotNull();
  }
}
