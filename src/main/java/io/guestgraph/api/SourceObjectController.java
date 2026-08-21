package io.guestgraph.api;

import io.guestgraph.api.TimelineController.ObservationDto;
import io.guestgraph.auth.TenantContext;
import io.guestgraph.persistence.TimelineQueryService;
import io.guestgraph.timeline.ObjectObservation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A business object as the graph knows it: who is on it now, and every observation ever made of it.
 *
 * <p>The history hangs off the object rather than off a guest on purpose. After a reassignment it
 * spans two guests, so presenting it as either guest's history would misrepresent the other's.
 */
@RestController
@RequestMapping("/api/v1/source-objects")
public class SourceObjectController {

  private final TimelineQueryService timeline;

  public SourceObjectController(TimelineQueryService timeline) {
    this.timeline = timeline;
  }

  public record RosterEntryDto(String role, Integer position, UUID guestId, UUID sourceRecordId) {

    static RosterEntryDto of(ObjectObservation o) {
      return new RosterEntryDto(o.role().name(), o.position(), o.guestId(), o.sourceRecordId());
    }
  }

  @GetMapping("/{sourceSystem}/{objectType}/{objectId}")
  public Map<String, Object> sourceObject(
      @PathVariable String sourceSystem,
      @PathVariable String objectType,
      @PathVariable String objectId) {
    TimelineQueryService.SourceObject object =
        timeline.sourceObject(TenantContext.tenantId(), sourceSystem, objectType, objectId);
    if (object == null) {
      throw new NotFoundException(
          "No " + objectType + " " + objectId + " from " + sourceSystem + " in this tenant");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceSystem", object.sourceSystem());
    body.put("objectType", object.objectType());
    body.put("objectId", object.objectId());
    body.put("currentVersion", object.currentVersion());
    body.put("businessStart", object.businessStart());
    body.put("businessEnd", object.businessEnd());
    body.put("roster", object.roster().stream().map(RosterEntryDto::of).toList());
    body.put("observations", object.observations().stream().map(ObservationDto::of).toList());
    return body;
  }
}
