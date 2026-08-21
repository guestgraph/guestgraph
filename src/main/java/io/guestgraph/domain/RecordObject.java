package io.guestgraph.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The business object a source record describes, stated explicitly at ingest (FR-001). An optional
 * immutable companion of the record, like {@link NormalizedIdentifier} and {@link BlockKey}.
 *
 * <p>{@code objectVersion} is the instant the source object itself records as its last
 * modification; comparing it chronologically is what decides the current roster (FR-008). A record
 * whose submitted version could not be parsed gets no {@code RecordObject} at all, so it takes part
 * in no roster while remaining stored and flagged (FR-024).
 */
public record RecordObject(
    UUID id,
    UUID tenantId,
    UUID sourceRecordId,
    UUID sourceSystemId,
    String objectType,
    String objectId,
    ObjectRole role,
    Integer position,
    Instant objectVersion,
    Instant businessStart,
    Instant businessEnd) {

  public static RecordObject of(
      UUID tenantId,
      UUID sourceRecordId,
      UUID sourceSystemId,
      String objectType,
      String objectId,
      ObjectRole role,
      Integer position,
      Instant objectVersion,
      Instant businessStart,
      Instant businessEnd) {
    return new RecordObject(
        UUID.randomUUID(),
        tenantId,
        sourceRecordId,
        sourceSystemId,
        objectType,
        objectId,
        role,
        position,
        objectVersion,
        businessStart,
        businessEnd);
  }
}
