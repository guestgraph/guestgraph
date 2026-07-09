package io.guestgraph.survivorship;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.SourceRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoldenProfileDeriverTest {

    private final GoldenProfileDeriver deriver = new GoldenProfileDeriver();

    @Test
    void mostRecentNonNullWinsPerField() {
        SourceRecord older = record(Map.of("firstName", "Anna", "email", "old@example.com"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        SourceRecord newer = record(Map.of("email", "new@example.com"),
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));

        Map<String, Object> profile = deriver.derive(List.of(newer, older));

        // Newer email wins; firstName survives from the older record because the newer one has none.
        assertThat(profile)
                .containsEntry("email", "new@example.com")
                .containsEntry("firstName", "Anna");
    }

    @Test
    void nullAndBlankValuesNeverOverwriteOlderData() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("firstName", null);
        withNull.put("lastName", "  ");
        SourceRecord older = record(Map.of("firstName", "Anna", "lastName", "Muster"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        SourceRecord newer = record(withNull,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));

        Map<String, Object> profile = deriver.derive(List.of(older, newer));

        assertThat(profile)
                .containsEntry("firstName", "Anna")
                .containsEntry("lastName", "Muster");
    }

    @Test
    void recordTimestampFallsBackToReceivedAt() {
        // No source timestamp on "late": its received_at (June) makes it the most recent.
        SourceRecord early = record(Map.of("firstName", "Old"),
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"));
        SourceRecord late = record(Map.of("firstName", "New"),
                null, Instant.parse("2026-06-01T00:00:00Z"));

        Map<String, Object> profile = deriver.derive(List.of(early, late));

        assertThat(profile).containsEntry("firstName", "New");
    }

    @Test
    void conflictingValuesAcrossThreeRecordsResolveByRecency() {
        SourceRecord first = record(Map.of("email", "one@example.com", "phone", "+41790000001"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        SourceRecord second = record(Map.of("email", "two@example.com"),
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        SourceRecord third = record(Map.of("email", "three@example.com", "firstName", "Anna"),
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"));

        Map<String, Object> profile = deriver.derive(List.of(third, first, second));

        assertThat(profile)
                .containsEntry("email", "three@example.com")
                .containsEntry("phone", "+41790000001")
                .containsEntry("firstName", "Anna");
    }

    @Test
    void emptyRecordListYieldsEmptyProfile() {
        assertThat(deriver.derive(List.of())).isEmpty();
    }

    private SourceRecord record(Map<String, Object> extracted, Instant recordTimestamp, Instant receivedAt) {
        return new SourceRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test", "key",
                "{}", extracted, List.of(), recordTimestamp, false, List.of(), receivedAt);
    }
}
