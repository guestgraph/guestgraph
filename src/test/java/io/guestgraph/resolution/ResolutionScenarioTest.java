package io.guestgraph.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.MergeEventKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Table-driven engine scenarios (Constitution VI): record sets in → expected guest
 * clusters + expected MergeEvents out. Pure JVM — no Spring, no database.
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

        ResolutionOutcome second = fx.record("r2").email("anna@example.com").phone("+41791234567").resolve();

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
        ResolutionOutcome c = fx.record("c").email("shared-a@example.com").phone("+41791112233").resolve();

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
        ResolutionOutcome second = fx.record("r2").email("a@example.com").field("lastName", "Muster").resolve();

        assertThat(fx.graph.profileOf(second.guestId()))
                .containsEntry("firstName", "Anna")
                .containsEntry("lastName", "Muster");
    }
}
