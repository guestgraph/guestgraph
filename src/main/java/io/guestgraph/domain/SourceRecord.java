package io.guestgraph.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A guest record exactly as received: the raw {@code payloadJson} is immutable
 * (Constitution II). {@code extracted} holds the normalized profile fields parsed
 * from the payload; {@code identifiers} the normalized strong identifiers.
 * The effective timestamp used by survivorship is {@code recordTimestamp}, falling
 * back to {@code receivedAt}.
 */
public record SourceRecord(
        UUID id,
        UUID tenantId,
        UUID sourceSystemId,
        String sourceSystemCode,
        String externalKey,
        String payloadJson,
        Map<String, Object> extracted,
        List<NormalizedIdentifier> identifiers,
        Instant recordTimestamp,
        boolean needsReview,
        List<String> needsReviewReasons,
        Instant receivedAt) {

    public Instant effectiveTimestamp() {
        return recordTimestamp != null ? recordTimestamp : receivedAt;
    }
}
