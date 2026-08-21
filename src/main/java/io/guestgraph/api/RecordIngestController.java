package io.guestgraph.api;

import io.guestgraph.api.IngestDtos.IngestRecordRequest;
import io.guestgraph.api.IngestDtos.IngestResult;
import io.guestgraph.api.IngestDtos.SourceObjectDto;
import io.guestgraph.auth.TenantContext;
import io.guestgraph.ingest.IngestService;
import io.guestgraph.ingest.UnknownSourceSystemException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/records — single record or batch (≤1000), resolved synchronously, one outcome per
 * record; a batch never fails atomically (research R11). An unparseable body is rejected by the
 * framework as RFC 9457 problem details.
 */
@RestController
@RequestMapping("/api/v1/records")
public class RecordIngestController {

  private static final Logger log = LoggerFactory.getLogger(RecordIngestController.class);

  private static final int MAX_BATCH_SIZE = 1000;

  private final IngestService ingestService;

  public RecordIngestController(IngestService ingestService) {
    this.ingestService = ingestService;
  }

  @PostMapping
  public Map<String, Object> ingest(@RequestBody Object body) {
    boolean batch = body instanceof List;
    List<?> items = batch ? (List<?>) body : List.of(body);
    if (items.isEmpty()) {
      throw new BadRequestException("Request contains no records");
    }
    if (items.size() > MAX_BATCH_SIZE) {
      throw new BadRequestException("Batch exceeds " + MAX_BATCH_SIZE + " records");
    }

    List<IngestResult> results = new ArrayList<>(items.size());
    for (Object item : items) {
      results.add(ingestOne(item, batch));
    }
    return Map.of("results", results);
  }

  private IngestResult ingestOne(Object item, boolean batch) {
    String externalKey =
        item instanceof Map<?, ?> m && m.get("externalKey") instanceof String k ? k : null;
    try {
      IngestRecordRequest request = parse(item);
      return ingestService.ingest(TenantContext.tenantId(), request);
    } catch (BadRequestException | UnknownSourceSystemException e) {
      // Single-record requests fail loudly; in a batch the record fails alone (never atomic).
      if (!batch) {
        throw e instanceof BadRequestException b ? b : new BadRequestException(e.getMessage());
      }
      return IngestResult.error(externalKey, e.getMessage());
    } catch (RuntimeException e) {
      // R11: records 1..N-1 already committed in their own transactions — their outcomes
      // must reach the client even when record N fails unexpectedly.
      if (!batch) {
        throw e;
      }
      log.error("Unexpected failure ingesting record '{}' in batch", externalKey, e);
      return IngestResult.failure(externalKey);
    }
  }

  private IngestRecordRequest parse(Object item) {
    if (!(item instanceof Map<?, ?> map)) {
      throw new BadRequestException("Each record must be a JSON object");
    }
    String sourceSystem = requireString(map, "sourceSystem");
    String externalKey = requireString(map, "externalKey");
    Object payload = map.get("payload");
    if (!(payload instanceof Map<?, ?> payloadMap)) {
      throw new BadRequestException("Field 'payload' is required and must be a JSON object");
    }
    Instant recordTimestamp = null;
    Object timestamp = map.get("recordTimestamp");
    if (timestamp instanceof String ts && !ts.isBlank()) {
      try {
        recordTimestamp = Instant.parse(ts);
      } catch (DateTimeParseException e) {
        throw new BadRequestException("Field 'recordTimestamp' must be an ISO-8601 instant");
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typedPayload = (Map<String, Object>) payloadMap;
    return new IngestRecordRequest(
        sourceSystem, externalKey, recordTimestamp, typedPayload, parseSourceObject(map));
  }

  /**
   * The optional business-object block. Note what does <em>not</em> throw here: a {@code version}
   * that is present but unparseable yields a null version, so the record is stored and flagged
   * rather than rejected (FR-024, Constitution III). Only a {@code sourceObject} that is not an
   * object at all is a malformed envelope, like {@code payload}.
   */
  private SourceObjectDto parseSourceObject(Map<?, ?> map) {
    Object raw = map.get("sourceObject");
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> object)) {
      throw new BadRequestException("Field 'sourceObject' must be a JSON object");
    }
    Object start = object.get("businessStart");
    Object end = object.get("businessEnd");
    return new SourceObjectDto(
        asString(object.get("type")),
        asString(object.get("id")),
        asString(object.get("role")),
        object.get("position") instanceof Number n ? n.intValue() : null,
        asInstantOrNull(object.get("version")),
        asInstantOrNull(start),
        asInstantOrNull(end),
        unparseable(start),
        unparseable(end));
  }

  private static String asString(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  /** Supplied, but not an instant — worth a reason, unlike simply being absent. */
  private static boolean unparseable(Object value) {
    return value != null && asInstantOrNull(value) == null;
  }

  private static Instant asInstantOrNull(Object value) {
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e) {
        return null; // flagged downstream, never a rejection
      }
    }
    return null;
  }

  private String requireString(Map<?, ?> map, String field) {
    if (map.get(field) instanceof String value && !value.isBlank()) {
      return value;
    }
    throw new BadRequestException(
        "Field '" + field + "' is required and must be a non-empty string");
  }
}
