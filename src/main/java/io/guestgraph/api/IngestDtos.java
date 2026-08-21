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
      Map<String, Object> payload,
      SourceObjectDto sourceObject) {}

  /**
   * The business object this record describes (FR-001). Optional — records without it behave
   * exactly as before.
   *
   * <p>{@code version} is the instant the source object itself records as last modified, and it is
   * what decides the current roster. It must derive from source state alone: a submitter's own
   * clock or a delivery event id would break idempotency across retries and backfills (FR-019).
   *
   * <p>{@code position} is descriptive. It is shown to users but confers no identity across
   * versions, so a person shifting position when a guest list shrinks is not a reassignment.
   *
   * <p>These fields are the sole source of business-object identity; {@code externalKey} stays an
   * opaque duplicate-detection token the service never parses (FR-001a).
   */
  public record SourceObjectDto(
      String type,
      String id,
      String role,
      Integer position,
      Instant version,
      Instant businessStart,
      Instant businessEnd,
      /** Present in the submission but not an instant — flagged, never guessed at. */
      boolean businessStartUnparseable,
      boolean businessEndUnparseable) {}

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
