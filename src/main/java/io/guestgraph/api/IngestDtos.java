package io.guestgraph.api;

import io.guestgraph.domain.IngestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IngestDtos {

  private IngestDtos() {}

  public record IngestRecordRequest(
      String sourceSystem,
      String externalKey,
      Instant recordTimestamp,
      Map<String, Object> payload) {}

  /**
   * {@code status} always reports the real resolution outcome; {@code needsReview} flags a stored
   * record with data problems, and {@code pendingReviewIds} any parked suspicious matches — neither
   * masks the other.
   */
  public record IngestResult(
      String externalKey,
      UUID sourceRecordId,
      UUID guestId,
      IngestStatus status,
      boolean needsReview,
      List<UUID> pendingReviewIds,
      Map<String, Object> problem) {

    public static IngestResult error(String externalKey, String detail) {
      return new IngestResult(
          externalKey,
          null,
          null,
          IngestStatus.ERROR,
          false,
          List.of(),
          Map.of(
              "type",
              "https://guestgraph.io/problems/invalid-record",
              "title",
              "Invalid record",
              "status",
              400,
              "detail",
              detail));
    }

    /** Unexpected per-record failure inside a batch: reported, never swallowed (R11). */
    public static IngestResult failure(String externalKey) {
      return new IngestResult(
          externalKey,
          null,
          null,
          IngestStatus.ERROR,
          false,
          List.of(),
          Map.of(
              "type", "https://guestgraph.io/problems/internal-error",
              "title", "Internal server error",
              "status", 500,
              "detail", "An unexpected error occurred while processing this record"));
    }
  }
}
