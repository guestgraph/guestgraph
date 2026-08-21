package io.guestgraph.api;

import io.guestgraph.auth.ActorResolver;
import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.persistence.NegativeRuleService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Do-not-merge rules (US2): written by unmerge/reject, listed and lifted here. */
@RestController
@RequestMapping("/api/v1/negative-rules")
public class NegativeRuleController {

  public record NegativeRuleDto(
      UUID id,
      UUID recordA,
      UUID recordB,
      String origin,
      ActorDto actor,
      Instant createdAt,
      Instant liftedAt,
      ActorDto liftedActor) {

    static NegativeRuleDto of(NegativeMatchRule rule) {
      return new NegativeRuleDto(
          rule.id(),
          rule.recordA(),
          rule.recordB(),
          rule.origin().name(),
          ActorDto.of(rule.actor()),
          rule.createdAt(),
          rule.liftedAt(),
          ActorDto.of(rule.liftedActor()));
    }
  }

  private final NegativeRuleService service;

  public NegativeRuleController(NegativeRuleService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(value = "includeLifted", defaultValue = "false") boolean includeLifted,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (limit < 1 || limit > 200) {
      throw new BadRequestException("limit must be 1..200");
    }
    NegativeRuleService.RulePage page =
        service.list(TenantContext.tenantId(), includeLifted, limit, cursor);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rules", page.rules().stream().map(NegativeRuleDto::of).toList());
    body.put("total", page.total());
    body.put("nextCursor", page.nextCursor());
    return body;
  }

  @DeleteMapping("/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void lift(@PathVariable UUID ruleId) {
    if (!service.lift(TenantContext.tenantId(), ruleId, ActorResolver.actor())) {
      throw new NotFoundException("No active do-not-merge rule " + ruleId + " in this tenant");
    }
  }
}
