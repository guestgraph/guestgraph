package io.guestgraph.api;

import io.guestgraph.domain.IngestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IngestDtos {

    private IngestDtos() {
    }

    public record IngestRecordRequest(
            String sourceSystem,
            String externalKey,
            Instant recordTimestamp,
            Map<String, Object> payload) {
    }

    public record IngestResult(
            String externalKey,
            UUID sourceRecordId,
            UUID guestId,
            IngestStatus status,
            List<UUID> pendingReviewIds,
            Map<String, Object> problem) {

        public static IngestResult error(String externalKey, String detail) {
            return new IngestResult(externalKey, null, null, IngestStatus.ERROR, List.of(), Map.of(
                    "type", "https://guestgraph.io/problems/invalid-record",
                    "title", "Invalid record",
                    "status", 400,
                    "detail", detail));
        }
    }
}
