package io.guestgraph.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.Actor;
import io.guestgraph.domain.ActorType;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.domain.NegativeRuleOrigin;
import io.guestgraph.domain.ReviewStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who decided, recorded beside what was decided (US3). Pure JVM on {@link InMemoryGraph}, like the
 * rest of the engine's scenarios — attribution is engine behaviour, so it is pinned here rather
 * than only through the API.
 */
class ActorScenarioTest {

  private static final Actor STEWARD = new Actor(ActorType.HUMAN, "rob@example.com");
  private static final Actor AGENT = new Actor(ActorType.AGENT, "triage-bot");

  @Test
  @DisplayName("automatic resolution records SYSTEM and the matcher, never a person")
  void automaticResolutionIsAttributedToTheSystem() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();

    List<MergeEvent> events = f.graph.events();
    assertThat(events).isNotEmpty();
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(e.actor().type()).isEqualTo(ActorType.SYSTEM);
              assertThat(e.actor().id()).isEqualTo(e.matcherName());
            });
  }

  @Test
  @DisplayName("an unmerge records the actor it was given")
  void unmergeRecordsItsActor() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();
    f.record("b").email("anna@example.com").resolve();

    UUID guestId = f.outcomeOf("b").guestId();
    new UnmergeOperation(f.graph, f.engine)
        .unmerge(EngineFixture.TENANT, guestId, List.of(f.recordId("b")), STEWARD);

    assertThat(stewardEvent(f).actor()).isEqualTo(STEWARD);
  }

  @Test
  @DisplayName("a rule written by an unmerge names its creating actor")
  void unmergeWrittenRuleNamesItsActor() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();
    f.record("b").email("anna@example.com").resolve();

    new UnmergeOperation(f.graph, f.engine)
        .unmerge(EngineFixture.TENANT, f.outcomeOf("b").guestId(), List.of(f.recordId("b")), AGENT);

    assertThat(f.graph.negativeRules())
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.actor()).isEqualTo(AGENT));
  }

  /**
   * The record-level half of FR-015. The half that matters — a NULL actor_type surviving the mapper
   * and the explain serializer — is covered end to end in {@code ActorAttributionTest}.
   */
  @Test
  @DisplayName("an event stored without actor data reads back as unattributed, not as a failure")
  void preExistingEventsAreUnattributed() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();
    MergeEvent legacy = f.graph.events().getFirst();

    MergeEvent withoutActor =
        new MergeEvent(
            legacy.id(),
            legacy.tenantId(),
            legacy.kind(),
            legacy.guestId(),
            legacy.absorbedGuestIds(),
            legacy.sourceRecordIds(),
            legacy.matcherName(),
            legacy.confidence(),
            legacy.evidence(),
            legacy.excludedGuestIds(),
            Actor.unattributed(),
            legacy.createdAt());

    assertThat(withoutActor.actor().isAttributed()).isFalse();
    assertThat(withoutActor.actor().type()).isNull();
  }

  // --- FR-016a: rules are lifted, not deleted ---

  @Test
  @DisplayName("lifting a rule stamps the lifting actor and stops it gating")
  void liftingStampsTheActorAndStopsGating() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();
    f.record("b").email("anna@example.com").resolve();
    UUID guestId = f.outcomeOf("b").guestId();
    new UnmergeOperation(f.graph, f.engine)
        .unmerge(EngineFixture.TENANT, guestId, List.of(f.recordId("b")), STEWARD);

    List<UUID> a = List.of(f.recordId("a"));
    List<UUID> b = List.of(f.recordId("b"));
    assertThat(f.graph.negativeRuleBetween(EngineFixture.TENANT, a, b)).isTrue();

    f.graph.liftNegativeRulesBetween(EngineFixture.TENANT, a, b, AGENT);

    // No longer gates...
    assertThat(f.graph.negativeRuleBetween(EngineFixture.TENANT, a, b)).isFalse();
    // ...but the decision to override a human's split is still on the record.
    NegativeMatchRule lifted = f.graph.negativeRules().getFirst();
    assertThat(lifted.liftedAt()).isNotNull();
    assertThat(lifted.liftedActor()).isEqualTo(AGENT);
    assertThat(lifted.actor()).isEqualTo(STEWARD);
  }

  @Test
  @DisplayName("the same pair can be split again after a lift")
  void aPairMaySplitAgainAfterALift() {
    EngineFixture f = new EngineFixture();
    f.record("a").email("anna@example.com").resolve();
    f.record("b").email("anna@example.com").resolve();
    UUID guestId = f.outcomeOf("b").guestId();
    new UnmergeOperation(f.graph, f.engine)
        .unmerge(EngineFixture.TENANT, guestId, List.of(f.recordId("b")), STEWARD);

    List<UUID> a = List.of(f.recordId("a"));
    List<UUID> b = List.of(f.recordId("b"));
    f.graph.liftNegativeRulesBetween(EngineFixture.TENANT, a, b, AGENT);

    f.graph.saveNegativeRule(
        NegativeMatchRule.of(
            EngineFixture.TENANT,
            f.recordId("a"),
            f.recordId("b"),
            NegativeRuleOrigin.MANUAL,
            STEWARD));

    assertThat(f.graph.negativeRules()).hasSize(2);
    assertThat(f.graph.negativeRuleBetween(EngineFixture.TENANT, a, b)).isTrue();
  }

  @Test
  @DisplayName("a review decision records the actor it was given")
  void reviewDecisionRecordsItsActor() {
    EngineFixture f = new EngineFixture();
    f.graph.setReviewThreshold(1);
    f.record("a").email("shared@example.com").resolve();
    f.record("b").email("shared@example.com").resolve();

    var pending =
        f.graph.reviews().stream().filter(r -> r.status() == ReviewStatus.PENDING).findFirst();
    // The fixture set the sharing threshold to 1 above, so a review must exist — an early
    // return here would let the test report green while asserting nothing.
    assertThat(pending).isPresent();
    new ReviewDecisionOperation(f.graph, f.engine)
        .decide(EngineFixture.TENANT, pending.get().id(), true, STEWARD);

    assertThat(stewardEvent(f).actor()).isEqualTo(STEWARD);
  }

  /**
   * The steward's own event, not the last one written: an unmerge replays the detached records, so
   * automatic re-resolution events follow it and those are correctly attributed to SYSTEM.
   */
  private static MergeEvent stewardEvent(EngineFixture f) {
    return f.graph.events().stream()
        .filter(e -> e.actor().type() != ActorType.SYSTEM)
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("no steward-attributed event was recorded"));
  }
}
