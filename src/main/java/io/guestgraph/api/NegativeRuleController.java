package io.guestgraph.api;

import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.NegativeMatchRule;
import io.guestgraph.persistence.NegativeRuleService;
import java.time.Instant;
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
      UUID id, UUID recordA, UUID recordB, String origin, Instant createdAt) {

    static NegativeRuleDto of(NegativeMatchRule rule) {
      return new NegativeRuleDto(
          rule.id(), rule.recordA(), rule.recordB(), rule.origin().name(), rule.createdAt());
    }
  }

  private final NegativeRuleService service;

  public NegativeRuleController(NegativeRuleService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      @RequestParam(value = "offset", defaultValue = "0") int offset) {
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new BadRequestException("limit must be 1..200 and offset >= 0");
    }
    NegativeRuleService.RulePage page = service.list(TenantContext.tenantId(), limit, offset);
    return Map.of(
        "rules", page.rules().stream().map(NegativeRuleDto::of).toList(),
        "total", page.total());
  }

  @DeleteMapping("/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void lift(@PathVariable UUID ruleId) {
    if (!service.delete(TenantContext.tenantId(), ruleId)) {
      throw new NotFoundException("No do-not-merge rule " + ruleId + " in this tenant");
    }
  }
}
