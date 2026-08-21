package io.guestgraph.api;

import io.guestgraph.auth.TenantContext;
import io.guestgraph.persistence.GuestQueryService;
import io.guestgraph.persistence.TimelineQueryService;
import io.guestgraph.timeline.Association;
import io.guestgraph.timeline.ObjectObservation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A guest's current business-object associations — what this guest <em>has</em>, as distinct from
 * what was ever observed about them, which {@code /guests/{id}/records} answers unchanged.
 */
@RestController
@RequestMapping("/api/v1/guests/{guestId}/timeline")
public class TimelineController {

  private final TimelineQueryService timeline;
  private final GuestQueryService guests;

  public TimelineController(TimelineQueryService timeline, GuestQueryService guests) {
    this.timeline = timeline;
    this.guests = guests;
  }

  public record AssociationDto(
      String sourceSystem,
      String objectType,
      String objectId,
      String role,
      Integer position,
      String status,
      UUID successorGuestId,
      Instant businessStart,
      Instant businessEnd,
      int observationCount,
      ObservationDto currentObservation) {

    static AssociationDto of(Association a) {
      return new AssociationDto(
          a.sourceSystem(),
          a.objectType(),
          a.objectId(),
          a.role().name(),
          a.position(),
          a.status().name(),
          a.successorGuestId(),
          a.businessStart(),
          a.businessEnd(),
          a.observationCount(),
          ObservationDto.of(a.currentObservation()));
    }
  }

  public record ObservationDto(
      UUID sourceRecordId,
      Instant objectVersion,
      String role,
      Integer position,
      UUID guestId,
      Map<String, Object> extracted,
      Instant recordTimestamp,
      boolean needsReview) {

    static ObservationDto of(ObjectObservation o) {
      return new ObservationDto(
          o.sourceRecordId(),
          o.objectVersion(),
          o.role().name(),
          o.position(),
          o.guestId(),
          o.extracted(),
          o.recordTimestamp(),
          o.needsReview());
    }
  }

  @GetMapping
  public Map<String, Object> timeline(
      @PathVariable UUID guestId,
      @RequestParam(value = "includePast", defaultValue = "false") boolean includePast,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (limit < 1 || limit > 200) {
      throw new BadRequestException("limit must be 1..200");
    }
    UUID tenantId = TenantContext.tenantId();
    // A timeline for a guest that does not exist is a 404, not an empty page — an empty page
    // would read as "this guest has no bookings". An existence check, not a profile load:
    // findGuest would fetch every identifier and a link count on the timeline's hot path.
    if (!guests.guestExists(tenantId, guestId)) {
      throw new NotFoundException("No guest " + guestId + " in this tenant");
    }

    TimelineQueryService.Page page =
        timeline.timeline(tenantId, guestId, includePast, limit, cursor);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", page.items().stream().map(AssociationDto::of).toList());
    body.put("nextCursor", page.nextCursor());
    return body;
  }
}
